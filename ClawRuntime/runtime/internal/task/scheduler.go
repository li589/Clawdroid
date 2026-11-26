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

	// taskMu protects queued/running cancel maps, inflight counter, idle signal,
	// and terminal state updates. It is NOT held during step execution.
	taskMu      sync.RWMutex
	queued      map[string]context.CancelFunc // taskID -> cancel function while waiting for a slot
	running     map[string]context.CancelFunc // taskID -> cancel function while executing/compensating
	inflight    int
	idleCh      chan struct{} // non-nil while work remains; closed when the scheduler is idle
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
		queued:        make(map[string]context.CancelFunc),
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

// WaitForIdle waits until all queued, running, and compensating work has stopped.
// It returns false if timeout elapses first. A non-positive timeout only checks
// the current state.
func (s *Scheduler) WaitForIdle(timeout time.Duration) bool {
	timer := time.NewTimer(timeout)
	defer timer.Stop()

	for {
		s.taskMu.RLock()
		idle := s.isIdleLocked()
		idleCh := s.idleCh
		s.taskMu.RUnlock()
		if idle {
			return true
		}

		select {
		case <-idleCh:
		case <-timer.C:
			return false
		}
	}
}

// isIdleLocked reports whether dispatch and any detached compensation goroutine
// have both finished. Caller must hold taskMu.
func (s *Scheduler) isIdleLocked() bool {
	return s.inflight == 0 && len(s.queued) == 0 && len(s.running) == 0
}

// signalIdleLocked wakes WaitForIdle callers once no scheduler-owned work
// remains. Caller must hold taskMu.
func (s *Scheduler) signalIdleLocked() {
	if s.idleCh != nil && s.isIdleLocked() {
		close(s.idleCh)
		s.idleCh = nil
	}
}

// RunningCount returns tasks currently holding a worker slot.
func (s *Scheduler) RunningCount() int {
	s.taskMu.RLock()
	defer s.taskMu.RUnlock()
	return len(s.running)
}

// Submit submits a new task and starts its execution when a worker slot is free.
// It copies t before applying defaults or publishing it, so the caller may read
// or mutate t after Submit returns without affecting the registered task.
func (s *Scheduler) Submit(ctx context.Context, t *Task) error {
	if t.ID == "" {
		return fmt.Errorf("task ID is required")
	}
	if len(t.Steps) == 0 {
		return fmt.Errorf("task must have at least one step")
	}

	owned := cloneTask(t)
	owned.State = TaskStateCreated
	if owned.OwnerKey == "" {
		owned.OwnerKey = owned.SessionID
	}
	if !owned.RetryPolicyConfigured && owned.RetryPolicy == (RetryPolicy{}) {
		owned.RetryPolicy = DefaultRetryPolicy()
	}
	if err := owned.RetryPolicy.Validate(); err != nil {
		return fmt.Errorf("invalid retry policy: %w", err)
	}
	for i := range owned.Steps {
		if owned.Steps[i].TimeoutMS == 0 {
			owned.Steps[i].TimeoutMS = DefaultStepTimeoutMS
		}
		if owned.Steps[i].TimeoutMS < MinStepTimeoutMS || owned.Steps[i].TimeoutMS > MaxStepTimeoutMS {
			return fmt.Errorf("steps[%d]: timeout_ms must be between %d and %d", i, MinStepTimeoutMS, MaxStepTimeoutMS)
		}
	}

	if err := s.registry.Submit(owned.OwnerKey, &owned); err != nil {
		return err
	}

	s.taskMu.Lock()
	if s.closed {
		s.taskMu.Unlock()
		_ = s.registry.Remove(owned.OwnerKey, owned.ID)
		return fmt.Errorf("scheduler is closed")
	}
	if s.inflight >= s.opts.MaxInflight {
		s.taskMu.Unlock()
		_ = s.registry.Remove(owned.OwnerKey, owned.ID)
		return ErrQueueFull
	}
	// Task lifetime is independent of the request/connection context. Cancellation
	// is explicit through Cancel or scheduler Close.
	taskCtx, cancel := context.WithCancel(context.Background())
	if s.idleCh == nil {
		s.idleCh = make(chan struct{})
	}
	s.inflight++
	s.queued[owned.ID] = cancel
	if !s.transitionLocked(owned.ID, TaskStateQueued) {
		delete(s.queued, owned.ID)
		cancel()
		s.inflight--
		s.signalIdleLocked()
		s.taskMu.Unlock()
		_ = s.registry.Remove(owned.OwnerKey, owned.ID)
		return fmt.Errorf("failed to queue task %q", owned.ID)
	}
	s.taskMu.Unlock()

	go s.dispatchTask(taskCtx, owned.ID)
	return nil
}

func (s *Scheduler) dispatchTask(ctx context.Context, taskID string) {
	defer func() {
		s.taskMu.Lock()
		if s.inflight > 0 {
			s.inflight--
		}
		s.signalIdleLocked()
		s.taskMu.Unlock()
	}()

	// A cancelled queued task must leave the queue without consuming a slot or
	// invoking its executor.
	select {
	case <-ctx.Done():
		s.cancelTask(taskID)
		return
	case s.workerSlots <- struct{}{}:
	}
	defer func() { <-s.workerSlots }()

	s.taskMu.Lock()
	cur, ok := s.registry.SnapshotByID(taskID)
	if !ok {
		s.taskMu.Unlock()
		return
	}
	if s.closed {
		if cur.State == TaskStateQueued {
			s.cancelLocked(taskID)
		}
		s.taskMu.Unlock()
		return
	}
	// Cancel and this transition are serialized by taskMu. Rechecking under the
	// lock closes the race where a queued task was cancelled while acquiring a slot.
	if cur.State != TaskStateQueued || !s.transitionLocked(taskID, TaskStateRunning) {
		s.taskMu.Unlock()
		return
	}
	cancel := s.queued[taskID]
	delete(s.queued, taskID)
	s.running[taskID] = cancel
	if err := s.registry.UpdateByID(taskID, func(t *Task) error {
		t.StartedAt = time.Now()
		return nil
	}); err != nil {
		s.taskMu.Unlock()
		return
	}
	s.taskMu.Unlock()

	s.runTask(ctx, taskID)
}

// Cancel requests cancellation of a queued, running, waiting, or retrying task.
// Compensating follows its own legal terminal path and cannot be cancelled here.
func (s *Scheduler) Cancel(taskID string) bool {
	s.taskMu.Lock()
	defer s.taskMu.Unlock()
	return s.cancelLocked(taskID)
}

func (s *Scheduler) cancelLocked(taskID string) bool {
	cur, ok := s.registry.SnapshotByID(taskID)
	if !ok || !schedulerCanCancel(cur.State) {
		return false
	}
	// Wake the executor before publishing Cancelled. The executor cannot perform a
	// competing scheduler transition until this lock is released and the state is set.
	if cancel := s.queued[taskID]; cancel != nil {
		cancel()
	}
	if cancel := s.running[taskID]; cancel != nil {
		cancel()
	}
	return s.transitionLocked(taskID, TaskStateCancelled)
}

func schedulerCanCancel(state TaskState) bool {
	switch state {
	case TaskStateQueued, TaskStateRunning, TaskStateWaitingSignal, TaskStateRetrying:
		return true
	default:
		return false
	}
}

func (s *Scheduler) cancelTask(taskID string) bool {
	s.taskMu.Lock()
	defer s.taskMu.Unlock()
	return s.cancelLocked(taskID)
}

// Close stops all active tasks and prevents new submissions. It is safe to call
// repeatedly or concurrently; cancellation is non-blocking, so callers that need
// quiescence should follow it with WaitForIdle.
func (s *Scheduler) Close() {
	s.taskMu.Lock()
	defer s.taskMu.Unlock()
	if s.closed {
		return
	}
	s.closed = true
	for taskID := range s.queued {
		s.cancelLocked(taskID)
	}
	for taskID := range s.running {
		if cancel := s.running[taskID]; cancel != nil {
			cancel()
		}
		if cur, ok := s.registry.SnapshotByID(taskID); ok && schedulerCanCancel(cur.State) {
			s.transitionLocked(taskID, TaskStateCancelled)
		}
	}
}

// transitionLocked transitions the task state via the registry so that every
// State/EndedAt write is serialized under r.mu (no cross-lock race). Caller
// must hold taskMu (protects s.running/inflight and the cancel-func bookkeeping).
func (s *Scheduler) transitionLocked(taskID string, newState TaskState) bool {
	// Read previous state from the registry (under r.mu) so we never race with
	// the single writer in Registry.SetState.
	oldState := newState
	if cur, ok := s.registry.SnapshotByID(taskID); ok {
		oldState = cur.State
	}
	// Apply the state change under the registry lock and read back the result.
	if !s.registry.SetState(taskID, newState) {
		// Transition invalid or task gone; read current state for logging.
		cur, ok := s.registry.SnapshotByID(taskID)
		if !ok {
			return false
		}
		slog.Error("task state transition failed", "task_id", taskID, "from", cur.State, "to", newState)
		return false
	}
	if newState.IsTerminal() {
		if cancel := s.queued[taskID]; cancel != nil {
			cancel()
			delete(s.queued, taskID)
		}
		if cancel := s.running[taskID]; cancel != nil {
			cancel()
			delete(s.running, taskID)
		}
	}
	if s.onStateChange != nil {
		// Build snapshot from registry for a consistent view.
		if cur, ok := s.registry.SnapshotByID(taskID); ok {
			s.onStateChange(taskID, oldState, newState, cur.StateSnapshot())
		}
	}
	return true
}

// transition transitions the task state safely. Acquires taskMu write lock.
func (s *Scheduler) transition(taskID string, newState TaskState) bool {
	s.taskMu.Lock()
	defer s.taskMu.Unlock()
	return s.transitionLocked(taskID, newState)
}

// GetTaskState reads the current task state. It reads through the registry lock
// (SnapshotByID returns a value copy) so it never races with Registry.SetState.
func (s *Scheduler) GetTaskState(taskID, sessionID string) (TaskState, bool) {
	cur, ok := s.registry.SnapshotByID(taskID)
	if !ok {
		return "", false
	}
	return cur.State, true
}

func (s *Scheduler) runTask(ctx context.Context, taskID string) {
	defer func() {
		// If the task entered Compensating state, the compensation goroutine
		// replaced s.running[taskID] with its own cancel func and will clean up.
		// Otherwise, remove the stale entry now.
		s.taskMu.Lock()
		cur, ok := s.registry.SnapshotByID(taskID)
		if !ok || cur.State != TaskStateCompensating {
			delete(s.running, taskID)
		}
		s.taskMu.Unlock()
	}()

	s.executeSteps(ctx, taskID)
}

func (s *Scheduler) executeSteps(ctx context.Context, taskID string) {
	for {
		err := s.registry.UpdateByID(taskID, func(task *Task) error {
			if task.CurrentStep >= len(task.Steps) {
				return fmt.Errorf("all steps completed")
			}
			if task.State == TaskStateCancelled {
				return fmt.Errorf("task cancelled")
			}
			return nil
		})
		if err != nil {
			curTask, ok := s.registry.SnapshotByID(taskID)
			if !ok {
				return
			}
			if curTask.CurrentStep >= len(curTask.Steps) {
				s.transition(taskID, TaskStateSucceeded)
			}
			return
		}

		curTask, ok := s.registry.SnapshotByID(taskID)
		if !ok || curTask.CurrentStep >= len(curTask.Steps) {
			return
		}
		step := curTask.Steps[curTask.CurrentStep]

		result := s.executeStepWithRetry(ctx, curTask, step)

		published := false
		_ = s.registry.UpdateByID(taskID, func(task *Task) error {
			if task.State == TaskStateCancelled || ctx.Err() != nil {
				return nil
			}
			task.StepResults = append(task.StepResults, result)
			if result.OK {
				// Keep result publication and progress advancement atomic so snapshots
				// never report more completed steps than the current step index.
				task.CurrentStep++
			}
			published = true
			return nil
		})
		if !published {
			if ctx.Err() != nil {
				s.cancelTask(taskID)
			}
			return
		}

		if !result.OK {
			if step.OnFailure != StepPolicyRetry {
				if !s.handleStepFailure(ctx, curTask, step, result) {
					return
				}
			} else {
				if result.Code == -1 {
					s.cancelTask(taskID)
					return
				}
				s.registry.UpdateByID(taskID, func(task *Task) error {
					task.Error = result.Message
					task.ErrorCode = result.Code
					return nil
				})
				s.transition(taskID, TaskStateFailed)
				return
			}
		}
	}
}

func (s *Scheduler) executeStepWithRetry(ctx context.Context, t Task, step Step) StepResult {
	retryCount := 0
	delay := time.Duration(t.RetryPolicy.InitialDelayMS) * time.Millisecond
	maxDelay := time.Duration(t.RetryPolicy.MaxDelayMS) * time.Millisecond
	if maxDelay == 0 {
		maxDelay = 5000 * time.Millisecond
	}

	for {
		if ctx.Err() != nil {
			return StepResult{
				StepIndex: t.CurrentStep,
				Action:    step.Action,
				Code:      -1,
				Message:   "cancelled before step execution",
			}
		}
		started := time.Now()
		code, message, data, latencyMS := s.executeStepContext(ctx, t, step)
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

		if result.OK || result.Code == -1 || step.OnFailure != StepPolicyRetry {
			return result
		}

		if retryCount >= t.RetryPolicy.MaxAttempts {
			return result
		}

		if !s.transition(t.ID, TaskStateRetrying) {
			result.Code = -1
			result.Message = "cancelled before retry backoff"
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
		if !s.transition(t.ID, TaskStateRunning) {
			result.Code = -1
			result.Message = "cancelled before retry"
			return result
		}
	}
}

func (s *Scheduler) executeStepContext(ctx context.Context, t Task, step Step) (int, string, map[string]interface{}, int64) {
	stepCtx := ctx
	cancel := func() {}
	if step.TimeoutMS > 0 {
		stepCtx, cancel = context.WithTimeout(ctx, time.Duration(step.TimeoutMS)*time.Millisecond)
	}
	defer cancel()
	return s.executor.ExecuteStep(stepCtx, t.SessionID, t.ID, step.Action, step.Args, step.TimeoutMS)
}

func (s *Scheduler) handleStepFailure(ctx context.Context, t Task, step Step, result StepResult) bool {
	if result.Code == -1 {
		s.cancelTask(t.ID)
		return false
	}

	switch step.OnFailure {
	case StepPolicyFail:
		s.registry.UpdateByID(t.ID, func(t *Task) error {
			t.Error = result.Message
			t.ErrorCode = result.Code
			return nil
		})
		s.transition(t.ID, TaskStateFailed)
		return false

	case StepPolicySkip:
		s.registry.UpdateByID(t.ID, func(t *Task) error {
			t.CurrentStep++
			return nil
		})
		return true

	case StepPolicyCompensate:
		s.registry.UpdateByID(t.ID, func(t *Task) error {
			t.Error = result.Message
			t.ErrorCode = result.Code
			return nil
		})
		// Compensation uses its own context, independent of the task's execution
		// context. Create it first, then atomically replace the running cancel and
		// publish Compensating under taskMu so Close cannot miss the new context.
		compCtx, cancelComp := context.WithCancel(context.Background())
		s.taskMu.Lock()
		previousCancel := s.running[t.ID]
		s.running[t.ID] = cancelComp
		if !s.transitionLocked(t.ID, TaskStateCompensating) {
			if previousCancel != nil {
				s.running[t.ID] = previousCancel
			} else {
				delete(s.running, t.ID)
			}
			s.taskMu.Unlock()
			cancelComp()
			return false
		}
		if previousCancel != nil {
			previousCancel()
		}
		s.taskMu.Unlock()
		go func() {
			defer cancelComp()
			s.runCompensation(compCtx, t)
			s.taskMu.Lock()
			delete(s.running, t.ID)
			s.signalIdleLocked()
			s.taskMu.Unlock()
		}()
		return false

	case StepPolicyRetry:
		return true

	default:
		s.registry.UpdateByID(t.ID, func(t *Task) error {
			t.Error = result.Message
			t.ErrorCode = result.Code
			return nil
		})
		s.transition(t.ID, TaskStateFailed)
		return false
	}
}

func (s *Scheduler) runCompensation(ctx context.Context, t Task) {
	curTask, ok := s.registry.SnapshotByID(t.ID)
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
			s.transition(curTask.ID, TaskStateFailed)
			return
		case <-time.After(100 * time.Millisecond):
		}

		compStep := step
		compStep.Action = step.CompensateAction
		compStep.Args = step.CompensateArgs
		_, _, _, _ = s.executeStepContext(ctx, t, compStep)
	}

	s.transition(curTask.ID, TaskStateFailed)
}
