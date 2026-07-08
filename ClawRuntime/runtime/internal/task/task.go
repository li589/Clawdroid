package task

import (
	"time"
)

// Step represents a single executable step within a task.
type Step struct {
	// Action is the IPC action name to execute (e.g. "inject_tap", "capture_screen").
	Action string `json:"action"`
	// Args are the parameters passed to the action.
	Args map[string]interface{} `json:"args"`
	// TimeoutMS is the per-step execution timeout in milliseconds.
	TimeoutMS int `json:"timeout_ms"`
	// OnFailure controls what to do when the step fails.
	OnFailure StepFailurePolicy `json:"on_failure"`
	// CompensateAction is the action to execute during compensating state (reverse order).
	CompensateAction string                 `json:"compensate_action,omitempty"`
	// CompensateArgs are the parameters for the compensation action.
	CompensateArgs map[string]interface{} `json:"compensate_args,omitempty"`
	// Description is an optional human-readable description.
	Description string `json:"description,omitempty"`
}

// StepFailurePolicy defines the recovery policy when a step fails.
type StepFailurePolicy string

const (
	StepPolicyFail      StepFailurePolicy = "fail"       // Abort task immediately
	StepPolicyRetry     StepFailurePolicy = "retry"      // Retry this step up to retry count
	StepPolicySkip      StepFailurePolicy = "skip"       // Skip this step and continue
	StepPolicyCompensate StepFailurePolicy = "compensate" // Enter compensating state
)

// StepResult holds the result of a step execution.
type StepResult struct {
	StepIndex   int                    `json:"step_index"`
	Action      string                 `json:"action"`
	OK          bool                   `json:"ok"`
	Code        int                    `json:"code"`
	Message     string                 `json:"message,omitempty"`
	Data        map[string]interface{} `json:"data,omitempty"`
	LatencyMS   int64                  `json:"latency_ms"`
	ExecutedAt  time.Time              `json:"executed_at"`
	RetryCount  int                    `json:"retry_count"`
}

// Task represents a multi-step execution task.
type Task struct {
	// ID is the globally unique task identifier.
	ID string `json:"task_id"`
	// SessionID is the IPC session that owns this task.
	SessionID string `json:"session_id"`
	// Name is an optional human-readable task name.
	Name string `json:"name,omitempty"`
	// Steps is the ordered list of steps to execute.
	Steps []Step `json:"steps"`
	// State is the current lifecycle state.
	State TaskState `json:"state"`
	// CurrentStep is the zero-based index of the currently executing or next step.
	CurrentStep int `json:"current_step"`
	// RetryPolicy controls retry behavior for all steps that use StepPolicyRetry.
	RetryPolicy RetryPolicy `json:"retry_policy"`
	// StepResults holds the result of each completed step.
	StepResults []StepResult `json:"step_results,omitempty"`
	// Error is the final error message if the task failed.
	Error string `json:"error,omitempty"`
	// ErrorCode is the error code of the final failure.
	ErrorCode int `json:"error_code,omitempty"`
	// CreatedAt is when the task was created.
	CreatedAt time.Time `json:"created_at"`
	// QueuedAt is when the task entered the queue.
	QueuedAt time.Time `json:"queued_at,omitempty"`
	// StartedAt is when the task began executing its first step.
	StartedAt time.Time `json:"started_at,omitempty"`
	// EndedAt is when the task reached a terminal state.
	EndedAt time.Time `json:"ended_at,omitempty"`
}

// RetryPolicy controls retry behavior for steps with StepPolicyRetry.
type RetryPolicy struct {
	// MaxAttempts is the maximum number of retry attempts per step (total = 1 + MaxAttempts).
	MaxAttempts int `json:"max_attempts"`
	// InitialDelayMS is the initial backoff delay in milliseconds.
	InitialDelayMS int `json:"initial_delay_ms"`
	// MaxDelayMS caps the exponential backoff.
	MaxDelayMS int `json:"max_delay_ms"`
}

// DefaultRetryPolicy returns a sensible default retry policy.
func DefaultRetryPolicy() RetryPolicy {
	return RetryPolicy{
		MaxAttempts:    2,
		InitialDelayMS: 500,
		MaxDelayMS:     5000,
	}
}

// StateSnapshot returns a snapshot of the task state suitable for event publishing.
func (t *Task) StateSnapshot() map[string]interface{} {
	snapshot := map[string]interface{}{
		"task_id":       t.ID,
		"session_id":    t.SessionID,
		"state":         string(t.State),
		"current_step":  t.CurrentStep,
		"total_steps":   len(t.Steps),
		"created_at":    t.CreatedAt.Unix(),
	}
	if !t.QueuedAt.IsZero() {
		snapshot["queued_at"] = t.QueuedAt.Unix()
	}
	if !t.StartedAt.IsZero() {
		snapshot["started_at"] = t.StartedAt.Unix()
	}
	if !t.EndedAt.IsZero() {
		snapshot["ended_at"] = t.EndedAt.Unix()
	}
	if t.Error != "" {
		snapshot["error"] = t.Error
	}
	if t.ErrorCode != 0 {
		snapshot["error_code"] = t.ErrorCode
	}
	if t.Name != "" {
		snapshot["name"] = t.Name
	}
	if len(t.StepResults) > 0 {
		snapshot["completed_steps"] = len(t.StepResults)
	}
	return snapshot
}
