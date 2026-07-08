package task

import (
	"fmt"
	"sync"
)

// Registry manages tasks per session.
type Registry struct {
	mu       sync.RWMutex
	sessions map[string]map[string]*Task // sessionID -> taskID -> Task
}

// NewRegistry creates a new task registry.
func NewRegistry() *Registry {
	return &Registry{
		sessions: make(map[string]map[string]*Task),
	}
}

// Submit adds a new task under the given session.
func (r *Registry) Submit(sessionID string, t *Task) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.sessions[sessionID] == nil {
		r.sessions[sessionID] = make(map[string]*Task)
	}
	if _, exists := r.sessions[sessionID][t.ID]; exists {
		return fmt.Errorf("task %q already exists in session %q", t.ID, sessionID)
	}
	r.sessions[sessionID][t.ID] = t
	return nil
}

// Get retrieves a task by session and task ID.
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

// Update atomically updates a task's state.
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

// Cancel marks a task as cancelled if it can transition.
func (r *Registry) Cancel(sessionID, taskID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()

	sessionTasks := r.sessions[sessionID]
	if sessionTasks == nil {
		return false
	}
	t, ok := sessionTasks[taskID]
	if !ok {
		return false
	}
	if t.State.CanTransitionTo(TaskStateCancelled) {
		t.State = TaskStateCancelled
		return true
	}
	return false
}

// CloseSession removes all tasks for a session.
func (r *Registry) CloseSession(sessionID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
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
