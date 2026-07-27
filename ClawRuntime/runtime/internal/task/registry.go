package task

import (
	"fmt"
	"sync"
	"time"
)

// Registry manages tasks per session with a global by-ID index for cross-session
// lookups (task_get / task_cancel may arrive on a different connection than task_submit).
//
// Concurrency model: r.mu protects sessions, tasksByID, and all Task state mutations
// (State / EndedAt / StepResults / CurrentStep / Error / ErrorCode). Callers MUST NOT
// mutate Task fields without going through Update/Cancel/transition helpers so that
// every write is serialized under r.mu. Reads from the scheduler that need a stable
// snapshot should use Snapshot() rather than racing on the live pointer.
type Registry struct {
	mu         sync.RWMutex
	sessions   map[string]map[string]*Task // sessionID -> taskID -> Task
	tasksByID  map[string]*Task            // taskID -> Task (global index, cross-session)
}

// NewRegistry creates a new task registry.
func NewRegistry() *Registry {
	return &Registry{
		sessions:  make(map[string]map[string]*Task),
		tasksByID: make(map[string]*Task),
	}
}

// Submit adds a new task under the given session.
func (r *Registry) Submit(sessionID string, t *Task) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if _, exists := r.tasksByID[t.ID]; exists {
		return fmt.Errorf("task %q already exists", t.ID)
	}
	if r.sessions[sessionID] == nil {
		r.sessions[sessionID] = make(map[string]*Task)
	}
	if _, exists := r.sessions[sessionID][t.ID]; exists {
		return fmt.Errorf("task %q already exists in session %q", t.ID, sessionID)
	}
	r.sessions[sessionID][t.ID] = t
	r.tasksByID[t.ID] = t
	return nil
}

// Get retrieves a task by session and task ID.
//
// Returns the live *Task pointer for internal scheduler use. Callers that only need
// a consistent read (e.g. IPC handlers) should prefer SnapshotByID to avoid racing
// with concurrent state updates.
func (r *Registry) Get(sessionID, taskID string) (*Task, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	if sessionTasks, ok := r.sessions[sessionID]; ok {
		if t, ok := sessionTasks[taskID]; ok {
			return t, true
		}
	}
	return nil, false
}

// GetByID retrieves a task by ID across all sessions (cross-connection lookup).
// Returns the live *Task pointer; use SnapshotByID for a consistent read.
func (r *Registry) GetByID(taskID string) (*Task, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	t, ok := r.tasksByID[taskID]
	return t, ok
}

// SnapshotByID returns a deep copy of the task identified by taskID, safe to read
// without holding locks. Used by IPC handlers (task_get / task_cancel) which may
// arrive on a different connection than task_submit.
func (r *Registry) SnapshotByID(taskID string) (Task, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	t, ok := r.tasksByID[taskID]
	if !ok {
		return Task{}, false
	}
	return t.cloneLocked(), true
}

// Snapshot returns a deep copy of the task, safe to read without locks.
func (r *Registry) Snapshot(sessionID, taskID string) (Task, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	if sessionTasks, ok := r.sessions[sessionID]; ok {
		if t, ok := sessionTasks[taskID]; ok {
			return t.cloneLocked(), true
		}
	}
	return Task{}, false
}

// cloneLocked produces a value copy with duplicated slices/maps so callers cannot
// observe partial updates. Caller must hold r.mu (or a snapshot of the pointer).
func (t *Task) cloneLocked() Task {
	cp := *t
	if t.Steps != nil {
		cp.Steps = append([]Step(nil), t.Steps...)
	}
	if t.StepResults != nil {
		cp.StepResults = append([]StepResult(nil), t.StepResults...)
	}
	return cp
}

// Update atomically updates a task's state. The callback runs under r.mu.
func (r *Registry) Update(sessionID, taskID string, upd func(*Task) error) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	sessionTasks, ok := r.sessions[sessionID]
	if !ok {
		return fmt.Errorf("session %q not found", sessionID)
	}
	t, ok := sessionTasks[taskID]
	if !ok {
		return fmt.Errorf("task %q not found in session %q", taskID, sessionID)
	}
	if err := upd(t); err != nil {
		return err
	}
	return nil
}

// UpdateByID atomically updates a task located by global taskID (cross-session).
// Used by the scheduler which always knows the task ID.
func (r *Registry) UpdateByID(taskID string, upd func(*Task) error) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	t, ok := r.tasksByID[taskID]
	if !ok {
		return fmt.Errorf("task %q not found", taskID)
	}
	return upd(t)
}

// List returns all tasks for a given session.
func (r *Registry) List(sessionID string) []*Task {
	r.mu.RLock()
	defer r.mu.RUnlock()

	sessionTasks := r.sessions[sessionID]
	result := make([]*Task, 0, len(sessionTasks))
	for _, t := range sessionTasks {
		result = append(result, t)
	}
	return result
}

// Cancel marks a task as cancelled if it can transition. Looks up by global taskID
// so a cancel arriving on a different connection still works.
func (r *Registry) Cancel(sessionID, taskID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	t, ok := r.tasksByID[taskID]
	if !ok {
		return false
	}
	if t.State.CanTransitionTo(TaskStateCancelled) {
		t.State = TaskStateCancelled
		t.EndedAt = time.Now()
		return true
	}
	return false
}

// CancelByID marks a task as cancelled by global taskID, independent of session.
func (r *Registry) CancelByID(taskID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	t, ok := r.tasksByID[taskID]
	if !ok {
		return false
	}
	if t.State.CanTransitionTo(TaskStateCancelled) {
		t.State = TaskStateCancelled
		t.EndedAt = time.Now()
		return true
	}
	return false
}

// SetState transitions a task's state under r.mu. Used by the scheduler to ensure
// every State/EndedAt write is serialized through the registry lock.
func (r *Registry) SetState(taskID string, newState TaskState) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	t, ok := r.tasksByID[taskID]
	if !ok {
		return false
	}
	if err := t.State.MustTransition(newState); err != nil {
		return false
	}
	t.State = newState
	if newState.IsTerminal() {
		t.EndedAt = time.Now()
	}
	return true
}

// Remove deletes a task from the registry (used when Submit fails after insert).
func (r *Registry) Remove(sessionID, taskID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	sessionTasks := r.sessions[sessionID]
	if sessionTasks == nil {
		return fmt.Errorf("session %q not found", sessionID)
	}
	if _, ok := sessionTasks[taskID]; !ok {
		return fmt.Errorf("task %q not found in session %q", taskID, sessionID)
	}
	delete(sessionTasks, taskID)
	delete(r.tasksByID, taskID)
	return nil
}

// CloseSession removes terminal tasks for a session from both the session map
// and the global index. Active (non-terminal) tasks are kept in tasksByID so
// they can still be polled via task_get from a reconnected session; they are
// removed from the session map so CloseSession is idempotent. Once those tasks
// reach a terminal state, the scheduler will clean them up via Remove.
func (r *Registry) CloseSession(sessionID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	sessionTasks := r.sessions[sessionID]
	for taskID, t := range sessionTasks {
		if t.State.IsTerminal() {
			delete(r.tasksByID, taskID)
		}
	}
	delete(r.sessions, sessionID)
}

// Count returns the number of active (non-terminal) tasks for a session.
func (r *Registry) Count(sessionID string) int {
	r.mu.RLock()
	defer r.mu.RUnlock()

	sessionTasks := r.sessions[sessionID]
	if sessionTasks == nil {
		return 0
	}
	n := 0
	for _, t := range sessionTasks {
		if t.State.IsActive() {
			n++
		}
	}
	return n
}
