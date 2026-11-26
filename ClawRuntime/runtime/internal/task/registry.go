package task

import (
	"fmt"
	"reflect"
	"sort"
	"sync"
	"time"
)

const (
	terminalTaskTTL  = 30 * time.Minute
	maxTerminalTasks = 200
)

// Registry manages tasks per stable owner with a global by-ID index for
// cross-connection lookups (task_get / task_cancel may arrive on a different
// connection than task_submit).
//
// Concurrency model: r.mu protects owners, tasksByID, and all Task state mutations
// (State / EndedAt / StepResults / CurrentStep / Error / ErrorCode). Callers MUST NOT
// mutate Task fields without going through Update/Cancel/transition helpers so that
// every write is serialized under r.mu. Reads from the scheduler that need a stable
// snapshot should use Snapshot() rather than racing on the live pointer.
type Registry struct {
	mu        sync.RWMutex
	owners    map[string]map[string]*Task // ownerKey -> taskID -> Task
	tasksByID map[string]*Task            // taskID -> Task (global index, cross-connection)
}

// NewRegistry creates a new task registry.
func NewRegistry() *Registry {
	return &Registry{
		owners:    make(map[string]map[string]*Task),
		tasksByID: make(map[string]*Task),
	}
}

// Submit adds a new task under the given stable owner key. The registry stores its
// own deep copy so later caller mutations cannot change registered task data.
// ownerKey falls back to SessionID for internal callers that do not use principals.
func (r *Registry) Submit(ownerKey string, t *Task) error {
	owned := cloneTask(t)
	if owned.OwnerKey == "" {
		owned.OwnerKey = ownerKey
	}
	if owned.OwnerKey == "" {
		owned.OwnerKey = owned.SessionID
	}
	if owned.OwnerKey == "" {
		owned.OwnerKey = owned.Owner.OwnerKey()
	}

	r.mu.Lock()
	defer r.mu.Unlock()

	r.pruneTerminalHistoryLocked(time.Now())
	if _, exists := r.tasksByID[owned.ID]; exists {
		return fmt.Errorf("task %q already exists", owned.ID)
	}
	if r.owners[owned.OwnerKey] == nil {
		r.owners[owned.OwnerKey] = make(map[string]*Task)
	}
	if _, exists := r.owners[owned.OwnerKey][owned.ID]; exists {
		return fmt.Errorf("task %q already exists for owner %q", owned.ID, owned.OwnerKey)
	}
	r.owners[owned.OwnerKey][owned.ID] = &owned
	r.tasksByID[owned.ID] = &owned
	return nil
}

// Get retrieves a task by stable owner key and task ID.
//
// Returns the live *Task pointer for internal scheduler use. Callers that only need
// a consistent read (e.g. IPC handlers) should prefer SnapshotByID to avoid racing
// with concurrent state updates.
func (r *Registry) Get(ownerKey, taskID string) (*Task, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.pruneTerminalHistoryLocked(time.Now())
	if ownerTasks, ok := r.owners[ownerKey]; ok {
		if t, ok := ownerTasks[taskID]; ok {
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
	r.mu.Lock()
	defer r.mu.Unlock()
	r.pruneTerminalHistoryLocked(time.Now())
	t, ok := r.tasksByID[taskID]
	if !ok {
		return Task{}, false
	}
	return t.cloneLocked(), true
}

// Snapshot returns a deep copy of the task, safe to read without locks.
func (r *Registry) Snapshot(ownerKey, taskID string) (Task, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.pruneTerminalHistoryLocked(time.Now())
	if ownerTasks, ok := r.owners[ownerKey]; ok {
		if t, ok := ownerTasks[taskID]; ok {
			return t.cloneLocked(), true
		}
	}
	return Task{}, false
}

// cloneLocked returns a snapshot using the current JSON/IPC deep-copy contract.
// Caller must hold r.mu (or have an exclusively owned task snapshot).
func (t *Task) cloneLocked() Task {
	return cloneTask(t)
}

// cloneTask duplicates the mutable data carried by the current JSON/IPC task
// contract. It does not support cyclic object graphs or arbitrary Go values with
// unexported state.
func cloneTask(t *Task) Task {
	cp := *t
	if t.Steps != nil {
		cp.Steps = make([]Step, len(t.Steps))
		for i, step := range t.Steps {
			cp.Steps[i] = step
			cp.Steps[i].Args = cloneDataMap(step.Args)
			cp.Steps[i].CompensateArgs = cloneDataMap(step.CompensateArgs)
		}
	}
	if t.StepResults != nil {
		cp.StepResults = make([]StepResult, len(t.StepResults))
		for i, result := range t.StepResults {
			cp.StepResults[i] = result
			cp.StepResults[i].Data = cloneDataMap(result.Data)
		}
	}
	return cp
}

func cloneDataMap(source map[string]interface{}) map[string]interface{} {
	if source == nil {
		return nil
	}
	cloned := make(map[string]interface{}, len(source))
	for key, value := range source {
		cloned[key] = cloneDataValue(value)
	}
	return cloned
}

// cloneDataValue recursively copies values in the current JSON/IPC data
// contract. Cyclic graphs and arbitrary unexported Go values are outside it.
func cloneDataValue(value interface{}) interface{} {
	if value == nil {
		return nil
	}
	return cloneDataReflect(reflect.ValueOf(value)).Interface()
}

// cloneDataReflect handles the exported, JSON-compatible shapes used by the
// current IPC payload contract; it is not a general-purpose object graph copier.
func cloneDataReflect(value reflect.Value) reflect.Value {
	switch value.Kind() {
	case reflect.Interface:
		if value.IsNil() {
			return reflect.Zero(value.Type())
		}
		cloned := reflect.New(value.Type()).Elem()
		cloned.Set(cloneDataReflect(value.Elem()))
		return cloned
	case reflect.Map:
		if value.IsNil() {
			return reflect.Zero(value.Type())
		}
		cloned := reflect.MakeMapWithSize(value.Type(), value.Len())
		iter := value.MapRange()
		for iter.Next() {
			cloned.SetMapIndex(iter.Key(), cloneDataReflect(iter.Value()))
		}
		return cloned
	case reflect.Slice:
		if value.IsNil() {
			return reflect.Zero(value.Type())
		}
		cloned := reflect.MakeSlice(value.Type(), value.Len(), value.Len())
		for i := 0; i < value.Len(); i++ {
			cloned.Index(i).Set(cloneDataReflect(value.Index(i)))
		}
		return cloned
	case reflect.Array:
		cloned := reflect.New(value.Type()).Elem()
		for i := 0; i < value.Len(); i++ {
			cloned.Index(i).Set(cloneDataReflect(value.Index(i)))
		}
		return cloned
	case reflect.Pointer:
		if value.IsNil() {
			return reflect.Zero(value.Type())
		}
		cloned := reflect.New(value.Type().Elem())
		cloned.Elem().Set(cloneDataReflect(value.Elem()))
		return cloned
	case reflect.Struct:
		cloned := reflect.New(value.Type()).Elem()
		cloned.Set(value)
		for i := 0; i < value.NumField(); i++ {
			if cloned.Field(i).CanSet() && value.Field(i).CanInterface() {
				cloned.Field(i).Set(cloneDataReflect(value.Field(i)))
			}
		}
		return cloned
	default:
		return value
	}
}

// Update atomically updates a task's state. The callback runs under r.mu.
func (r *Registry) Update(ownerKey, taskID string, upd func(*Task) error) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	ownerTasks, ok := r.owners[ownerKey]
	if !ok {
		return fmt.Errorf("owner %q not found", ownerKey)
	}
	t, ok := ownerTasks[taskID]
	if !ok {
		return fmt.Errorf("task %q not found for owner %q", taskID, ownerKey)
	}
	return upd(t)
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

// List returns deep snapshots of all tasks for a stable owner. The returned
// values share no mutable slices or nested data with the registry.
func (r *Registry) List(ownerKey string) []Task {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.pruneTerminalHistoryLocked(time.Now())
	ownerTasks := r.owners[ownerKey]
	result := make([]Task, 0, len(ownerTasks))
	for _, t := range ownerTasks {
		result = append(result, t.cloneLocked())
	}
	return result
}

// Cancel marks a task as cancelled if it belongs to ownerKey and can transition.
func (r *Registry) Cancel(ownerKey, taskID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	t, ok := r.tasksByID[taskID]
	if !ok || t.OwnerKey != ownerKey {
		return false
	}
	return r.cancelLocked(t)
}

// CancelByID marks a task as cancelled by global taskID, independent of owner.
func (r *Registry) CancelByID(taskID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	t, ok := r.tasksByID[taskID]
	if !ok {
		return false
	}
	return r.cancelLocked(t)
}

func (r *Registry) cancelLocked(t *Task) bool {
	if !t.State.CanTransitionTo(TaskStateCancelled) {
		return false
	}
	t.State = TaskStateCancelled
	t.EndedAt = time.Now()
	return true
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
	if newState == TaskStateQueued {
		t.QueuedAt = time.Now()
	}
	if newState.IsTerminal() {
		t.EndedAt = time.Now()
	}
	return true
}

// Remove deletes a task from the registry (used when Submit fails after insert).
func (r *Registry) Remove(ownerKey, taskID string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	ownerTasks := r.owners[ownerKey]
	if ownerTasks == nil {
		return fmt.Errorf("owner %q not found", ownerKey)
	}
	if _, ok := ownerTasks[taskID]; !ok {
		return fmt.Errorf("task %q not found for owner %q", taskID, ownerKey)
	}
	r.removeLocked(taskID)
	return nil
}

// CloseSession is retained for connection-lifecycle callers. Task ownership and
// history are stable across connections, so closing a session must not delete or
// detach any task. Retention is handled exclusively by TTL/capacity pruning.
func (r *Registry) CloseSession(string) {}

// Count returns the number of active (non-terminal) tasks for a stable owner.
func (r *Registry) Count(ownerKey string) int {
	r.mu.RLock()
	defer r.mu.RUnlock()

	ownerTasks := r.owners[ownerKey]
	if ownerTasks == nil {
		return 0
	}
	n := 0
	for _, t := range ownerTasks {
		if t.State.IsActive() {
			n++
		}
	}
	return n
}

// pruneTerminalHistoryLocked enforces both age and capacity limits. Active tasks
// are never candidates, including tasks whose EndedAt was accidentally populated.
// Caller must hold r.mu for writing.
func (r *Registry) pruneTerminalHistoryLocked(now time.Time) {
	cutoff := now.Add(-terminalTaskTTL)
	for taskID, t := range r.tasksByID {
		if t.State.IsTerminal() && !t.EndedAt.IsZero() && !t.EndedAt.After(cutoff) {
			r.removeLocked(taskID)
		}
	}

	terminal := make([]*Task, 0, len(r.tasksByID))
	for _, t := range r.tasksByID {
		if t.State.IsTerminal() && !t.EndedAt.IsZero() {
			terminal = append(terminal, t)
		}
	}
	if len(terminal) <= maxTerminalTasks {
		return
	}
	sort.Slice(terminal, func(i, j int) bool {
		if terminal[i].EndedAt.Equal(terminal[j].EndedAt) {
			return terminal[i].ID < terminal[j].ID
		}
		return terminal[i].EndedAt.Before(terminal[j].EndedAt)
	})
	for _, t := range terminal[:len(terminal)-maxTerminalTasks] {
		r.removeLocked(t.ID)
	}
}

func (r *Registry) removeLocked(taskID string) {
	t, ok := r.tasksByID[taskID]
	if !ok {
		return
	}
	delete(r.tasksByID, taskID)
	if ownerTasks := r.owners[t.OwnerKey]; ownerTasks != nil {
		delete(ownerTasks, taskID)
		if len(ownerTasks) == 0 {
			delete(r.owners, t.OwnerKey)
		}
	}
}
