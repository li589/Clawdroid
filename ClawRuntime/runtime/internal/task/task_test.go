package task

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"
)

// ─── State tests ──────────────────────────────────────────────────────────────

func TestTaskState_CanTransitionTo(t *testing.T) {
	cases := []struct {
		from   TaskState
		to     TaskState
		expect bool
	}{
		// Valid transitions per protocol.md Section 7
		{TaskStateCreated, TaskStateQueued, true},
		{TaskStateCreated, TaskStateCancelled, true},
		{TaskStateCreated, TaskStateRunning, false},
		{TaskStateCreated, TaskStateSucceeded, false},
		{TaskStateQueued, TaskStateRunning, true},
		{TaskStateQueued, TaskStateCancelled, true},
		{TaskStateQueued, TaskStateSucceeded, false},
		{TaskStateRunning, TaskStateSucceeded, true},
		{TaskStateRunning, TaskStateFailed, true},
		{TaskStateRunning, TaskStateCancelled, true},
		{TaskStateRunning, TaskStateCompensating, true},
		{TaskStateRunning, TaskStateWaitingSignal, true},
		{TaskStateRunning, TaskStateQueued, false},
		{TaskStateWaitingSignal, TaskStateRunning, true},
		{TaskStateWaitingSignal, TaskStateCancelled, true},
		{TaskStateWaitingSignal, TaskStateSucceeded, false},
		{TaskStateRetrying, TaskStateRunning, true},
		{TaskStateRetrying, TaskStateFailed, true},
		{TaskStateRetrying, TaskStateCancelled, false},
		// Terminal states have no outgoing transitions
		{TaskStateSucceeded, TaskStateRunning, false},
		{TaskStateSucceeded, TaskStateFailed, false},
		{TaskStateFailed, TaskStateRunning, false},
		{TaskStateFailed, TaskStateSucceeded, false},
		{TaskStateCancelled, TaskStateRunning, false},
		{TaskStateCancelled, TaskStateQueued, false},
		// Compensating transitions
		{TaskStateCompensating, TaskStateSucceeded, true},
		{TaskStateCompensating, TaskStateFailed, true},
		{TaskStateCompensating, TaskStateRunning, false},
	}
	for _, c := range cases {
		got := c.from.CanTransitionTo(c.to)
		if got != c.expect {
			t.Errorf("CanTransitionTo(%s -> %s): got %v, want %v", c.from, c.to, got, c.expect)
		}
	}
}

func TestTaskState_MustTransition(t *testing.T) {
	// Valid transition should not error
	err := TaskStateCreated.MustTransition(TaskStateQueued)
	if err != nil {
		t.Errorf("valid transition returned error: %v", err)
	}

	// Invalid transition should error
	err = TaskStateCreated.MustTransition(TaskStateSucceeded)
	if err == nil {
		t.Error("invalid transition did not return error")
	}
	if !strings.Contains(err.Error(), "invalid state transition") {
		t.Errorf("error message unexpected: %v", err)
	}
}

func TestTaskState_IsTerminal(t *testing.T) {
	terminal := []TaskState{TaskStateSucceeded, TaskStateFailed, TaskStateCancelled}
	nonTerminal := []TaskState{TaskStateCreated, TaskStateQueued, TaskStateRunning, TaskStateWaitingSignal, TaskStateRetrying, TaskStateCompensating}

	for _, s := range terminal {
		if !s.IsTerminal() {
			t.Errorf("%s should be terminal", s)
		}
	}
	for _, s := range nonTerminal {
		if s.IsTerminal() {
			t.Errorf("%s should not be terminal", s)
		}
	}
}

func TestTaskState_IsActive(t *testing.T) {
	active := []TaskState{TaskStateQueued, TaskStateRunning, TaskStateWaitingSignal, TaskStateRetrying, TaskStateCompensating}
	notActive := []TaskState{TaskStateCreated, TaskStateSucceeded, TaskStateFailed, TaskStateCancelled}

	for _, s := range active {
		if !s.IsActive() {
			t.Errorf("%s should be active", s)
		}
	}
	for _, s := range notActive {
		if s.IsActive() {
			t.Errorf("%s should not be active", s)
		}
	}
}

func TestTaskState_IsRetryable(t *testing.T) {
	retryable := []TaskState{TaskStateRunning, TaskStateWaitingSignal}
	notRetryable := []TaskState{TaskStateCreated, TaskStateQueued, TaskStateRetrying, TaskStateSucceeded, TaskStateFailed, TaskStateCancelled, TaskStateCompensating}

	for _, s := range retryable {
		if !s.IsRetryable() {
			t.Errorf("%s should be retryable", s)
		}
	}
	for _, s := range notRetryable {
		if s.IsRetryable() {
			t.Errorf("%s should not be retryable", s)
		}
	}
}

// ─── Registry tests ───────────────────────────────────────────────────────────

func makeTask(id, sessionID string) *Task {
	return &Task{
		ID:        id,
		SessionID: sessionID,
		Steps:     []Step{{Action: "ping", Args: map[string]interface{}{}}},
		State:     TaskStateCreated,
	}
}

func TestRegistry_SubmitAndGet(t *testing.T) {
	r := NewRegistry()
	t1 := makeTask("task-1", "sess-a")
	t2 := makeTask("task-2", "sess-a")
	t3 := makeTask("task-3", "sess-b")

	r.Submit("sess-a", t1)
	r.Submit("sess-a", t2)
	r.Submit("sess-b", t3)

	got, ok := r.Get("sess-a", "task-1")
	if !ok {
		t.Fatal("task-1 not found")
	}
	if got != t1 {
		t.Error("Get returned wrong task")
	}

	_, ok = r.Get("sess-a", "task-3")
	if ok {
		t.Error("cross-session lookup should fail")
	}

	_, ok = r.Get("sess-x", "task-1")
	if ok {
		t.Error("nonexistent session lookup should fail")
	}
}

func TestRegistry_Update(t *testing.T) {
	r := NewRegistry()
	t1 := makeTask("task-1", "sess-a")
	r.Submit("sess-a", t1)

	err := r.Update("sess-a", "task-1", func(t *Task) error {
		t.Name = "updated"
		return nil
	})
	if err != nil {
		t.Errorf("Update failed: %v", err)
	}

	got, ok := r.Get("sess-a", "task-1")
	if !ok {
		t.Fatal("task-1 not found")
	}
	if got.Name != "updated" {
		t.Errorf("Update did not apply: got %q", got.Name)
	}

	// Update on nonexistent task returns an error
	err = r.Update("sess-a", "nonexistent", func(t *Task) error {
		t.Name = "should-not-apply"
		return nil
	})
	if err == nil {
		t.Error("Update on nonexistent should return error")
	}
}

func TestRegistry_List(t *testing.T) {
	r := NewRegistry()
	for i := 0; i < 3; i++ {
		r.Submit("sess-a", makeTask("task-a-"+string(rune('1'+i)), "sess-a"))
	}
	r.Submit("sess-b", makeTask("task-b-1", "sess-b"))

	list := r.List("sess-a")
	if len(list) != 3 {
		t.Errorf("List(sess-a): got %d, want 3", len(list))
	}

	list = r.List("sess-b")
	if len(list) != 1 {
		t.Errorf("List(sess-b): got %d, want 1", len(list))
	}

	list = r.List("sess-none")
	if len(list) != 0 {
		t.Errorf("List(nonexistent): got %d, want 0", len(list))
	}
}

func TestRegistry_Count(t *testing.T) {
	r := NewRegistry()
	if r.Count("sess-a") != 0 {
		t.Errorf("empty count: got %d", r.Count("sess-a"))
	}
	t1 := makeTask("t1", "sess-a")
	t2 := makeTask("t2", "sess-a")
	t3 := makeTask("t3", "sess-b")
	t1.State = TaskStateRunning
	t2.State = TaskStateRunning
	t3.State = TaskStateQueued
	r.Submit("sess-a", t1)
	r.Submit("sess-a", t2)
	r.Submit("sess-b", t3)
	if r.Count("sess-a") != 2 {
		t.Errorf("count sess-a after 2 active: got %d, want 2", r.Count("sess-a"))
	}
	if r.Count("sess-b") != 1 {
		t.Errorf("count sess-b after 1 active: got %d, want 1", r.Count("sess-b"))
	}
}

func TestRegistry_CloseSession(t *testing.T) {
	r := NewRegistry()
	r.Submit("sess-a", makeTask("t1", "sess-a"))
	r.Submit("sess-a", makeTask("t2", "sess-a"))
	r.Submit("sess-b", makeTask("t3", "sess-b"))

	r.CloseSession("sess-a")

	if len(r.List("sess-a")) != 0 {
		t.Error("sess-a tasks should be cleared")
	}
	if len(r.List("sess-b")) != 1 {
		t.Error("sess-b tasks should remain")
	}
}

func TestRegistry_Concurrent(t *testing.T) {
	r := NewRegistry()
	var wg sync.WaitGroup
	sessionID := "sess-concurrent"

	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			tk := makeTask(fmt.Sprintf("task-%03d", idx), sessionID)
			tk.State = TaskStateRunning
			r.Submit(sessionID, tk)
		}(i)
	}
	wg.Wait()

	// Also concurrent reads and updates
	var readWG sync.WaitGroup
	for i := 0; i < 20; i++ {
		readWG.Add(1)
		go func() {
			defer readWG.Done()
			_ = r.List(sessionID)
			_ = r.Count(sessionID)
		}()
	}
	readWG.Wait()

	if r.Count(sessionID) != 50 {
		t.Errorf("final count: got %d, want 50", r.Count(sessionID))
	}
}

// ─── Scheduler tests ──────────────────────────────────────────────────────────

// fakeExecutor records calls and returns configurable results.
type fakeExecutor struct {
	mu       sync.Mutex
	calls    []fakeCall
	results  map[string]fakeResult // action -> result
	stepFunc func(ctx context.Context, taskID string, action string, args map[string]interface{}, timeoutMS int) (code int, message string, data map[string]interface{}, latencyMS int64)
}

type fakeCall struct {
	TaskID  string
	Action  string
	Args    map[string]interface{}
}

type fakeResult struct {
	Code     int
	Message  string
	Data     map[string]interface{}
	Latency  int64
	Blocking bool // if true, the call blocks until resultCh is closed
}

func newFakeExecutor() *fakeExecutor {
	return &fakeExecutor{
		results: make(map[string]fakeResult),
	}
}

func (f *fakeExecutor) SetResult(action string, code int, message string, data map[string]interface{}) {
	f.mu.Lock()
	f.results[action] = fakeResult{Code: code, Message: message, Data: data}
	f.mu.Unlock()
}

func (f *fakeExecutor) SetBlocking(action string, code int, message string, data map[string]interface{}) {
	f.mu.Lock()
	f.results[action] = fakeResult{Code: code, Message: message, Data: data, Blocking: true}
	f.mu.Unlock()
}

func (f *fakeExecutor) ExecuteStep(ctx context.Context, taskID string, action string, args map[string]interface{}, timeoutMS int) (code int, message string, data map[string]interface{}, latencyMS int64) {
	f.mu.Lock()
	f.calls = append(f.calls, fakeCall{TaskID: taskID, Action: action, Args: args})
	result, ok := f.results[action]
	f.mu.Unlock()

	if f.stepFunc != nil {
		return f.stepFunc(ctx, taskID, action, args, timeoutMS)
	}

	if !ok {
		return 0, "ok", nil, 10
	}
	if result.Blocking {
		select {
		case <-ctx.Done():
			return -1, "cancelled", nil, 10
		default:
		}
		// Block until cancelled or completed.
		select {
		case <-ctx.Done():
			return -1, "cancelled", nil, 10
		}
	}
	return result.Code, result.Message, result.Data, 10
}

func (f *fakeExecutor) callsFor(taskID string) []fakeCall {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []fakeCall
	for _, c := range f.calls {
		if c.TaskID == taskID {
			out = append(out, c)
		}
	}
	return out
}

func (f *fakeExecutor) reset() {
	f.mu.Lock()
	f.calls = nil
	f.mu.Unlock()
}

func makeScheduler(exec *fakeExecutor) (*Scheduler, *[]stateChangeEntry) {
	var changes []stateChangeEntry
	changeHook := func(taskID string, oldState, newState TaskState, snapshot map[string]interface{}) {
		changes = append(changes, stateChangeEntry{
			TaskID:   taskID,
			OldState: oldState,
			NewState: newState,
		})
	}
	sched := NewScheduler(exec, changeHook)
	return sched, &changes
}

type stateChangeEntry struct {
	TaskID   string
	OldState TaskState
	NewState TaskState
}

func makeTaskFor(name string, sessionID string, steps []Step) *Task {
	if len(steps) == 0 {
		steps = []Step{{Action: "ping", Args: map[string]interface{}{}}}
	}
	return &Task{
		ID:        name,
		SessionID: sessionID,
		Steps:     steps,
		State:     TaskStateCreated,
	}
}

func TestScheduler_SubmitValidatesTaskID(t *testing.T) {
	sched, _ := makeScheduler(newFakeExecutor())
	task := &Task{ID: "", SessionID: "sess-a", Steps: []Step{{Action: "ping", Args: map[string]interface{}{}}}}
	err := sched.Submit(context.Background(), task)
	if err == nil {
		t.Error("Submit with empty ID should fail")
	}
}

func TestScheduler_SubmitValidatesSteps(t *testing.T) {
	sched, _ := makeScheduler(newFakeExecutor())
	task := &Task{ID: "t1", SessionID: "sess-a", Steps: nil}
	err := sched.Submit(context.Background(), task)
	if err == nil {
		t.Error("Submit with no steps should fail")
	}
}

func TestScheduler_HappyPath(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetResult("ping", 0, "ok", nil)
	exec.SetResult("tap", 0, "ok", nil)
	sched, changes := makeScheduler(exec)

	task := makeTaskFor("task-happy", "sess-a", []Step{
		{Action: "ping", Args: map[string]interface{}{}},
		{Action: "tap", Args: map[string]interface{}{}},
	})

	err := sched.Submit(context.Background(), task)
	if err != nil {
		t.Fatalf("Submit failed: %v", err)
	}

	// Wait for task to complete.
	time.Sleep(200 * time.Millisecond)

	if task.State != TaskStateSucceeded {
		t.Errorf("final state: got %s, want Succeeded", task.State)
	}
	if task.CurrentStep != 2 {
		t.Errorf("current step: got %d, want 2", task.CurrentStep)
	}

	calls := exec.callsFor("task-happy")
	if len(calls) != 2 {
		t.Errorf("call count: got %d, want 2", len(calls))
	}
	if calls[0].Action != "ping" || calls[1].Action != "tap" {
		t.Error("actions in wrong order")
	}

	// State transitions: Created->Queued->Running->Succeeded
	if len(*changes) < 3 {
		t.Errorf("state changes: got %d, want at least 3", len(*changes))
	}
}

func TestScheduler_AllStepsFailWithFailPolicy(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetResult("failing_action", 1, "step failed", nil)
	sched, _ := makeScheduler(exec)

	task := makeTaskFor("task-fail", "sess-a", []Step{
		{Action: "failing_action", Args: map[string]interface{}{}, OnFailure: StepPolicyFail},
	})

	sched.Submit(context.Background(), task)
	time.Sleep(100 * time.Millisecond)

	if task.State != TaskStateFailed {
		t.Errorf("final state: got %s, want Failed", task.State)
	}
	if task.Error != "step failed" {
		t.Errorf("error: got %q", task.Error)
	}
}

func TestScheduler_SkipPolicyContinues(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetResult("bad_step", 1, "skip me", nil)
	exec.SetResult("good_step", 0, "ok", nil)
	sched, _ := makeScheduler(exec)

	task := makeTaskFor("task-skip", "sess-a", []Step{
		{Action: "bad_step", Args: map[string]interface{}{}, OnFailure: StepPolicySkip},
		{Action: "good_step", Args: map[string]interface{}{}},
	})

	sched.Submit(context.Background(), task)
	time.Sleep(150 * time.Millisecond)

	if task.State != TaskStateSucceeded {
		t.Errorf("final state: got %s, want Succeeded", task.State)
	}
	if task.CurrentStep != 2 {
		t.Errorf("current step: got %d, want 2", task.CurrentStep)
	}
}

func TestScheduler_CompensatePolicy(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetResult("do_work", 0, "ok", nil)
	exec.SetResult("fail_step", 1, "compensate me", nil)
	exec.SetResult("undo_work", 0, "undone", nil)
	sched, _ := makeScheduler(exec)

	task := makeTaskFor("task-comp", "sess-a", []Step{
		{Action: "do_work", Args: map[string]interface{}{}},
		{Action: "fail_step", Args: map[string]interface{}{}, OnFailure: StepPolicyCompensate, CompensateAction: "undo_work", CompensateArgs: map[string]interface{}{}},
	})

	sched.Submit(context.Background(), task)
	time.Sleep(200 * time.Millisecond)

	if task.State != TaskStateFailed {
		t.Errorf("final state: got %s, want Failed", task.State)
	}

	// Compensation should have been triggered.
	calls := exec.callsFor("task-comp")
	if len(calls) < 3 {
		t.Errorf("call count: got %d, want at least 3 (do_work, fail_step, undo_work)", len(calls))
	}
	if len(calls) >= 3 && calls[2].Action != "undo_work" {
		t.Errorf("third action: got %s, want undo_work", calls[2].Action)
	}
}

func TestScheduler_Cancel(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetBlocking("slow_step", 0, "ok", nil)
	sched, _ := makeScheduler(exec)

	task := makeTaskFor("task-cancel", "sess-a", []Step{
		{Action: "slow_step", Args: map[string]interface{}{}},
	})

	sched.Submit(context.Background(), task)
	time.Sleep(50 * time.Millisecond) // let it start

	ok := sched.Cancel("task-cancel")
	if !ok {
		t.Error("Cancel returned false for running task")
	}

	time.Sleep(100 * time.Millisecond)
	if task.State != TaskStateCancelled {
		t.Errorf("final state after cancel: got %s, want Cancelled", task.State)
	}
}

func TestScheduler_CancelNonExistent(t *testing.T) {
	sched, _ := makeScheduler(newFakeExecutor())
	ok := sched.Cancel("nonexistent-task")
	if ok {
		t.Error("Cancel should return false for nonexistent task")
	}
}

func TestScheduler_RetryWithBackoff(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetResult("flaky", 1, "try again", nil)
	sched, _ := makeScheduler(exec)

	task := makeTaskFor("task-retry", "sess-a", []Step{
		{
			Action:     "flaky",
			Args:       map[string]interface{}{},
			OnFailure:  StepPolicyRetry,
			TimeoutMS:  100,
		},
	})
	task.RetryPolicy = RetryPolicy{MaxAttempts: 2, InitialDelayMS: 20, MaxDelayMS: 100}

	sched.Submit(context.Background(), task)
	time.Sleep(300 * time.Millisecond)

	// After 2 failures (1 original + 2 retries), task should be in Failed state.
	if task.State != TaskStateFailed {
		t.Errorf("final state after retries exhausted: got %s, want Failed", task.State)
	}

	calls := exec.callsFor("task-retry")
	// 1 original + up to 2 retries = max 3 calls
	if len(calls) < 2 {
		t.Errorf("retry call count: got %d, want at least 2", len(calls))
	}
}

func TestScheduler_RetrySucceedsOnSecondAttempt(t *testing.T) {
	exec := newFakeExecutor()
	callCount := 0
	exec.stepFunc = func(ctx context.Context, taskID string, action string, args map[string]interface{}, timeoutMS int) (code int, message string, data map[string]interface{}, latencyMS int64) {
		callCount++
		if callCount == 1 {
			return 1, "try again", nil, 10
		}
		return 0, "ok", nil, 10
	}

	sched, _ := makeScheduler(exec)

	tk := makeTaskFor("task-retry-ok", "sess-a", []Step{
		{
			Action:    "flaky_retry",
			Args:      map[string]interface{}{},
			OnFailure: StepPolicyRetry,
		},
	})
	tk.RetryPolicy = RetryPolicy{MaxAttempts: 3, InitialDelayMS: 10, MaxDelayMS: 50}

	sched.Submit(context.Background(), tk)
	time.Sleep(300 * time.Millisecond)

	if tk.State != TaskStateSucceeded {
		t.Errorf("final state: got %s, want Succeeded", tk.State)
	}
	if callCount < 2 {
		t.Errorf("call count: got %d, want at least 2", callCount)
	}
}

func TestScheduler_ClosePreventsNewSubmissions(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetResult("ping", 0, "ok", nil)
	sched, _ := makeScheduler(exec)

	sched.Close()

	task := makeTaskFor("task-after-close", "sess-a", []Step{
		{Action: "ping", Args: map[string]interface{}{}},
	})
	err := sched.Submit(context.Background(), task)
	if err == nil {
		t.Error("Submit after Close should fail")
	}
}

func TestScheduler_RegistryGet(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetResult("ping", 0, "ok", nil)
	sched, _ := makeScheduler(exec)

	task := makeTaskFor("task-reg-get", "sess-a", []Step{
		{Action: "ping", Args: map[string]interface{}{}},
	})
	sched.Submit(context.Background(), task)
	time.Sleep(50 * time.Millisecond)

	got, ok := sched.Registry().Get("sess-a", "task-reg-get")
	if !ok {
		t.Fatal("task not found in registry")
	}
	if got.ID != "task-reg-get" {
		t.Errorf("wrong task: got %s", got.ID)
	}
	if got.SessionID != "sess-a" {
		t.Errorf("wrong session: got %s", got.SessionID)
	}
}

func TestScheduler_CancelByStateChangeHandler(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetResult("ping", 0, "ok", nil)
	sched, changes := makeScheduler(exec)

	task := makeTaskFor("task-state-chg", "sess-a", []Step{
		{Action: "ping", Args: map[string]interface{}{}},
	})

	sched.Submit(context.Background(), task)
	time.Sleep(50 * time.Millisecond)

	if len(*changes) == 0 {
		t.Error("no state changes recorded")
	}

	// Verify the first transition is Queued
	if (*changes)[0].NewState != TaskStateQueued {
		t.Errorf("first state change: got %s, want Queued", (*changes)[0].NewState)
	}
}

func TestScheduler_MultipleTasks(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetResult("ping", 0, "ok", nil)
	sched, _ := makeScheduler(exec)

	for i := 0; i < 3; i++ {
		task := makeTaskFor("task-multi-"+string(rune('a'+i)), "sess-a", []Step{
			{Action: "ping", Args: map[string]interface{}{}},
		})
		sched.Submit(context.Background(), task)
	}

	time.Sleep(200 * time.Millisecond)

	list := sched.Registry().List("sess-a")
	if len(list) != 3 {
		t.Errorf("registry count: got %d, want 3", len(list))
	}
	for _, tk := range list {
		if tk.State != TaskStateSucceeded {
			t.Errorf("task %s state: got %s, want Succeeded", tk.ID, tk.State)
		}
	}
}

// ─── StateSnapshot tests ──────────────────────────────────────────────────────

func TestTask_StateSnapshot(t *testing.T) {
	now := time.Now()
	task := &Task{
		ID:          "snap-test",
		SessionID:   "sess-x",
		Name:        "Snapshot Test",
		State:       TaskStateRunning,
		CurrentStep: 1,
		Steps: []Step{
			{Action: "a"},
			{Action: "b"},
			{Action: "c"},
		},
		CreatedAt: now.Add(-10 * time.Second),
		QueuedAt:  now.Add(-9 * time.Second),
		StartedAt: now.Add(-8 * time.Second),
		Error:     "",
		ErrorCode: 0,
		StepResults: []StepResult{
			{StepIndex: 0, OK: true},
		},
	}

	snap := task.StateSnapshot()

	if snap["task_id"] != "snap-test" {
		t.Errorf("task_id: got %v", snap["task_id"])
	}
	if snap["session_id"] != "sess-x" {
		t.Errorf("session_id: got %v", snap["session_id"])
	}
	if snap["state"] != "Running" {
		t.Errorf("state: got %v", snap["state"])
	}
	if snap["current_step"] != 1 {
		t.Errorf("current_step: got %v", snap["current_step"])
	}
	if snap["total_steps"] != 3 {
		t.Errorf("total_steps: got %v", snap["total_steps"])
	}
	if snap["name"] != "Snapshot Test" {
		t.Errorf("name: got %v", snap["name"])
	}
	if snap["completed_steps"] != 1 {
		t.Errorf("completed_steps: got %v", snap["completed_steps"])
	}
	if _, ok := snap["error"]; ok {
		t.Error("error should not be present when empty")
	}
	if _, ok := snap["error_code"]; ok {
		t.Error("error_code should not be present when zero")
	}
}
