package server

import (
	"context"
	"strings"
	"sync/atomic"
	"testing"

	"clawdroid/runtime/internal/ipc"
	"clawdroid/runtime/internal/task"
)

func TestStepFromMapValidatesTimeout(t *testing.T) {
	cases := []struct {
		name    string
		present bool
		value   interface{}
		want    int
		valid   bool
	}{
		{name: "missing uses scheduler default", valid: true},
		{name: "minimum", present: true, value: float64(1), want: 1, valid: true},
		{name: "maximum", present: true, value: float64(300000), want: 300000, valid: true},
		{name: "zero", present: true, value: float64(0)},
		{name: "negative", present: true, value: float64(-1)},
		{name: "too large", present: true, value: float64(300001)},
		{name: "fractional", present: true, value: float64(1.5)},
		{name: "wrong type", present: true, value: "1000"},
		{name: "null", present: true, value: nil},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			raw := map[string]interface{}{"action": "ping"}
			if tc.present {
				raw["timeout_ms"] = tc.value
			}
			step, err := stepFromMap(raw)
			if !tc.valid {
				if err == nil {
					t.Fatalf("stepFromMap accepted timeout_ms=%#v", tc.value)
				}
				if !strings.Contains(err.Error(), "timeout_ms") {
					t.Fatalf("error %q does not identify timeout_ms", err)
				}
				return
			}
			if err != nil {
				t.Fatalf("stepFromMap failed: %v", err)
			}
			if step.TimeoutMS != tc.want {
				t.Fatalf("timeout_ms: got %d, want %d", step.TimeoutMS, tc.want)
			}
		})
	}
}

func retryValidationTask(retry interface{}) map[string]interface{} {
	return map[string]interface{}{
		"task_id": "retry-validation",
		"steps": []interface{}{map[string]interface{}{
			"action":     "ping",
			"on_failure": string(task.StepPolicyRetry),
		}},
		"retry_policy": retry,
	}
}

func TestTaskFromMapValidatesRetryPolicy(t *testing.T) {
	valid := []struct {
		name string
		raw  interface{}
		want task.RetryPolicy
	}{
		{name: "empty policy uses defaults", raw: map[string]interface{}{}, want: task.RetryPolicy{MaxAttempts: 2, InitialDelayMS: 500, MaxDelayMS: 5000}},
		{name: "explicit zero disables retries", raw: map[string]interface{}{"max_attempts": float64(0), "initial_delay_ms": float64(0), "max_delay_ms": float64(0)}, want: task.RetryPolicy{}},
		{name: "minimum retry delay", raw: map[string]interface{}{"max_attempts": float64(1), "initial_delay_ms": float64(100), "max_delay_ms": float64(100)}, want: task.RetryPolicy{MaxAttempts: 1, InitialDelayMS: 100, MaxDelayMS: 100}},
		{name: "maximum boundaries", raw: map[string]interface{}{"max_attempts": float64(10), "initial_delay_ms": float64(60000), "max_delay_ms": float64(300000)}, want: task.RetryPolicy{MaxAttempts: 10, InitialDelayMS: 60000, MaxDelayMS: 300000}},
	}
	for _, tc := range valid {
		t.Run(tc.name, func(t *testing.T) {
			got, err := taskFromMap(retryValidationTask(tc.raw))
			if err != nil {
				t.Fatalf("taskFromMap failed: %v", err)
			}
			if got.RetryPolicy != tc.want {
				t.Fatalf("retry policy: got %#v, want %#v", got.RetryPolicy, tc.want)
			}
		})
	}

	invalid := []struct {
		name string
		raw  interface{}
	}{
		{name: "not an object", raw: []interface{}{}},
		{name: "negative attempts", raw: map[string]interface{}{"max_attempts": float64(-1)}},
		{name: "too many attempts", raw: map[string]interface{}{"max_attempts": float64(11)}},
		{name: "fractional attempts", raw: map[string]interface{}{"max_attempts": float64(1.5)}},
		{name: "wrong attempts type", raw: map[string]interface{}{"max_attempts": "1"}},
		{name: "negative initial delay", raw: map[string]interface{}{"initial_delay_ms": float64(-1)}},
		{name: "initial delay too large", raw: map[string]interface{}{"initial_delay_ms": float64(60001)}},
		{name: "retry delay below minimum", raw: map[string]interface{}{"max_attempts": float64(1), "initial_delay_ms": float64(99), "max_delay_ms": float64(100)}},
		{name: "fractional initial delay", raw: map[string]interface{}{"initial_delay_ms": float64(1.5)}},
		{name: "wrong initial delay type", raw: map[string]interface{}{"initial_delay_ms": "100"}},
		{name: "negative maximum delay", raw: map[string]interface{}{"max_delay_ms": float64(-1)}},
		{name: "maximum delay too large", raw: map[string]interface{}{"max_delay_ms": float64(300001)}},
		{name: "maximum below initial", raw: map[string]interface{}{"initial_delay_ms": float64(100), "max_delay_ms": float64(99)}},
		{name: "fractional maximum delay", raw: map[string]interface{}{"max_delay_ms": float64(1.5)}},
		{name: "wrong maximum delay type", raw: map[string]interface{}{"max_delay_ms": "100"}},
	}
	for _, tc := range invalid {
		t.Run(tc.name, func(t *testing.T) {
			if got, err := taskFromMap(retryValidationTask(tc.raw)); err == nil {
				t.Fatalf("taskFromMap accepted invalid retry policy %#v: %#v", tc.raw, got.RetryPolicy)
			}
		})
	}
}

func TestTaskSubmitExplicitZeroRetryPolicyDoesNotRetry(t *testing.T) {
	var calls atomic.Int32
	executor := lifecycleExecutorFunc(func(context.Context, string, string, string, map[string]interface{}, int) (int, string, map[string]interface{}, int64) {
		calls.Add(1)
		return 1, "always fails", nil, 1
	})
	srv := newLifecycleServer(t, executor)
	owner := newLifecycleSession(srv, "com.example.app", 10123, "sha256:signature-a")
	req := lifecycleTaskRequest("explicit-no-retry", ipc.ActionPing, map[string]interface{}{}, task.StepPolicyRetry)
	taskMap := req.Args["task"].(map[string]interface{})
	taskMap["retry_policy"] = map[string]interface{}{
		"max_attempts":     float64(0),
		"initial_delay_ms": float64(0),
		"max_delay_ms":     float64(0),
	}
	response := srv.handleTaskSubmit(owner, req)
	if !response.OK {
		t.Fatalf("task_submit failed: %#v", response)
	}
	waitForLifecycleTaskState(t, srv, "explicit-no-retry", task.TaskStateFailed)
	if got := calls.Load(); got != 1 {
		t.Fatalf("explicit max_attempts=0 executed %d times, want 1", got)
	}
}
