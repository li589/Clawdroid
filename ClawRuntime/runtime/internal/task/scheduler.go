package task

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"sync"
	"time"
)

// ErrQueueFull is returned when concurrent/queued tasks exceed scheduler limits.
var ErrQueueFull = errors.New("task queue full")

// StepExecutor is the interface for executing individual IPC actions.
type StepExecutor interface {
	ExecuteStep(ctx context.Context, sessionID, taskID string, action string, args map[string]interface{}, timeoutMS int) (code int, message string, data map[string]interface{}, latencyMS int64)
}

// StateChangeHandler is called whenever a task transitions state.
type StateChangeHandler func(taskID string, oldState, newState TaskState, snapshot map[string]interface{})

// SchedulerOptions bounds parallel multi-agent / multi-task execution.
type SchedulerOptions struct {
	// MaxConcurrent is the number of tasks that may run steps at once (default 8).
	MaxConcurrent int
	// MaxInflight is running + waiting-for-slot tasks (default 32). Excess Submit → ErrQueueFull.
	MaxInflight int
}

func (o SchedulerOptions) normalized() SchedulerOptions {
	if o.MaxConcurrent <= 0 {
		o.MaxConcurrent = 8
	}
	if o.MaxInflight <= 0 {
		o.MaxInflight = 32
	}
	if o.MaxInflight < o.MaxConcurrent {
		o.MaxInflight = o.MaxConcurrent
	}
	return o
}

// Scheduler manages task execution with bounded parallelism for multi-agent workloads.
type Scheduler struct {
	registry      *Registry
	executor      StepExecutor
	onStateChange StateChangeHandler
	opts          SchedulerOptions

	// taskMu protects running map, inflight counter, and terminal state updates.
	// It is NOT held during step execution to avoid blocking the executor.
	taskMu      sync.RWMutex
	running     map[string]context.CancelFunc // taskID -> cancel function
	inflight    int
	workerSlots chan struct{}
	closed      bool
}

// NewScheduler creates a new task scheduler with default concurrency bounds.
func NewScheduler(executor StepExecutor, onStateChange StateChangeHandler) *Scheduler {
	return NewSchedulerWithOptions(executor, onStateChange, SchedulerOptions{})
}

// NewSchedulerWithOptions creates a scheduler with explicit concurrency bounds.
func NewSchedulerWithOptions(executor StepExecutor, onStateChange StateChangeHandler, opts SchedulerOptions) *Scheduler {
	opts = opts.normalized()
	return &Scheduler{
		registry:      NewRegistry(),
		executor:      executor,
		onStateChange: onStateChange,
		opts:          opts,
		running:       make(map[string]context.CancelFunc),
		workerSlots:   make(chan struct{}, opts.MaxConcurrent),
	}
}

// Registry returns the task registry for direct query (e.g. task_get, task_list).
func (s *Scheduler) Registry() *Registry {
	return s.registry
}

// Options returns the effective concurrency options.
func (s *Scheduler) Options() SchedulerOptions {
	return s.opts
}

// InflightCount returns running + queued-waiting tasks.
func (s *Scheduler) InflightCount() int {
	s.taskMu.RLock()
	defer s.taskMu.RUnlock()
	return s.inflight
}

// RunningCount returns tasks currently holding a worker slot.
func (s *Scheduler) RunningCount() int {
	s.taskMu.RLock()
	defer s.taskMu.RUnlock()
	return len(s.running)
}

// Submit submits a new task and starts its execution when a worker slot is free.
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
		_ = s.registry.Remove(t.SessionID, t.ID)
		return fmt.Errorf("scheduler is closed")
	}
	if s.inflight >= s.opts.MaxInflight {
		s.taskMu.Unlock()
		_ = s.registry.Remove(t.SessionID, t.ID)
		return ErrQueueFull
	}
	s.inflight++
	s.transitionLocked(t, TaskStateQueued)
	t.QueuedAt = time.Now()
	s.taskMu.Unlock()

	go s.dispatchTask(t)
	return nil
}

func (s *Scheduler) dispatchTask(t *Task) {
	// Wait for a concurrency slot so multiple agents can run in parallel up to MaxConcurrent.
	s.workerSlots <- struct{}{}
	defer func() {
		<-s.workerSlots
		s.taskMu.Lock()
		if s.inflight > 0 {
			s.inflight--
		}
		s.taskMu.Unlock()
	}()

	s.taskMu.RLock()
	closed := s.closed
	s.taskMu.RUnlock()
	if closed {
		s.transition(t, TaskStateCancelled)
		return
	}
	s.runTask(t)
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
			curTask, ok := s.registry.Get(t.SessionID, t.ID)
			if !ok {
				return
			}
			if curTask.CurrentStep >= len(curTask.Steps) {
				s.transition(curTask, TaskStateSucceeded)
			}
			return
		}

		curTask, ok := s.registry.Get(t.SessionID, t.ID)
		if !ok || curTask.CurrentStep >= len(curTask.Steps) {
			return
		}
		step := curTask.Steps[curTask.CurrentStep]

		result := s.executeStepWithRetry(ctx, curTask, step)

		s.registry.Update(t.SessionID, t.ID, func(task *Task) error {
			task.StepResults = append(task.StepResults, result)
			return nil
		})

		if !result.OK {
			if step.OnFailure != StepPolicyRetry {
				if !s.handleStepFailure(curTask, step, result) {
					return
				}
			} else {
				if result.Code == -1 {
					s.transition(curTask, TaskStateCancelled)
					return
				}
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
		code, message, data, latencyMS := s.executor.ExecuteStep(ctx, t.SessionID, t.ID, step.Action, step.Args, step.TimeoutMS)
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

func (s *Scheduler) handleStepFailure(t *Task, step Step, result StepResult) bool {
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
	curTask, ok := s.registry.Get(t.SessionID, t.ID)
	if !ok {
		return
	}
	for i := len(curTask.StepResults) - 1; i >= 0; i-- {
		result := curTask.StepResults[i]
		step := curTask.Steps[result.StepIndex]
		if step.CompensateAction == "" {
			continue
		}

		select {
		case <-ctx.Done():
			s.transition(curTask, TaskStateFailed)
			return
		case <-time.After(100 * time.Millisecond):
		}

		_, _, _, _ = s.executor.ExecuteStep(ctx, t.SessionID, t.ID, step.CompensateAction, step.CompensateArgs, step.TimeoutMS)
	}

	s.transition(curTask, TaskStateFailed)
}
