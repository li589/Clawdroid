package task

import (
	"context"
	"errors"
	"fmt"
	"reflect"
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
		{TaskStateRetrying, TaskStateCancelled, true},
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

func TestRegistry_SubmitAndSnapshot(t *testing.T) {
	r := NewRegistry()
	t1 := makeTask("task-1", "sess-a")
	t2 := makeTask("task-2", "sess-a")
	t3 := makeTask("task-3", "sess-b")

	r.Submit("sess-a", t1)
	r.Submit("sess-a", t2)
	r.Submit("sess-b", t3)

	got, ok := r.Snapshot("sess-a", "task-1")
	if !ok {
		t.Fatal("task-1 not found")
	}
	if got.ID != t1.ID {
		t.Error("Snapshot returned wrong task")
	}

	_, ok = r.Snapshot("sess-a", "task-3")
	if ok {
		t.Error("cross-session lookup should fail")
	}

	_, ok = r.Snapshot("sess-x", "task-1")
	if ok {
		t.Error("nonexistent session lookup should fail")
	}
}

func TestRegistry_SubmitOwnsTask(t *testing.T) {
	r := NewRegistry()
	original := makeTask("task-owned", "sess-a")
	original.State = TaskStateRunning
	original.RetryPolicy = RetryPolicy{MaxAttempts: 3, InitialDelayMS: 10, MaxDelayMS: 100}
	original.Steps = []Step{{
		Action: "work",
		Args:   map[string]interface{}{"nested": map[string]interface{}{"value": "original"}},
	}}
	if err := r.Submit("sess-a", original); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	before, ok := r.SnapshotByID(original.ID)
	if !ok {
		t.Fatal("task not found in registry")
	}
	beforeList := r.List("sess-a")

	original.State = TaskStateFailed
	original.RetryPolicy = RetryPolicy{MaxAttempts: 9, InitialDelayMS: 90, MaxDelayMS: 900}
	original.Steps[0].Action = "mutated"
	original.Steps[0].Args["nested"].(map[string]interface{})["value"] = "mutated"

	after, ok := r.SnapshotByID("task-owned")
	if !ok {
		t.Fatal("task disappeared from registry")
	}
	if !reflect.DeepEqual(after, before) {
		t.Errorf("caller mutation changed registry snapshot:\nbefore: %#v\nafter:  %#v", before, after)
	}
	if afterList := r.List("sess-a"); !reflect.DeepEqual(afterList, beforeList) {
		t.Errorf("caller mutation changed registry list:\nbefore: %#v\nafter:  %#v", beforeList, afterList)
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

	got, ok := r.Snapshot("sess-a", "task-1")
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

func TestRegistry_ListReturnsDeepSnapshot(t *testing.T) {
	r := NewRegistry()
	original := makeTask("task-snapshot", "sess-a")
	original.Steps = []Step{{
		Action:         "work",
		Args:           map[string]interface{}{"nested": map[string]interface{}{"value": "original"}},
		CompensateArgs: map[string]interface{}{"nested": map[string]interface{}{"value": "undo"}},
	}}
	original.StepResults = []StepResult{{
		StepIndex: 0,
		OK:        true,
		Data: map[string]interface{}{
			"items": []interface{}{map[string]interface{}{"value": "result"}},
		},
	}}
	if err := r.Submit("sess-a", original); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}

	list := r.List("sess-a")
	if len(list) != 1 {
		t.Fatalf("List returned %d tasks, want 1", len(list))
	}
	list[0].State = TaskStateFailed
	list[0].Steps[0].Args["nested"].(map[string]interface{})["value"] = "mutated"
	list[0].Steps[0].CompensateArgs["nested"].(map[string]interface{})["value"] = "mutated"
	list[0].StepResults[0].Data["items"].([]interface{})[0].(map[string]interface{})["value"] = "mutated"

	got, ok := r.SnapshotByID(original.ID)
	if !ok {
		t.Fatal("task disappeared from registry")
	}
	if got.State != TaskStateCreated {
		t.Fatalf("snapshot mutation changed registry state: got %s", got.State)
	}
	if got.Steps[0].Args["nested"].(map[string]interface{})["value"] != "original" {
		t.Fatal("snapshot mutation changed registry step args")
	}
	if got.Steps[0].CompensateArgs["nested"].(map[string]interface{})["value"] != "undo" {
		t.Fatal("snapshot mutation changed registry compensation args")
	}
	if got.StepResults[0].Data["items"].([]interface{})[0].(map[string]interface{})["value"] != "result" {
		t.Fatal("snapshot mutation changed registry step result data")
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

func TestRegistry_CloseSessionPreservesOwnerHistory(t *testing.T) {
	r := NewRegistry()
	r.Submit("sess-a", makeTask("t1", "sess-a"))
	r.Submit("sess-a", makeTask("t2", "sess-a"))
	r.Submit("sess-b", makeTask("t3", "sess-b"))

	r.CloseSession("sess-a")

	if len(r.List("sess-a")) != 2 {
		t.Error("closing a connection must not clear stable owner history")
	}
	if len(r.List("sess-b")) != 1 {
		t.Error("sess-b tasks should remain")
	}
}

func TestRegistry_CloseSessionKeepsActiveAndTerminalTasks(t *testing.T) {
	r := NewRegistry()
	activeTask := makeTask("t-active", "sess-a")
	activeTask.State = TaskStateRunning
	r.Submit("sess-a", activeTask)

	terminalTask := makeTask("t-terminal", "sess-a")
	terminalTask.State = TaskStateSucceeded
	terminalTask.EndedAt = time.Now()
	r.Submit("sess-a", terminalTask)

	r.CloseSession("sess-a")

	got, ok := r.SnapshotByID("t-active")
	if !ok || got.State != TaskStateRunning {
		t.Fatalf("active task after CloseSession: got %#v, present=%v", got, ok)
	}
	if got, ok := r.SnapshotByID("t-terminal"); !ok || got.State != TaskStateSucceeded {
		t.Fatalf("terminal history after CloseSession: got %#v, present=%v", got, ok)
	}
	if len(r.List("sess-a")) != 2 {
		t.Error("closing a connection must not detach stable owner history")
	}
}

func TestRegistry_ListPrunesExpiredTerminalHistoryButKeepsActiveTasks(t *testing.T) {
	r := NewRegistry()
	now := time.Now()

	expired := makeTask("expired", "owner-a")
	expired.State = TaskStateSucceeded
	expired.EndedAt = now.Add(-31 * time.Minute)
	recent := makeTask("recent", "owner-a")
	recent.State = TaskStateFailed
	recent.EndedAt = now.Add(-29 * time.Minute)
	active := makeTask("active", "owner-a")
	active.State = TaskStateRunning
	active.EndedAt = now.Add(-2 * time.Hour)

	for _, tk := range []*Task{expired, recent, active} {
		if err := r.Submit("owner-a", tk); err != nil {
			t.Fatalf("Submit(%s) failed: %v", tk.ID, err)
		}
	}

	list := r.List("owner-a")
	if len(list) != 2 {
		t.Fatalf("List returned %d tasks, want 2 non-expired tasks: %#v", len(list), list)
	}
	seen := make(map[string]bool, len(list))
	for _, tk := range list {
		seen[tk.ID] = true
	}
	if seen["expired"] || !seen["recent"] || !seen["active"] {
		t.Fatalf("unexpected pruned history: %#v", seen)
	}
}

func TestRegistry_GetPrunesExpiredTerminalHistory(t *testing.T) {
	r := NewRegistry()
	expired := makeTask("expired-get", "owner-a")
	expired.State = TaskStateCancelled
	expired.EndedAt = time.Now().Add(-31 * time.Minute)
	if err := r.Submit("owner-a", expired); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}

	if _, ok := r.SnapshotByID(expired.ID); ok {
		t.Fatal("SnapshotByID retained an expired terminal task")
	}
}

func TestRegistry_SubmitPrunesExpiredTerminalHistory(t *testing.T) {
	r := NewRegistry()
	expired := makeTask("expired-submit", "owner-a")
	expired.State = TaskStateSucceeded
	if err := r.Submit("owner-a", expired); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	if err := r.UpdateByID(expired.ID, func(tk *Task) error {
		tk.State = TaskStateSucceeded
		tk.EndedAt = time.Now().Add(-31 * time.Minute)
		return nil
	}); err != nil {
		t.Fatalf("UpdateByID failed: %v", err)
	}

	active := makeTask("new-active", "owner-a")
	active.State = TaskStateRunning
	if err := r.Submit("owner-a", active); err != nil {
		t.Fatalf("Submit active task failed: %v", err)
	}
	if _, ok := r.Snapshot("owner-a", expired.ID); ok {
		t.Fatal("Submit did not prune expired terminal history")
	}
}

func TestRegistry_PrunesTerminalHistoryToCapacityWithoutPruningActiveTasks(t *testing.T) {
	r := NewRegistry()
	now := time.Now()
	for i := 0; i < 205; i++ {
		tk := makeTask(fmt.Sprintf("terminal-%03d", i), "owner-a")
		tk.State = TaskStateSucceeded
		tk.EndedAt = now.Add(time.Duration(i) * time.Second)
		if err := r.Submit("owner-a", tk); err != nil {
			t.Fatalf("Submit(%s) failed: %v", tk.ID, err)
		}
	}
	active := makeTask("active-over-capacity", "owner-a")
	active.State = TaskStateRunning
	active.EndedAt = now.Add(-2 * time.Hour)
	if err := r.Submit("owner-a", active); err != nil {
		t.Fatalf("Submit active task failed: %v", err)
	}

	list := r.List("owner-a")
	if len(list) != 201 {
		t.Fatalf("List returned %d tasks, want 200 terminal tasks plus 1 active task", len(list))
	}
	seen := make(map[string]bool, len(list))
	for _, tk := range list {
		seen[tk.ID] = true
	}
	for i := 0; i < 5; i++ {
		if seen[fmt.Sprintf("terminal-%03d", i)] {
			t.Errorf("oldest terminal task %03d was not pruned", i)
		}
	}
	if !seen["terminal-204"] || !seen["active-over-capacity"] {
		t.Fatalf("newest terminal or active task was pruned: %#v", seen)
	}
}

func TestRegistry_Concurrent(t *testing.T) {
	r := NewRegistry()
	const writerCount = 50
	const readerCount = 20
	sessionID := "sess-concurrent"
	start := make(chan struct{})
	writersDone := make(chan struct{})

	var writerWG sync.WaitGroup
	for i := 0; i < writerCount; i++ {
		writerWG.Add(1)
		go func(idx int) {
			defer writerWG.Done()
			<-start
			tk := makeTask(fmt.Sprintf("task-%03d", idx), sessionID)
			tk.State = TaskStateRunning
			if err := r.Submit(sessionID, tk); err != nil {
				t.Errorf("Submit failed: %v", err)
				return
			}
			if err := r.Update(sessionID, tk.ID, func(tk *Task) error {
				tk.Name = fmt.Sprintf("task-%03d", idx)
				return nil
			}); err != nil {
				t.Errorf("Update failed: %v", err)
			}
		}(i)
	}
	go func() {
		writerWG.Wait()
		close(writersDone)
	}()

	var readerWG sync.WaitGroup
	for i := 0; i < readerCount; i++ {
		readerWG.Add(1)
		go func() {
			defer readerWG.Done()
			<-start
			for {
				_ = r.List(sessionID)
				_ = r.Count(sessionID)
				select {
				case <-writersDone:
					return
				default:
				}
			}
		}()
	}

	close(start)
	readerWG.Wait()

	if r.Count(sessionID) != writerCount {
		t.Errorf("final count: got %d, want %d", r.Count(sessionID), writerCount)
	}
}

// ─── Scheduler tests ──────────────────────────────────────────────────────────

// fakeExecutor records calls and returns configurable results.
type fakeExecutor struct {
	mu       sync.Mutex
	calls    []fakeCall
	results  map[string]fakeResult // action -> result
	stepFunc func(ctx context.Context, sessionID, taskID string, action string, args map[string]interface{}, timeoutMS int) (code int, message string, data map[string]interface{}, latencyMS int64)
	started  chan fakeCall
}

type fakeCall struct {
	TaskID string
	Action string
	Args   map[string]interface{}
}

type fakeResult struct {
	Code     int
	Message  string
	Data     map[string]interface{}
	Latency  int64
	Blocking bool
}

func newFakeExecutor() *fakeExecutor {
	return &fakeExecutor{
		results: make(map[string]fakeResult),
		started: make(chan fakeCall, 256),
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

func (f *fakeExecutor) SetStepFunc(fn func(context.Context, string, string, string, map[string]interface{}, int) (int, string, map[string]interface{}, int64)) {
	f.mu.Lock()
	f.stepFunc = fn
	f.mu.Unlock()
}

func (f *fakeExecutor) ExecuteStep(ctx context.Context, sessionID, taskID string, action string, args map[string]interface{}, timeoutMS int) (code int, message string, data map[string]interface{}, latencyMS int64) {
	f.mu.Lock()
	call := fakeCall{TaskID: taskID, Action: action, Args: args}
	f.calls = append(f.calls, call)
	result, ok := f.results[action]
	stepFunc := f.stepFunc
	f.mu.Unlock()

	f.started <- call
	if stepFunc != nil {
		return stepFunc(ctx, sessionID, taskID, action, args, timeoutMS)
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

func waitForExecutorCall(t *testing.T, exec *fakeExecutor, taskID, action string) {
	t.Helper()
	timer := time.NewTimer(5 * time.Second)
	defer timer.Stop()
	for {
		select {
		case call := <-exec.started:
			if call.TaskID == taskID && call.Action == action {
				return
			}
		case <-timer.C:
			t.Fatalf("timed out waiting for task %s action %s", taskID, action)
		}
	}
}

type stateChangeEntry struct {
	TaskID   string
	OldState TaskState
	NewState TaskState
	Snapshot map[string]interface{}
}

type stateChangeLog struct {
	mu      sync.Mutex
	entries []stateChangeEntry
	notify  chan struct{}
}

func newStateChangeLog() *stateChangeLog {
	return &stateChangeLog{notify: make(chan struct{}, 256)}
}

func (l *stateChangeLog) record(entry stateChangeEntry) {
	l.mu.Lock()
	l.entries = append(l.entries, entry)
	l.mu.Unlock()
	select {
	case l.notify <- struct{}{}:
	default:
	}
}

func (l *stateChangeLog) snapshot() []stateChangeEntry {
	l.mu.Lock()
	defer l.mu.Unlock()
	return append([]stateChangeEntry(nil), l.entries...)
}

func (l *stateChangeLog) waitForState(taskID string, state TaskState) (stateChangeEntry, bool) {
	timer := time.NewTimer(5 * time.Second)
	defer timer.Stop()
	for {
		for _, entry := range l.snapshot() {
			if entry.TaskID == taskID && entry.NewState == state {
				return entry, true
			}
		}
		select {
		case <-l.notify:
		case <-timer.C:
			return stateChangeEntry{}, false
		}
	}
}

func makeScheduler(exec *fakeExecutor) (*Scheduler, *stateChangeLog) {
	return makeSchedulerWithOptions(exec, SchedulerOptions{})
}

func makeSchedulerWithOptions(exec *fakeExecutor, opts SchedulerOptions) (*Scheduler, *stateChangeLog) {
	changes := newStateChangeLog()
	changeHook := func(taskID string, oldState, newState TaskState, snapshot map[string]interface{}) {
		changes.record(stateChangeEntry{
			TaskID:   taskID,
			OldState: oldState,
			NewState: newState,
			Snapshot: snapshot,
		})
	}
	return NewSchedulerWithOptions(exec, changeHook, opts), changes
}

func waitForTaskState(t *testing.T, sched *Scheduler, changes *stateChangeLog, taskID string, want TaskState) Task {
	t.Helper()
	return waitForTaskStateWithin(t, sched, changes, taskID, want, 5*time.Second)
}

func waitForTaskStateWithin(t *testing.T, sched *Scheduler, changes *stateChangeLog, taskID string, want TaskState, timeout time.Duration) Task {
	t.Helper()
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	for {
		if current, ok := sched.Registry().SnapshotByID(taskID); ok && current.State == want {
			return current
		}
		select {
		case <-changes.notify:
		case <-timer.C:
			current, _ := sched.Registry().SnapshotByID(taskID)
			t.Fatalf("timed out waiting for task %s state %s; last snapshot: %#v", taskID, want, current)
			return Task{}
		}
	}
}

func waitForSchedulerInflight(t *testing.T, sched *Scheduler, want int) {
	t.Helper()
	ticker := time.NewTicker(time.Millisecond)
	defer ticker.Stop()
	timer := time.NewTimer(5 * time.Second)
	defer timer.Stop()
	for {
		if sched.InflightCount() == want {
			return
		}
		select {
		case <-ticker.C:
		case <-timer.C:
			t.Fatalf("timed out waiting for inflight=%d; got %d", want, sched.InflightCount())
		}
	}
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

func TestScheduler_SubmitOwnsRegistryTask(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetStepFunc(func(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
		<-ctx.Done()
		return -1, "cancelled", nil, 10
	})
	sched := NewSchedulerWithOptions(exec, nil, SchedulerOptions{MaxConcurrent: 1, MaxInflight: 2})
	defer sched.Close()

	blocker := makeTaskFor("task-owner-blocker", "sess-blocker", nil)
	if err := sched.Submit(context.Background(), blocker); err != nil {
		t.Fatalf("Submit blocker failed: %v", err)
	}
	waitForExecutorCall(t, exec, blocker.ID, blocker.Steps[0].Action)

	caller := makeTaskFor("task-owned", "sess-a", []Step{{
		Action: "work",
		Args:   map[string]interface{}{"nested": map[string]interface{}{"value": "original"}},
	}})
	if err := sched.Submit(context.Background(), caller); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	if caller.State != TaskStateCreated {
		t.Errorf("Submit mutated caller state: got %s, want %s", caller.State, TaskStateCreated)
	}
	if caller.RetryPolicy != (RetryPolicy{}) {
		t.Errorf("Submit mutated caller retry policy: got %#v, want zero value", caller.RetryPolicy)
	}

	before, ok := sched.Registry().SnapshotByID(caller.ID)
	if !ok {
		t.Fatal("task not found in registry")
	}
	if before.State != TaskStateQueued {
		t.Errorf("registry state: got %s, want %s", before.State, TaskStateQueued)
	}
	wantRetry := RetryPolicy{MaxAttempts: 2, InitialDelayMS: 500, MaxDelayMS: 5000}
	if before.RetryPolicy != wantRetry {
		t.Errorf("registry retry policy: got %#v, want %#v", before.RetryPolicy, wantRetry)
	}
	beforeList := sched.Registry().List(caller.SessionID)

	caller.State = TaskStateFailed
	caller.RetryPolicy = RetryPolicy{MaxAttempts: 9, InitialDelayMS: 90, MaxDelayMS: 900}
	caller.Steps[0].Action = "mutated"
	caller.Steps[0].Args["nested"].(map[string]interface{})["value"] = "mutated"

	after, ok := sched.Registry().SnapshotByID(caller.ID)
	if !ok {
		t.Fatal("task disappeared from registry")
	}
	if !reflect.DeepEqual(after, before) {
		t.Errorf("caller mutation changed registry snapshot:\nbefore: %#v\nafter:  %#v", before, after)
	}
	if afterList := sched.Registry().List(caller.SessionID); !reflect.DeepEqual(afterList, beforeList) {
		t.Errorf("caller mutation changed registry list:\nbefore: %#v\nafter:  %#v", beforeList, afterList)
	}
}

func TestScheduler_SubmitCallerTaskAccessConcurrentWithExecution(t *testing.T) {
	const stepCount = 200
	steps := make([]Step, stepCount)
	for i := range steps {
		steps[i] = Step{Action: "ping", Args: map[string]interface{}{"value": i}}
	}

	release := make(chan struct{})
	var releaseOnce sync.Once
	releaseExecution := func() {
		releaseOnce.Do(func() { close(release) })
	}
	defer releaseExecution()

	exec := newFakeExecutor()
	exec.SetStepFunc(func(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
		<-release
		return 0, "ok", nil, 10
	})
	sched, changes := makeScheduler(exec)
	defer sched.Close()

	caller := makeTaskFor("task-concurrent-caller", "sess-a", steps)
	if err := sched.Submit(context.Background(), caller); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	waitForExecutorCall(t, exec, caller.ID, caller.Steps[0].Action)

	callerDone := make(chan struct{})
	callerStarted := make(chan struct{})
	var callerWG sync.WaitGroup
	callerWG.Add(1)
	go func() {
		defer callerWG.Done()
		caller.State = TaskStateFailed
		caller.RetryPolicy = RetryPolicy{MaxAttempts: 9, InitialDelayMS: 90, MaxDelayMS: 900}
		caller.Steps[0].Action = "mutated"
		caller.Steps[0].Args["value"] = "mutated"
		close(callerStarted)
		for {
			select {
			case <-callerDone:
				return
			default:
				_ = caller.State
				_ = caller.RetryPolicy
				_ = caller.Steps[0].Action
				_ = caller.Steps[0].Args["value"]
				caller.State = TaskStateRunning
				caller.RetryPolicy = RetryPolicy{MaxAttempts: 7, InitialDelayMS: 70, MaxDelayMS: 700}
				caller.Steps[0].Action = "caller-work"
				caller.Steps[0].Args["value"] = "caller-work"
			}
		}
	}()
	defer func() {
		close(callerDone)
		callerWG.Wait()
	}()

	<-callerStarted
	releaseExecution()
	waitForTaskState(t, sched, changes, caller.ID, TaskStateSucceeded)
}

func TestScheduler_QueuedEventIncludesQueuedAt(t *testing.T) {
	exec := newFakeExecutor()
	sched, changes := makeScheduler(exec)
	defer sched.Close()

	task := makeTaskFor("task-queued-event", "sess-a", nil)
	if err := sched.Submit(context.Background(), task); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}

	queued, ok := changes.waitForState(task.ID, TaskStateQueued)
	if !ok {
		t.Fatal("timed out waiting for Queued transition")
	}
	queuedAt, ok := queued.Snapshot["queued_at"].(int64)
	if !ok || queuedAt == 0 {
		t.Fatalf("Queued event did not include queued_at: %#v", queued.Snapshot)
	}
	waitForTaskState(t, sched, changes, task.ID, TaskStateSucceeded)
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

	finalTask := waitForTaskState(t, sched, changes, task.ID, TaskStateSucceeded)
	if finalTask.CurrentStep != 2 {
		t.Errorf("current step: got %d, want 2", finalTask.CurrentStep)
	}

	calls := exec.callsFor("task-happy")
	if len(calls) != 2 {
		t.Errorf("call count: got %d, want 2", len(calls))
	}
	if calls[0].Action != "ping" || calls[1].Action != "tap" {
		t.Error("actions in wrong order")
	}

	// State transitions: Created->Queued->Running->Succeeded
	if got := len(changes.snapshot()); got < 3 {
		t.Errorf("state changes: got %d, want at least 3", got)
	}
}

func TestScheduler_AllStepsFailWithFailPolicy(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetResult("failing_action", 1, "step failed", nil)
	sched, changes := makeScheduler(exec)

	task := makeTaskFor("task-fail", "sess-a", []Step{
		{Action: "failing_action", Args: map[string]interface{}{}, OnFailure: StepPolicyFail},
	})

	if err := sched.Submit(context.Background(), task); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	finalTask := waitForTaskState(t, sched, changes, task.ID, TaskStateFailed)
	if finalTask.Error != "step failed" {
		t.Errorf("error: got %q", finalTask.Error)
	}
}

func TestScheduler_SkipPolicyContinues(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetResult("bad_step", 1, "skip me", nil)
	exec.SetResult("good_step", 0, "ok", nil)
	sched, changes := makeScheduler(exec)

	task := makeTaskFor("task-skip", "sess-a", []Step{
		{Action: "bad_step", Args: map[string]interface{}{}, OnFailure: StepPolicySkip},
		{Action: "good_step", Args: map[string]interface{}{}},
	})

	if err := sched.Submit(context.Background(), task); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	finalTask := waitForTaskState(t, sched, changes, task.ID, TaskStateSucceeded)
	if finalTask.CurrentStep != 2 {
		t.Errorf("current step: got %d, want 2", finalTask.CurrentStep)
	}
}

func TestScheduler_CompensatePolicy(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetResult("do_work", 0, "ok", nil)
	exec.SetResult("fail_step", 1, "compensate me", nil)
	exec.SetResult("undo_work", 0, "undone", nil)
	sched, changes := makeScheduler(exec)

	task := makeTaskFor("task-comp", "sess-a", []Step{
		{Action: "do_work", Args: map[string]interface{}{}},
		{Action: "fail_step", Args: map[string]interface{}{}, OnFailure: StepPolicyCompensate, CompensateAction: "undo_work", CompensateArgs: map[string]interface{}{}},
	})

	if err := sched.Submit(context.Background(), task); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	waitForTaskState(t, sched, changes, task.ID, TaskStateFailed)

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
	sched, changes := makeScheduler(exec)

	task := makeTaskFor("task-cancel", "sess-a", []Step{
		{Action: "slow_step", Args: map[string]interface{}{}},
	})

	if err := sched.Submit(context.Background(), task); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	waitForExecutorCall(t, exec, task.ID, "slow_step")

	if !sched.Cancel(task.ID) {
		t.Error("Cancel returned false for running task")
	}
	waitForTaskState(t, sched, changes, task.ID, TaskStateCancelled)
}

func TestScheduler_CancelQueuedTask(t *testing.T) {
	exec := newFakeExecutor()
	blockerStarted := make(chan struct{})
	releaseBlocker := make(chan struct{})
	var releaseOnce sync.Once
	exec.SetStepFunc(func(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
		if taskID == "blocker" {
			close(blockerStarted)
			<-releaseBlocker
		}
		return 0, "ok", nil, 1
	})
	sched, changes := makeSchedulerWithOptions(exec, SchedulerOptions{MaxConcurrent: 1, MaxInflight: 2})
	defer sched.Close()
	defer releaseOnce.Do(func() { close(releaseBlocker) })

	blocker := makeTaskFor("blocker", "owner-a", nil)
	if err := sched.Submit(context.Background(), blocker); err != nil {
		t.Fatalf("Submit blocker failed: %v", err)
	}
	select {
	case <-blockerStarted:
	case <-time.After(5 * time.Second):
		t.Fatal("blocker did not start")
	}

	queued := makeTaskFor("queued-cancel", "owner-a", nil)
	if err := sched.Submit(context.Background(), queued); err != nil {
		t.Fatalf("Submit queued task failed: %v", err)
	}
	if current, ok := sched.Registry().SnapshotByID(queued.ID); !ok || current.State != TaskStateQueued {
		t.Fatalf("second task state: got %#v, want Queued", current)
	}
	if !sched.Cancel(queued.ID) {
		t.Fatal("Cancel returned false for queued task")
	}
	if current, ok := sched.Registry().SnapshotByID(queued.ID); !ok || current.State != TaskStateCancelled {
		t.Fatalf("cancelled queued task state: got %#v, want Cancelled", current)
	}

	releaseOnce.Do(func() { close(releaseBlocker) })
	waitForTaskState(t, sched, changes, blocker.ID, TaskStateSucceeded)
	waitForSchedulerInflight(t, sched, 0)
	if calls := exec.callsFor(queued.ID); len(calls) != 0 {
		t.Fatalf("cancelled queued task was executed: %#v", calls)
	}
}

func TestScheduler_CancelRunningTaskUpdatesStateBeforeExecutorReturns(t *testing.T) {
	exec := newFakeExecutor()
	started := make(chan struct{})
	observedCancel := make(chan struct{})
	release := make(chan struct{})
	var releaseOnce sync.Once
	exec.SetStepFunc(func(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
		close(started)
		<-ctx.Done()
		close(observedCancel)
		<-release
		return -1, "cancelled", nil, 1
	})
	sched, _ := makeScheduler(exec)
	defer sched.Close()
	defer releaseOnce.Do(func() { close(release) })

	tk := makeTaskFor("running-cancel", "owner-a", nil)
	if err := sched.Submit(context.Background(), tk); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	select {
	case <-started:
	case <-time.After(5 * time.Second):
		t.Fatal("executor did not start")
	}

	if !sched.Cancel(tk.ID) {
		t.Fatal("Cancel returned false for running task")
	}
	select {
	case <-observedCancel:
	case <-time.After(time.Second):
		t.Fatal("executor did not observe context cancellation")
	}
	if current, ok := sched.Registry().SnapshotByID(tk.ID); !ok || current.State != TaskStateCancelled {
		t.Fatalf("task state after Cancel: got %#v, want Cancelled before executor returns", current)
	}

	releaseOnce.Do(func() { close(release) })
	waitForSchedulerInflight(t, sched, 0)
}

func TestScheduler_CancelWaitingSignalAndRetrying(t *testing.T) {
	for _, state := range []TaskState{TaskStateWaitingSignal, TaskStateRetrying} {
		t.Run(string(state), func(t *testing.T) {
			exec := newFakeExecutor()
			started := make(chan struct{})
			observedCancel := make(chan struct{})
			release := make(chan struct{})
			var releaseOnce sync.Once
			exec.SetStepFunc(func(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
				close(started)
				<-ctx.Done()
				close(observedCancel)
				<-release
				return -1, "cancelled", nil, 1
			})
			sched, _ := makeScheduler(exec)
			defer sched.Close()
			defer releaseOnce.Do(func() { close(release) })

			tk := makeTaskFor("cancel-"+string(state), "owner-a", nil)
			if err := sched.Submit(context.Background(), tk); err != nil {
				t.Fatalf("Submit failed: %v", err)
			}
			select {
			case <-started:
			case <-time.After(5 * time.Second):
				t.Fatal("executor did not start")
			}
			if !sched.Registry().SetState(tk.ID, state) {
				t.Fatalf("failed to set up task state %s", state)
			}
			if !sched.Cancel(tk.ID) {
				t.Fatalf("Cancel returned false for %s task", state)
			}
			select {
			case <-observedCancel:
			case <-time.After(time.Second):
				t.Fatal("executor did not observe context cancellation")
			}
			if current, ok := sched.Registry().SnapshotByID(tk.ID); !ok || current.State != TaskStateCancelled {
				t.Fatalf("task state after Cancel: got %#v, want Cancelled", current)
			}
			releaseOnce.Do(func() { close(release) })
			waitForSchedulerInflight(t, sched, 0)
		})
	}
}

func TestScheduler_CancelCompensatingIsRejected(t *testing.T) {
	exec := newFakeExecutor()
	started := make(chan struct{})
	observedCancel := make(chan struct{})
	release := make(chan struct{})
	var releaseOnce sync.Once
	exec.SetStepFunc(func(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
		close(started)
		<-ctx.Done()
		close(observedCancel)
		<-release
		return -1, "cancelled", nil, 1
	})
	sched, _ := makeScheduler(exec)
	defer sched.Close()
	defer releaseOnce.Do(func() { close(release) })

	tk := makeTaskFor("compensating-cancel", "owner-a", nil)
	if err := sched.Submit(context.Background(), tk); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	select {
	case <-started:
	case <-time.After(5 * time.Second):
		t.Fatal("executor did not start")
	}
	if !sched.Registry().SetState(tk.ID, TaskStateCompensating) {
		t.Fatal("failed to set up Compensating state")
	}
	if sched.Cancel(tk.ID) {
		t.Fatal("Cancel accepted an illegal Compensating -> Cancelled transition")
	}
	if current, ok := sched.Registry().SnapshotByID(tk.ID); !ok || current.State != TaskStateCompensating {
		t.Fatalf("task state after rejected Cancel: got %#v, want Compensating", current)
	}
	select {
	case <-observedCancel:
		t.Fatal("rejected Cancel cancelled the executor context")
	default:
	}
	releaseOnce.Do(func() { close(release) })
}

func TestScheduler_CancelDuringRetryBackoff(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetResult("flaky", 1, "retry me", nil)
	sched, changes := makeScheduler(exec)
	defer sched.Close()

	tk := makeTaskFor("retry-cancel", "owner-a", []Step{{
		Action:    "flaky",
		Args:      map[string]interface{}{},
		OnFailure: StepPolicyRetry,
	}})
	tk.RetryPolicy = RetryPolicy{MaxAttempts: 1, InitialDelayMS: 5000, MaxDelayMS: 5000}
	if err := sched.Submit(context.Background(), tk); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	waitForTaskStateWithin(t, sched, changes, tk.ID, TaskStateRetrying, time.Second)
	if !sched.Cancel(tk.ID) {
		t.Fatal("Cancel returned false during retry backoff")
	}
	if current, ok := sched.Registry().SnapshotByID(tk.ID); !ok || current.State != TaskStateCancelled {
		t.Fatalf("task state after Cancel: got %#v, want Cancelled", current)
	}
	waitForSchedulerInflight(t, sched, 0)
	if calls := exec.callsFor(tk.ID); len(calls) != 1 {
		t.Fatalf("retry executed after cancellation: got %d calls, want 1", len(calls))
	}
}

func TestScheduler_PerStepTimeoutReachesExecutorContext(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetStepFunc(func(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
		<-ctx.Done()
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			return 4, "step timed out", nil, 1
		}
		return -1, "cancelled", nil, 1
	})
	sched, changes := makeScheduler(exec)
	defer sched.Close()

	tk := makeTaskFor("step-timeout", "owner-a", []Step{{
		Action:    "wait-for-context",
		Args:      map[string]interface{}{},
		TimeoutMS: 40,
		OnFailure: StepPolicyFail,
	}})
	if err := sched.Submit(context.Background(), tk); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	finalTask := waitForTaskStateWithin(t, sched, changes, tk.ID, TaskStateFailed, time.Second)
	if finalTask.ErrorCode != 4 || finalTask.Error != "step timed out" {
		t.Fatalf("timeout result: code=%d error=%q, want code=4 and step timeout", finalTask.ErrorCode, finalTask.Error)
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
	sched, changes := makeScheduler(exec)

	task := makeTaskFor("task-retry", "sess-a", []Step{
		{
			Action:    "flaky",
			Args:      map[string]interface{}{},
			OnFailure: StepPolicyRetry,
			TimeoutMS: 100,
		},
	})
	task.RetryPolicy = RetryPolicy{MaxAttempts: 2, InitialDelayMS: 100, MaxDelayMS: 100}

	if err := sched.Submit(context.Background(), task); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	// After 2 failures (1 original + 2 retries), task should be in Failed state.
	waitForTaskState(t, sched, changes, task.ID, TaskStateFailed)

	calls := exec.callsFor("task-retry")
	// 1 original + up to 2 retries = max 3 calls
	if len(calls) < 2 {
		t.Errorf("retry call count: got %d, want at least 2", len(calls))
	}
}

func TestScheduler_RetrySucceedsOnSecondAttempt(t *testing.T) {
	exec := newFakeExecutor()
	callCount := 0
	exec.SetStepFunc(func(ctx context.Context, sessionID, taskID string, action string, args map[string]interface{}, timeoutMS int) (code int, message string, data map[string]interface{}, latencyMS int64) {
		callCount++
		if callCount == 1 {
			return 1, "try again", nil, 10
		}
		return 0, "ok", nil, 10
	})

	sched, changes := makeScheduler(exec)

	tk := makeTaskFor("task-retry-ok", "sess-a", []Step{
		{
			Action:    "flaky_retry",
			Args:      map[string]interface{}{},
			OnFailure: StepPolicyRetry,
		},
	})
	tk.RetryPolicy = RetryPolicy{MaxAttempts: 3, InitialDelayMS: 100, MaxDelayMS: 100}

	if err := sched.Submit(context.Background(), tk); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	waitForTaskState(t, sched, changes, tk.ID, TaskStateSucceeded)
	if callCount != 2 {
		t.Errorf("call count: got %d, want 2", callCount)
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

func TestScheduler_WaitForIdle(t *testing.T) {
	t.Run("already idle", func(t *testing.T) {
		sched, _ := makeScheduler(newFakeExecutor())
		defer sched.Close()
		if !sched.WaitForIdle(time.Second) {
			t.Fatal("WaitForIdle returned false for an idle scheduler")
		}
	})

	t.Run("times out while running", func(t *testing.T) {
		exec := newFakeExecutor()
		exec.SetStepFunc(func(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
			<-ctx.Done()
			return -1, "cancelled", nil, 1
		})
		sched, _ := makeScheduler(exec)
		defer sched.Close()

		tk := makeTaskFor("wait-timeout", "owner-a", nil)
		if err := sched.Submit(context.Background(), tk); err != nil {
			t.Fatalf("Submit failed: %v", err)
		}
		waitForExecutorCall(t, exec, tk.ID, tk.Steps[0].Action)
		if sched.WaitForIdle(20 * time.Millisecond) {
			t.Fatal("WaitForIdle returned true while the executor was still running")
		}

		sched.Close()
		if !sched.WaitForIdle(time.Second) {
			t.Fatal("WaitForIdle returned false after Close cancelled the executor")
		}
	})

	t.Run("waits for completion", func(t *testing.T) {
		release := make(chan struct{})
		var releaseOnce sync.Once
		exec := newFakeExecutor()
		exec.SetStepFunc(func(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
			<-release
			return 0, "ok", nil, 1
		})
		sched, _ := makeScheduler(exec)
		defer func() {
			releaseOnce.Do(func() { close(release) })
			sched.Close()
		}()

		tk := makeTaskFor("wait-completion", "owner-a", nil)
		if err := sched.Submit(context.Background(), tk); err != nil {
			t.Fatalf("Submit failed: %v", err)
		}
		waitForExecutorCall(t, exec, tk.ID, tk.Steps[0].Action)

		result := make(chan bool, 1)
		go func() {
			result <- sched.WaitForIdle(time.Second)
		}()
		select {
		case <-result:
			t.Fatal("WaitForIdle returned before the executor completed")
		case <-time.After(20 * time.Millisecond):
		}

		releaseOnce.Do(func() { close(release) })
		select {
		case idle := <-result:
			if !idle {
				t.Fatal("WaitForIdle returned false after the executor completed")
			}
		case <-time.After(time.Second):
			t.Fatal("WaitForIdle did not observe executor completion")
		}
		waitForSchedulerInflight(t, sched, 0)
	})
}

func TestScheduler_CloseCancelsRunningAndQueuedTasks(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetStepFunc(func(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
		<-ctx.Done()
		return -1, "cancelled", nil, 1
	})
	sched, changes := makeSchedulerWithOptions(exec, SchedulerOptions{MaxConcurrent: 1, MaxInflight: 2})
	defer sched.Close()

	blocker := makeTaskFor("close-blocker", "owner-a", nil)
	if err := sched.Submit(context.Background(), blocker); err != nil {
		t.Fatalf("Submit blocker failed: %v", err)
	}
	waitForExecutorCall(t, exec, blocker.ID, blocker.Steps[0].Action)

	queued := makeTaskFor("close-queued", "owner-a", nil)
	if err := sched.Submit(context.Background(), queued); err != nil {
		t.Fatalf("Submit queued task failed: %v", err)
	}
	if current, ok := sched.Registry().SnapshotByID(queued.ID); !ok || current.State != TaskStateQueued {
		t.Fatalf("queued task state before Close: got %#v, present=%v; want Queued", current, ok)
	}

	var closeWG sync.WaitGroup
	for i := 0; i < 8; i++ {
		closeWG.Add(1)
		go func() {
			defer closeWG.Done()
			sched.Close()
		}()
	}
	closeDone := make(chan struct{})
	go func() {
		closeWG.Wait()
		close(closeDone)
	}()
	select {
	case <-closeDone:
	case <-time.After(time.Second):
		t.Fatal("concurrent Close calls deadlocked")
	}
	sched.Close()

	waitForTaskState(t, sched, changes, blocker.ID, TaskStateCancelled)
	waitForTaskState(t, sched, changes, queued.ID, TaskStateCancelled)
	waitForSchedulerInflight(t, sched, 0)
	if calls := exec.callsFor(queued.ID); len(calls) != 0 {
		t.Fatalf("queued task executed during Close: %#v", calls)
	}
}

func TestScheduler_CloseCancelsCompensatingTask(t *testing.T) {
	compensationStarted := make(chan struct{})
	compensationCancelled := make(chan struct{})
	exec := newFakeExecutor()
	exec.SetStepFunc(func(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
		switch action {
		case "fail":
			return 1, "trigger compensation", nil, 1
		case "undo":
			close(compensationStarted)
			<-ctx.Done()
			close(compensationCancelled)
			return -1, "compensation cancelled", nil, 1
		default:
			return 0, "ok", nil, 1
		}
	})
	sched, changes := makeScheduler(exec)
	defer sched.Close()

	tk := makeTaskFor("close-compensating", "owner-a", []Step{
		{Action: "work"},
		{Action: "fail", OnFailure: StepPolicyCompensate, CompensateAction: "undo"},
	})
	if err := sched.Submit(context.Background(), tk); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	waitForTaskState(t, sched, changes, tk.ID, TaskStateCompensating)
	select {
	case <-compensationStarted:
	case <-time.After(time.Second):
		t.Fatal("compensation executor did not start")
	}
	if sched.WaitForIdle(20 * time.Millisecond) {
		t.Fatal("WaitForIdle returned true while compensation was still running")
	}

	sched.Close()
	select {
	case <-compensationCancelled:
	case <-time.After(time.Second):
		t.Fatal("Close did not cancel the compensation executor")
	}
	waitForTaskState(t, sched, changes, tk.ID, TaskStateFailed)
	if !sched.WaitForIdle(time.Second) {
		t.Fatal("WaitForIdle returned false after compensation stopped")
	}
	sched.Close()
}

func TestScheduler_RegistryGet(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetResult("ping", 0, "ok", nil)
	sched, changes := makeScheduler(exec)

	task := makeTaskFor("task-reg-get", "sess-a", []Step{
		{Action: "ping", Args: map[string]interface{}{}},
	})
	if err := sched.Submit(context.Background(), task); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	waitForTaskState(t, sched, changes, task.ID, TaskStateSucceeded)

	got, ok := sched.Registry().Snapshot("sess-a", task.ID)
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

	if err := sched.Submit(context.Background(), task); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	waitForTaskState(t, sched, changes, task.ID, TaskStateSucceeded)

	entries := changes.snapshot()
	if len(entries) == 0 {
		t.Fatal("no state changes recorded")
	}

	// Verify the first transition is Queued.
	if entries[0].NewState != TaskStateQueued {
		t.Errorf("first state change: got %s, want Queued", entries[0].NewState)
	}
}

func TestScheduler_MultipleTasks(t *testing.T) {
	exec := newFakeExecutor()
	exec.SetResult("ping", 0, "ok", nil)
	sched, changes := makeScheduler(exec)

	for i := 0; i < 3; i++ {
		task := makeTaskFor("task-multi-"+string(rune('a'+i)), "sess-a", []Step{
			{Action: "ping", Args: map[string]interface{}{}},
		})
		if err := sched.Submit(context.Background(), task); err != nil {
			t.Fatalf("Submit failed: %v", err)
		}
		waitForTaskState(t, sched, changes, task.ID, TaskStateSucceeded)
	}

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

func TestScheduler_SubmitDefaultsZeroStepTimeout(t *testing.T) {
	type observation struct {
		timeoutMS   int
		hasDeadline bool
		remaining   time.Duration
	}
	observed := make(chan observation, 1)
	exec := newFakeExecutor()
	exec.SetStepFunc(func(ctx context.Context, sessionID, taskID string, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
		deadline, ok := ctx.Deadline()
		remaining := time.Duration(0)
		if ok {
			remaining = time.Until(deadline)
		}
		observed <- observation{timeoutMS: timeoutMS, hasDeadline: ok, remaining: remaining}
		return 0, "ok", nil, 1
	})
	sched, changes := makeScheduler(exec)
	defer sched.Close()

	tk := makeTaskFor("default-step-timeout", "owner-a", nil)
	if err := sched.Submit(context.Background(), tk); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	waitForTaskState(t, sched, changes, tk.ID, TaskStateSucceeded)

	got := <-observed
	if got.timeoutMS != 30000 {
		t.Errorf("executor timeout_ms: got %d, want 30000", got.timeoutMS)
	}
	if !got.hasDeadline {
		t.Error("zero timeout produced an unlimited worker context")
	}
	if got.remaining < 25*time.Second || got.remaining > 31*time.Second {
		t.Errorf("executor deadline remaining: got %s, want approximately 30s", got.remaining)
	}
	stored, ok := sched.Registry().SnapshotByID(tk.ID)
	if !ok || stored.Steps[0].TimeoutMS != 30000 {
		t.Fatalf("stored step timeout was not defaulted: %#v", stored)
	}
}

func TestScheduler_SubmitRejectsInvalidStepTimeout(t *testing.T) {
	for _, timeoutMS := range []int{-1, 300001} {
		t.Run(fmt.Sprintf("timeout_%d", timeoutMS), func(t *testing.T) {
			sched, _ := makeScheduler(newFakeExecutor())
			defer sched.Close()
			tk := makeTaskFor("invalid-step-timeout", "owner-a", []Step{{Action: "ping", TimeoutMS: timeoutMS}})
			if err := sched.Submit(context.Background(), tk); err == nil {
				t.Errorf("Submit accepted timeout_ms=%d", timeoutMS)
			}
		})
	}
}

func TestScheduler_SubmitRejectsInvalidRetryPolicy(t *testing.T) {
	cases := []RetryPolicy{
		{MaxAttempts: -1, InitialDelayMS: 100, MaxDelayMS: 100},
		{MaxAttempts: 11, InitialDelayMS: 100, MaxDelayMS: 100},
		{MaxAttempts: 1, InitialDelayMS: 99, MaxDelayMS: 100},
		{MaxAttempts: 1, InitialDelayMS: 60001, MaxDelayMS: 60001},
		{MaxAttempts: 1, InitialDelayMS: 100, MaxDelayMS: 99},
		{MaxAttempts: 1, InitialDelayMS: 100, MaxDelayMS: 300001},
	}
	for _, policy := range cases {
		t.Run(fmt.Sprintf("attempts_%d_initial_%d_max_%d", policy.MaxAttempts, policy.InitialDelayMS, policy.MaxDelayMS), func(t *testing.T) {
			sched, _ := makeScheduler(newFakeExecutor())
			defer sched.Close()
			tk := makeTaskFor("invalid-retry-policy", "owner-a", []Step{{Action: "ping", OnFailure: StepPolicyRetry}})
			tk.RetryPolicy = policy
			if err := sched.Submit(context.Background(), tk); err == nil {
				t.Errorf("Submit accepted invalid retry policy: %#v", policy)
			}
		})
	}
}

func TestScheduler_RegistersCompensationCancelBeforeVisibleState(t *testing.T) {
	oldTaskCancelled := make(chan struct{})
	var oldCancelOnce sync.Once
	compensatingEntered := make(chan struct{})
	allowTransitionReturn := make(chan struct{})
	releaseCompensation := make(chan struct{})
	var allowOnce sync.Once
	var releaseOnce sync.Once
	allowReturn := func() { allowOnce.Do(func() { close(allowTransitionReturn) }) }
	release := func() { releaseOnce.Do(func() { close(releaseCompensation) }) }
	defer allowReturn()
	defer release()

	var sched *Scheduler
	exec := newFakeExecutor()
	exec.SetStepFunc(func(ctx context.Context, sessionID, taskID string, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
		if action == "fail_step" {
			sched.taskMu.Lock()
			originalCancel := sched.running[taskID]
			sched.running[taskID] = func() {
				oldCancelOnce.Do(func() { close(oldTaskCancelled) })
				if originalCancel != nil {
					originalCancel()
				}
			}
			sched.taskMu.Unlock()
			return 1, "trigger compensation", nil, 1
		}
		select {
		case <-ctx.Done():
			return -1, "compensation cancelled", nil, 1
		case <-releaseCompensation:
			return 0, "compensation released", nil, 1
		}
	})

	changes := newStateChangeLog()
	sched = NewSchedulerWithOptions(exec, func(taskID string, oldState, newState TaskState, snapshot map[string]interface{}) {
		changes.record(stateChangeEntry{TaskID: taskID, OldState: oldState, NewState: newState, Snapshot: snapshot})
		if newState == TaskStateCompensating {
			// transitionLocked invokes this hook while holding taskMu. The
			// compensation cancel function must already be registered here;
			// otherwise Close can observe Compensating with only the old task cancel.
			if cancel := sched.running[taskID]; cancel != nil {
				cancel()
			}
			close(compensatingEntered)
			<-allowTransitionReturn
		}
	}, SchedulerOptions{})
	defer sched.Close()

	tk := makeTaskFor("compensation-registration-race", "owner-a", []Step{{
		Action:           "fail_step",
		OnFailure:        StepPolicyCompensate,
		CompensateAction: "undo_work",
	}})
	if err := sched.Submit(context.Background(), tk); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	select {
	case <-compensatingEntered:
	case <-time.After(5 * time.Second):
		t.Fatal("task did not enter Compensating")
	}

	select {
	case <-oldTaskCancelled:
		t.Error("Compensating was visible with the old task cancel still registered")
	default:
	}

	closeInvoked := make(chan struct{})
	closeDone := make(chan struct{})
	go func() {
		close(closeInvoked)
		sched.Close()
		close(closeDone)
	}()
	<-closeInvoked
	allowReturn()
	select {
	case <-closeDone:
	case <-time.After(5 * time.Second):
		t.Fatal("Close deadlocked during compensation transition")
	}

	deadline := time.NewTimer(500 * time.Millisecond)
	defer deadline.Stop()
	for {
		if current, ok := sched.Registry().SnapshotByID(tk.ID); ok && current.State == TaskStateFailed {
			return
		}
		select {
		case <-changes.notify:
		case <-deadline.C:
			current, _ := sched.Registry().SnapshotByID(tk.ID)
			t.Errorf("Close missed compensation cancellation; state=%s, want Failed", current.State)
			release()
			waitForTaskState(t, sched, changes, tk.ID, TaskStateFailed)
			return
		}
	}
}

func TestScheduler_CancelAndCompleteHaveOneLegalTerminalWinner(t *testing.T) {
	cases := []struct {
		name    string
		code    int
		allowed []TaskState
	}{
		{name: "success", code: 0, allowed: []TaskState{TaskStateSucceeded, TaskStateCancelled}},
		{name: "failure", code: 1, allowed: []TaskState{TaskStateFailed, TaskStateCancelled}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			for i := 0; i < 50; i++ {
				exec := newFakeExecutor()
				started := make(chan struct{})
				releaseCompletion := make(chan struct{})
				exec.SetStepFunc(func(ctx context.Context, sessionID, taskID string, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
					close(started)
					<-releaseCompletion
					return tc.code, "completed", nil, 1
				})
				sched, changes := makeScheduler(exec)
				tk := makeTaskFor(fmt.Sprintf("terminal-race-%d", i), "owner-a", nil)
				if err := sched.Submit(context.Background(), tk); err != nil {
					t.Fatalf("Submit failed: %v", err)
				}
				select {
				case <-started:
				case <-time.After(5 * time.Second):
					t.Fatal("executor did not start")
				}

				begin := make(chan struct{})
				var wg sync.WaitGroup
				wg.Add(2)
				go func() {
					defer wg.Done()
					<-begin
					sched.Cancel(tk.ID)
				}()
				go func() {
					defer wg.Done()
					<-begin
					close(releaseCompletion)
				}()
				close(begin)
				wg.Wait()
				waitForSchedulerInflight(t, sched, 0)

				final, ok := sched.Registry().SnapshotByID(tk.ID)
				if !ok {
					t.Fatalf("iteration %d: terminal task disappeared", i)
				}
				legal := false
				for _, state := range tc.allowed {
					if final.State == state {
						legal = true
						break
					}
				}
				if !legal {
					t.Fatalf("iteration %d: final state=%s, want one of %v", i, final.State, tc.allowed)
				}

				terminalEvents := 0
				for _, entry := range changes.snapshot() {
					if !entry.NewState.IsTerminal() {
						continue
					}
					terminalEvents++
					if entry.NewState != final.State {
						t.Fatalf("iteration %d: terminal event=%s, final state=%s", i, entry.NewState, final.State)
					}
				}
				if terminalEvents != 1 {
					t.Fatalf("iteration %d: got %d terminal events, want exactly one", i, terminalEvents)
				}
			}
		})
	}
}
