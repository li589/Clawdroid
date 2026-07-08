package task

import (
	"context"
	"fmt"
	"log/slog"
	"math"
	"sync"
	"time"
)

// StepExecutor is the interface for executing individual IPC actions.
type StepExecutor interface {
	ExecuteStep(ctx context.Context, taskID string, action string, args map[string]interface{}, timeoutMS int) (code int, message string, data map[string]interface{}, latencyMS int64)
}

// StateChangeHandler is called whenever a task transitions state.
type StateChangeHandler func(taskID string, oldState, newState TaskState, snapshot map[string]interface{})

// Scheduler manages task execution.
type Scheduler struct {
	registry      *Registry
	executor     StepExecutor
	onStateChange StateChangeHandler

	// taskMu protects running map and terminal state updates.
	// It is NOT held during step execution to avoid blocking the executor.
	taskMu sync.RWMutex
	running map[string]context.CancelFunc // taskID -> cancel function
	closed   bool
}

// NewScheduler creates a new task scheduler.
func NewScheduler(executor StepExecutor, onStateChange StateChangeHandler) *Scheduler {
	return &Scheduler{
		registry:      NewRegistry(),
		executor:     executor,
		onStateChange: onStateChange,
		running:      make(map[string]context.CancelFunc),
	}
}

// Registry returns the task registry for direct query (e.g. task_get, task_list).
func (s *Scheduler) Registry() *Registry {
	return s.registry
}

// Submit submits a new task and starts its execution immediately.
func (s *Scheduler) Submit(ctx context.Context, t *Task) error {
	if t.ID == "" {
		return fmt.Errorf("task ID is required")
	}
	if len(t.Steps) == 0 {
		return fmt.Errorf("task must have at least one step")
	}

	t.State = TaskStateCreated
	if t.RetryPolicy.MaxAttempts == 0 && t.RetryPolicy.InitialDelayMS == 0 {
		t.RetryPolicy = DefaultRetryPolicy()
	}

	if err := s.registry.Submit(t.SessionID, t); err != nil {
		return err
	}

	s.taskMu.Lock()
	if s.closed {
		s.taskMu.Unlock()
		return fmt.Errorf("scheduler is closed")
	}
	s.transitionLocked(t, TaskStateQueued)
	t.QueuedAt = time.Now()
	s.taskMu.Unlock()

	go s.runTask(t)

	return nil
}

// Cancel requests cancellation of a running task.
func (s *Scheduler) Cancel(taskID string) bool {
	s.taskMu.RLock()
	defer s.taskMu.RUnlock()
	cancel, ok := s.running[taskID]
	if !ok {
		return false
	}
	cancel()
	return true
}

// Close stops all running tasks and prevents new submissions.
func (s *Scheduler) Close() {
	s.taskMu.Lock()
	defer s.taskMu.Unlock()
	s.closed = true
	for _, cancel := range s.running {
		cancel()
	}
}

// transitionLocked transitions the task state. Caller must hold taskMu.
func (s *Scheduler) transitionLocked(t *Task, newState TaskState) {
	oldState := t.State
	if err := oldState.MustTransition(newState); err != nil {
		slog.Error("task state transition failed", "task_id", t.ID, "from", oldState, "to", newState, "error", err)
		return
	}
	t.State = newState
	if newState.IsTerminal() {
		t.EndedAt = time.Now()
		if cancel, ok := s.running[t.ID]; ok {
			cancel()
			delete(s.running, t.ID)
		}
	}
	if s.onStateChange != nil {
		s.onStateChange(t.ID, oldState, newState, t.StateSnapshot())
	}
}

// transition transitions the task state safely. Acquires taskMu write lock.
func (s *Scheduler) transition(t *Task, newState TaskState) {
	s.taskMu.Lock()
	defer s.taskMu.Unlock()
	s.transitionLocked(t, newState)
}

// GetTaskState reads the current task state without holding locks during execution.
func (s *Scheduler) GetTaskState(taskID, sessionID string) (TaskState, bool) {
	t, ok := s.registry.Get(sessionID, taskID)
	if !ok {
		return "", false
	}
	return t.State, true
}

func (s *Scheduler) runTask(t *Task) {
	ctx, cancel := context.WithCancel(context.Background())
	s.taskMu.Lock()
	s.running[t.ID] = cancel
	s.taskMu.Unlock()

	defer func() {
		cancel()
		s.taskMu.Lock()
		delete(s.running, t.ID)
		s.taskMu.Unlock()
	}()

	s.transition(t, TaskStateRunning)
	s.registry.Update(t.SessionID, t.ID, func(t *Task) error {
		t.StartedAt = time.Now()
		return nil
	})

	s.executeSteps(ctx, t)
}

func (s *Scheduler) executeSteps(ctx context.Context, t *Task) {
	for {
		// Single atomic update to check completion and cancellation.
		err := s.registry.Update(t.SessionID, t.ID, func(task *Task) error {
			if task.CurrentStep >= len(task.Steps) {
				return fmt.Errorf("all steps completed")
			}
			if task.State == TaskStateCancelled {
				return fmt.Errorf("task cancelled")
			}
			return nil
		})
		if err != nil {
			// Determine exit reason and transition to terminal state.
			curTask, ok := s.registry.Get(t.SessionID, t.ID)
			if !ok {
				return
			}
			if curTask.CurrentStep >= len(curTask.Steps) {
				// All steps completed normally.
				s.transition(curTask, TaskStateSucceeded)
			}
			// Otherwise cancelled or already terminal — nothing to do.
			return
		}

		curTask, ok := s.registry.Get(t.SessionID, t.ID)
		if !ok || curTask.CurrentStep >= len(curTask.Steps) {
			// Safety check; the Update above should have caught this.
			return
		}
		step := curTask.Steps[curTask.CurrentStep]

		result := s.executeStepWithRetry(ctx, curTask, step)

		s.registry.Update(t.SessionID, t.ID, func(task *Task) error {
			task.StepResults = append(task.StepResults, result)
			return nil
		})

		if !result.OK {
			// Retry policy is handled entirely inside executeStepWithRetry;
			// handleStepFailure is called only for non-retry failure policies.
			if step.OnFailure != StepPolicyRetry {
				if !s.handleStepFailure(curTask, step, result) {
					return
				}
			} else {
				// Context cancellation takes precedence over retry failure.
				if result.Code == -1 {
					s.transition(curTask, TaskStateCancelled)
					return
				}
				// Retry exhausted: record the failure and transition to Failed.
				s.registry.Update(t.SessionID, t.ID, func(task *Task) error {
					task.Error = result.Message
					task.ErrorCode = result.Code
					return nil
				})
				s.transition(curTask, TaskStateFailed)
				return
			}
		} else {
			s.registry.Update(t.SessionID, t.ID, func(task *Task) error {
				task.CurrentStep++
				return nil
			})
			// Task remains in Running state; no self-transition needed.
		}
	}
}

func (s *Scheduler) executeStepWithRetry(ctx context.Context, t *Task, step Step) StepResult {
	retryCount := 0
	delay := time.Duration(t.RetryPolicy.InitialDelayMS) * time.Millisecond
	maxDelay := time.Duration(t.RetryPolicy.MaxDelayMS) * time.Millisecond
	if maxDelay == 0 {
		maxDelay = 5000 * time.Millisecond
	}

	for {
		started := time.Now()
		code, message, data, latencyMS := s.executor.ExecuteStep(ctx, t.ID, step.Action, step.Args, step.TimeoutMS)
		result := StepResult{
			StepIndex:  t.CurrentStep,
			Action:     step.Action,
			OK:         code == 0,
			Code:       code,
			Message:    message,
			Data:       data,
			LatencyMS:  latencyMS,
			ExecutedAt: started,
			RetryCount: retryCount,
		}

		if result.OK || step.OnFailure != StepPolicyRetry {
			return result
		}

		if retryCount >= t.RetryPolicy.MaxAttempts {
			return result
		}

		// Wait with exponential backoff before retrying.
		// Do not transition to Retrying state — step-level retry stays within Running.
		select {
		case <-ctx.Done():
			result.Code = -1
			result.Message = "cancelled during retry backoff"
			return result
		case <-time.After(delay):
		}

		delay = time.Duration(math.Min(float64(delay*2), float64(maxDelay)))
		retryCount++
	}
}

// handleStepFailure processes a failed step.
// Returns true if execution should continue, false if the task reached a terminal state.
func (s *Scheduler) handleStepFailure(t *Task, step Step, result StepResult) bool {
	// Context cancellation takes precedence over step failure.
	if result.Code == -1 {
		s.transition(t, TaskStateCancelled)
		return false
	}

	switch step.OnFailure {
	case StepPolicyFail:
		s.registry.Update(t.SessionID, t.ID, func(t *Task) error {
			t.Error = result.Message
			t.ErrorCode = result.Code
			return nil
		})
		s.transition(t, TaskStateFailed)
		return false

	case StepPolicySkip:
		s.registry.Update(t.SessionID, t.ID, func(t *Task) error {
			t.CurrentStep++
			return nil
		})
		// Task remains in Running state.
		return true

	case StepPolicyCompensate:
		s.registry.Update(t.SessionID, t.ID, func(t *Task) error {
			t.Error = result.Message
			t.ErrorCode = result.Code
			return nil
		})
		s.transition(t, TaskStateCompensating)
		go s.runCompensation(context.Background(), t)
		return false

	case StepPolicyRetry:
		// Already handled in executeStepWithRetry; should not reach here
		return true

	default:
		s.registry.Update(t.SessionID, t.ID, func(t *Task) error {
			t.Error = result.Message
			t.ErrorCode = result.Code
			return nil
		})
		s.transition(t, TaskStateFailed)
		return false
	}
}

func (s *Scheduler) runCompensation(ctx context.Context, t *Task) {
	for i := len(t.StepResults) - 1; i >= 0; i-- {
		result := t.StepResults[i]
		step := t.Steps[result.StepIndex]
		if step.CompensateAction == "" {
			continue
		}

		select {
		case <-ctx.Done():
			s.transition(t, TaskStateFailed)
			return
		case <-time.After(100 * time.Millisecond):
		}

		_, _, _, _ = s.executor.ExecuteStep(ctx, t.ID, step.CompensateAction, step.CompensateArgs, step.TimeoutMS)
	}

	s.transition(t, TaskStateFailed)
}
