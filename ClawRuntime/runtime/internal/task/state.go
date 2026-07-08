package task

import "fmt"

// TaskState represents the lifecycle state of a task.
type TaskState string

const (
	TaskStateCreated      TaskState = "Created"
	TaskStateQueued       TaskState = "Queued"
	TaskStateRunning      TaskState = "Running"
	TaskStateWaitingSignal TaskState = "WaitingSignal"
	TaskStateRetrying     TaskState = "Retrying"
	TaskStateSucceeded    TaskState = "Succeeded"
	TaskStateFailed       TaskState = "Failed"
	TaskStateCancelled    TaskState = "Cancelled"
	TaskStateCompensating TaskState = "Compensating"
)

// validTransitions defines the allowed state transitions per protocol.md Section 7.
var validTransitions = map[TaskState][]TaskState{
	TaskStateCreated:       {TaskStateQueued, TaskStateCancelled},
	TaskStateQueued:        {TaskStateRunning, TaskStateCancelled},
	TaskStateRunning:       {TaskStateSucceeded, TaskStateFailed, TaskStateCancelled, TaskStateCompensating, TaskStateWaitingSignal, TaskStateRetrying},
	TaskStateWaitingSignal: {TaskStateRunning, TaskStateCancelled},
	TaskStateRetrying:      {TaskStateRunning, TaskStateFailed},
	TaskStateSucceeded:     {},
	TaskStateFailed:        {},
	TaskStateCancelled:     {},
	TaskStateCompensating:  {TaskStateSucceeded, TaskStateFailed},
}

// CanTransitionTo returns true if a transition from the current state to next is valid.
func (s TaskState) CanTransitionTo(next TaskState) bool {
	allowed, ok := validTransitions[s]
	if !ok {
		return false
	}
	for _, t := range allowed {
		if t == next {
			return true
		}
	}
	return false
}

// MustTransition validates and transitions the task to next state.
// Returns an error if the transition is invalid.
func (s TaskState) MustTransition(next TaskState) error {
	if !s.CanTransitionTo(next) {
		return fmt.Errorf("invalid state transition: %s -> %s", s, next)
	}
	return nil
}

// IsTerminal returns true if the state is a terminal state (no outgoing transitions).
func (s TaskState) IsTerminal() bool {
	transitions, ok := validTransitions[s]
	return !ok || len(transitions) == 0
}

// IsActive returns true if the task is in a non-terminal, non-created state.
func (s TaskState) IsActive() bool {
	return s == TaskStateQueued || s == TaskStateRunning ||
		s == TaskStateWaitingSignal || s == TaskStateRetrying ||
		s == TaskStateCompensating
}

// IsRetryable returns true if the task can enter the Retrying state from the current state.
func (s TaskState) IsRetryable() bool {
	return s == TaskStateRunning || s == TaskStateWaitingSignal
}
