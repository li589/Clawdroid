package server

import (
	"bytes"
	"context"
	"log"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"clawdroid/runtime/internal/audit"
	"clawdroid/runtime/internal/config"
	"clawdroid/runtime/internal/ipc"
	"clawdroid/runtime/internal/task"
)

type lifecycleExecutorFunc func(context.Context, string, string, string, map[string]interface{}, int) (int, string, map[string]interface{}, int64)

func (f lifecycleExecutorFunc) ExecuteStep(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
	return f(ctx, sessionID, taskID, action, args, timeoutMS)
}

func newLifecycleServer(t *testing.T, executor task.StepExecutor) *Server {
	t.Helper()
	cfg := config.Default()
	cfg.AuditDir = t.TempDir()
	cfg.MaxConcurrentTasks = 1
	cfg.MaxInflightTasks = 8
	cfg.ShellEnabled = true
	cfg.ScreenshotEnabled = true
	cfg.InputInjectEnabled = true
	srv := New(cfg, audit.NewLogger())
	if executor != nil {
		srv.taskScheduler.Close()
		srv.taskScheduler = task.NewSchedulerWithOptions(executor, srv.onTaskStateChange, task.SchedulerOptions{
			MaxConcurrent: cfg.MaxConcurrentTasks,
			MaxInflight:   cfg.MaxInflightTasks,
		})
	}
	t.Cleanup(srv.taskScheduler.Close)
	return srv
}

func newLifecycleSession(srv *Server, packageName string, uid int, signature string) *session {
	sess := newSession()
	sess.completeAuthentication(packageName, signature, time.Now().Unix())
	sess.setPeerCredentials(peerCredentials{PID: 1000 + uid, UID: uid, GID: uid, Method: "test"})
	srv.finalizeCapabilityState(sess)
	return sess
}

func lifecycleTaskRequest(taskID, action string, args map[string]interface{}, onFailure task.StepFailurePolicy) ipc.Request {
	step := map[string]interface{}{
		"action": action,
		"args":   args,
	}
	if onFailure != "" {
		step["on_failure"] = string(onFailure)
	}
	return ipc.Request{
		RequestID: "submit-" + taskID,
		Action:    ipc.ActionTaskSubmit,
		Args: map[string]interface{}{
			"task": map[string]interface{}{
				"task_id": taskID,
				"steps":   []interface{}{step},
			},
		},
	}
}

func submitLifecycleTask(t *testing.T, srv *Server, sess *session, taskID, action string, args map[string]interface{}, onFailure task.StepFailurePolicy) ipc.Response {
	t.Helper()
	response := srv.handleTaskSubmit(sess, lifecycleTaskRequest(taskID, action, args, onFailure))
	if !response.OK {
		t.Fatalf("task_submit failed: %#v", response)
	}
	return response
}

func waitForLifecycleTaskState(t *testing.T, srv *Server, taskID string, want task.TaskState) task.Task {
	t.Helper()
	ticker := time.NewTicker(time.Millisecond)
	defer ticker.Stop()
	timer := time.NewTimer(5 * time.Second)
	defer timer.Stop()
	for {
		if current, ok := srv.taskScheduler.Registry().SnapshotByID(taskID); ok && current.State == want {
			return current
		}
		select {
		case <-ticker.C:
		case <-timer.C:
			current, _ := srv.taskScheduler.Registry().SnapshotByID(taskID)
			t.Fatalf("timed out waiting for task %s state %s; last snapshot: %#v", taskID, want, current)
			return task.Task{}
		}
	}
}

func waitForLifecycleExecutor(t *testing.T, started <-chan string, taskID string) {
	t.Helper()
	timer := time.NewTimer(5 * time.Second)
	defer timer.Stop()
	for {
		select {
		case got := <-started:
			if got == taskID {
				return
			}
		case <-timer.C:
			t.Fatalf("timed out waiting for executor to start task %s", taskID)
		}
	}
}

func waitForLifecycleSchedulerIdle(t *testing.T, srv *Server) {
	t.Helper()
	ticker := time.NewTicker(time.Millisecond)
	defer ticker.Stop()
	timer := time.NewTimer(5 * time.Second)
	defer timer.Stop()
	for srv.taskScheduler.InflightCount() != 0 {
		select {
		case <-ticker.C:
		case <-timer.C:
			t.Fatalf("timed out waiting for scheduler idle; inflight=%d", srv.taskScheduler.InflightCount())
		}
	}
}

func TestTaskListUsesStableOwnerAcrossConnections(t *testing.T) {
	srv := newLifecycleServer(t, lifecycleExecutorFunc(func(context.Context, string, string, string, map[string]interface{}, int) (int, string, map[string]interface{}, int64) {
		return 0, "ok", nil, 1
	}))
	submitter := newLifecycleSession(srv, "com.example.app", 10123, "sha256:signature-a")
	srv.registerSession(submitter)
	submitLifecycleTask(t, srv, submitter, "owner-list", ipc.ActionPing, map[string]interface{}{}, "")
	waitForLifecycleTaskState(t, srv, "owner-list", task.TaskStateSucceeded)

	reconnected := newLifecycleSession(srv, "com.example.app", 10123, "sha256:signature-a")
	srv.unregisterSession(submitter.id)
	response := srv.handleTaskList(reconnected, ipc.Request{RequestID: "list", Action: ipc.ActionTaskList})
	if !response.OK {
		t.Fatalf("task_list failed after reconnect: %#v", response)
	}
	tasks, ok := response.Data["tasks"].([]map[string]interface{})
	if !ok || len(tasks) != 1 || tasks[0]["task_id"] != "owner-list" {
		t.Fatalf("task_list did not return the same owner's task: %#v", response.Data["tasks"])
	}
	get := srv.handleTaskGet(reconnected, ipc.Request{RequestID: "get", Action: ipc.ActionTaskGet, Args: map[string]interface{}{"task_id": "owner-list"}})
	if !get.OK || get.Data["task_id"] != "owner-list" {
		t.Fatalf("task_get failed after reconnect: %#v", get)
	}

	for name, other := range map[string]*session{
		"package":   newLifecycleSession(srv, "com.example.other", 10123, "sha256:signature-a"),
		"uid":       newLifecycleSession(srv, "com.example.app", 10124, "sha256:signature-a"),
		"signature": newLifecycleSession(srv, "com.example.app", 10123, "sha256:signature-b"),
	} {
		t.Run(name, func(t *testing.T) {
			response := srv.handleTaskList(other, ipc.Request{RequestID: "list-other", Action: ipc.ActionTaskList})
			tasks, _ := response.Data["tasks"].([]map[string]interface{})
			if len(tasks) != 0 {
				t.Fatalf("different %s owner received task: %#v", name, tasks)
			}
		})
	}
}

func TestTaskCancelQueuedTaskAcrossConnection(t *testing.T) {
	started := make(chan string, 8)
	releaseBlocker := make(chan struct{})
	var releaseOnce sync.Once
	var queuedCalls atomic.Int32
	executor := lifecycleExecutorFunc(func(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
		started <- taskID
		if taskID == "blocker" {
			<-releaseBlocker
		}
		if taskID == "queued-cancel" {
			queuedCalls.Add(1)
		}
		return 0, "ok", nil, 1
	})
	srv := newLifecycleServer(t, executor)
	defer srv.taskScheduler.Close()
	defer releaseOnce.Do(func() { close(releaseBlocker) })

	owner := newLifecycleSession(srv, "com.example.app", 10123, "sha256:signature-a")
	reconnected := newLifecycleSession(srv, "com.example.app", 10123, "sha256:signature-a")
	submitLifecycleTask(t, srv, owner, "blocker", ipc.ActionPing, map[string]interface{}{}, "")
	waitForLifecycleExecutor(t, started, "blocker")
	submitLifecycleTask(t, srv, owner, "queued-cancel", ipc.ActionPing, map[string]interface{}{}, "")

	response := srv.handleTaskCancel(reconnected, ipc.Request{
		RequestID: "cancel",
		Action:    ipc.ActionTaskCancel,
		Args:      map[string]interface{}{"task_id": "queued-cancel"},
	})
	if !response.OK || response.Code != ipc.CodeOK {
		t.Fatalf("task_cancel for queued task failed: %#v", response)
	}
	waitForLifecycleTaskState(t, srv, "queued-cancel", task.TaskStateCancelled)

	releaseOnce.Do(func() { close(releaseBlocker) })
	waitForLifecycleTaskState(t, srv, "blocker", task.TaskStateSucceeded)
	waitForLifecycleSchedulerIdle(t, srv)
	if got := queuedCalls.Load(); got != 0 {
		t.Fatalf("cancelled queued task executed %d times", got)
	}
}

func TestTaskCancelRejectsTerminalAndIllegalStates(t *testing.T) {
	for _, state := range []task.TaskState{task.TaskStateSucceeded, task.TaskStateFailed, task.TaskStateCancelled, task.TaskStateCompensating, task.TaskStateCreated} {
		t.Run(string(state), func(t *testing.T) {
			started := make(chan string, 2)
			releaseBlocker := make(chan struct{})
			var releaseOnce sync.Once
			executor := lifecycleExecutorFunc(func(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
				started <- taskID
				if taskID == "blocker" {
					<-releaseBlocker
				}
				return 0, "ok", nil, 1
			})
			srv := newLifecycleServer(t, executor)
			defer srv.taskScheduler.Close()
			defer releaseOnce.Do(func() { close(releaseBlocker) })

			owner := newLifecycleSession(srv, "com.example.app", 10123, "sha256:signature-a")
			submitLifecycleTask(t, srv, owner, "blocker", ipc.ActionPing, map[string]interface{}{}, "")
			waitForLifecycleExecutor(t, started, "blocker")
			submitLifecycleTask(t, srv, owner, "invalid-cancel", ipc.ActionPing, map[string]interface{}{}, "")
			if err := srv.taskScheduler.Registry().UpdateByID("invalid-cancel", func(tk *task.Task) error {
				tk.State = state
				if state.IsTerminal() {
					tk.EndedAt = time.Now()
				}
				return nil
			}); err != nil {
				t.Fatalf("set up task state: %v", err)
			}

			response := srv.handleTaskCancel(owner, ipc.Request{
				RequestID: "cancel",
				Action:    ipc.ActionTaskCancel,
				Args:      map[string]interface{}{"task_id": "invalid-cancel"},
			})
			if response.OK || response.Code != ipc.CodeErrTaskStateInvalid {
				t.Fatalf("task_cancel for %s returned %#v, want 7002", state, response)
			}
			releaseOnce.Do(func() { close(releaseBlocker) })
		})
	}
}

func TestTaskGetAndCancelRejectDifferentOwner(t *testing.T) {
	srv := newLifecycleServer(t, lifecycleExecutorFunc(func(context.Context, string, string, string, map[string]interface{}, int) (int, string, map[string]interface{}, int64) {
		return 0, "ok", nil, 1
	}))
	owner := newLifecycleSession(srv, "com.example.app", 10123, "sha256:signature-a")
	other := newLifecycleSession(srv, "com.example.other", 10123, "sha256:signature-a")
	submitLifecycleTask(t, srv, owner, "private-task", ipc.ActionPing, map[string]interface{}{}, "")
	waitForLifecycleTaskState(t, srv, "private-task", task.TaskStateSucceeded)

	get := srv.handleTaskGet(other, ipc.Request{RequestID: "get", Action: ipc.ActionTaskGet, Args: map[string]interface{}{"task_id": "private-task"}})
	if get.OK || get.Code != ipc.CodeErrTaskNotFound {
		t.Fatalf("different owner task_get returned %#v, want 7001", get)
	}
	cancel := srv.handleTaskCancel(other, ipc.Request{RequestID: "cancel", Action: ipc.ActionTaskCancel, Args: map[string]interface{}{"task_id": "private-task"}})
	if cancel.OK || cancel.Code != ipc.CodeErrTaskNotFound {
		t.Fatalf("different owner task_cancel returned %#v, want 7001", cancel)
	}
}

func TestTaskSubmitRequiresStablePrincipal(t *testing.T) {
	srv := newLifecycleServer(t, lifecycleExecutorFunc(func(context.Context, string, string, string, map[string]interface{}, int) (int, string, map[string]interface{}, int64) {
		return 0, "ok", nil, 1
	}))
	response := srv.handleTaskSubmit(newSession(), lifecycleTaskRequest("anonymous-task", ipc.ActionPing, map[string]interface{}{}, ""))
	if response.OK || response.Code != ipc.CodeErrPeerVerifyFailed {
		t.Fatalf("task_submit without stable principal returned %#v, want 1001", response)
	}
}

func TestExecuteStepUsesImmutableTaskPrincipal(t *testing.T) {
	srv := newLifecycleServer(t, nil)
	owner := newLifecycleSession(srv, "com.example.app", 10123, "sha256:signature-a")
	srv.registerSession(owner)
	submitLifecycleTask(t, srv, owner, "principal-task", ipc.ActionPing, map[string]interface{}{}, "")
	waitForLifecycleTaskState(t, srv, "principal-task", task.TaskStateSucceeded)

	owner.completeAuthentication("com.example.changed", "sha256:changed", time.Now().Unix())
	code, _, data, _ := srv.ExecuteStep(context.Background(), owner.id, "principal-task", ipc.ActionGetCapabilities, nil, 0)
	if code != ipc.CodeOK || data["package_name"] != "com.example.app" {
		t.Fatalf("ExecuteStep used mutable connection identity: code=%d data=%#v", code, data)
	}
}

func TestExecuteStepSurvivesClosedSubmittingConnection(t *testing.T) {
	srv := newLifecycleServer(t, nil)
	owner := newLifecycleSession(srv, "com.example.app", 10123, "sha256:signature-a")
	srv.registerSession(owner)
	submitLifecycleTask(t, srv, owner, "closed-submitter", ipc.ActionPing, map[string]interface{}{}, "")
	waitForLifecycleTaskState(t, srv, "closed-submitter", task.TaskStateSucceeded)
	srv.unregisterSession(owner.id)

	code, _, data, _ := srv.ExecuteStep(context.Background(), owner.id, "closed-submitter", ipc.ActionGetCapabilities, nil, 0)
	if code != ipc.CodeOK || data["package_name"] != "com.example.app" {
		t.Fatalf("ExecuteStep failed after submitting connection closed: code=%d data=%#v", code, data)
	}
}

func TestExecuteStepDoesNotBorrowUnrelatedActiveSession(t *testing.T) {
	srv := newLifecycleServer(t, nil)
	owner := newLifecycleSession(srv, "com.example.app", 10123, "sha256:signature-a")
	other := newLifecycleSession(srv, "com.example.other", 10123, "sha256:signature-b")
	srv.registerSession(owner)
	submitLifecycleTask(t, srv, owner, "no-borrow", ipc.ActionPing, map[string]interface{}{}, "")
	waitForLifecycleTaskState(t, srv, "no-borrow", task.TaskStateSucceeded)
	srv.unregisterSession(owner.id)
	srv.registerSession(other)

	code, _, data, _ := srv.ExecuteStep(context.Background(), owner.id, "no-borrow", ipc.ActionGetCapabilities, nil, 0)
	if code != ipc.CodeOK || data["package_name"] != "com.example.app" {
		t.Fatalf("ExecuteStep borrowed unrelated session identity: code=%d data=%#v", code, data)
	}
}

func TestExecuteStepHonorsCancelledContext(t *testing.T) {
	srv := newLifecycleServer(t, nil)
	owner := newLifecycleSession(srv, "com.example.app", 10123, "sha256:signature-a")
	srv.registerSession(owner)
	submitLifecycleTask(t, srv, owner, "context-task", ipc.ActionPing, map[string]interface{}{}, "")
	waitForLifecycleTaskState(t, srv, "context-task", task.TaskStateSucceeded)

	cases := []struct {
		action string
		args   map[string]interface{}
	}{
		{action: ipc.ActionCaptureScreen, args: map[string]interface{}{}},
		{action: ipc.ActionInjectTap, args: map[string]interface{}{"x": float64(1), "y": float64(1)}},
		{action: ipc.ActionInjectSwipe, args: map[string]interface{}{"x1": float64(1), "y1": float64(1), "x2": float64(2), "y2": float64(2)}},
		{action: ipc.ActionInjectKeyevent, args: map[string]interface{}{"key": "BACK"}},
		{action: ipc.ActionExecShellLimited, args: map[string]interface{}{"command": "sleep 1", "timeout_ms": float64(100)}},
	}
	for _, tc := range cases {
		t.Run(tc.action, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			cancel()
			code, message, _, _ := srv.ExecuteStep(ctx, owner.id, "context-task", tc.action, tc.args, 0)
			if code != ipc.CodeErrCancelled {
				t.Fatalf("ExecuteStep(%s) returned code=%d message=%q, want 5", tc.action, code, message)
			}
		})
	}
}

func TestExecuteStepHonorsPerStepTimeout(t *testing.T) {
	srv := newLifecycleServer(t, nil)
	owner := newLifecycleSession(srv, "com.example.app", 10123, "sha256:signature-a")
	srv.registerSession(owner)
	submitLifecycleTask(t, srv, owner, "timeout-task", ipc.ActionPing, map[string]interface{}{}, "")
	waitForLifecycleTaskState(t, srv, "timeout-task", task.TaskStateSucceeded)

	started := time.Now()
	code, message, _, _ := srv.ExecuteStep(context.Background(), owner.id, "timeout-task", ipc.ActionExecShellLimited, map[string]interface{}{
		"command":    "sleep 1",
		"timeout_ms": float64(1000),
	}, 50)
	elapsed := time.Since(started)
	if code != ipc.CodeErrTimeout {
		t.Fatalf("ExecuteStep returned code=%d message=%q elapsed=%s, want timeout", code, message, elapsed)
	}
	if elapsed > 900*time.Millisecond {
		t.Fatalf("ExecuteStep ignored 50ms step timeout: elapsed=%s", elapsed)
	}
}

func TestServerStartShutdownClosesSchedulerAndCancelsExecutor(t *testing.T) {
	started := make(chan struct{})
	cancelled := make(chan struct{})
	executor := lifecycleExecutorFunc(func(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
		close(started)
		<-ctx.Done()
		close(cancelled)
		return -1, "cancelled", nil, 1
	})
	srv := newLifecycleServer(t, executor)
	srv.cfg.SocketName = "clawdroid_" + t.Name()
	defer func() {
		srv.taskScheduler.Close()
		select {
		case <-cancelled:
		case <-time.After(time.Second):
			t.Error("executor was not cancelled during test cleanup")
		}
	}()

	tk := &task.Task{
		ID:        "shutdown-running",
		SessionID: "owner-a",
		Steps:     []task.Step{{Action: "block-until-cancelled"}},
	}
	if err := srv.taskScheduler.Submit(context.Background(), tk); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	select {
	case <-started:
	case <-time.After(5 * time.Second):
		t.Fatal("executor did not start")
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	startDone := make(chan error, 1)
	go func() {
		startDone <- srv.Start(ctx)
	}()
	select {
	case err := <-startDone:
		if err != nil {
			t.Fatalf("Start returned an error after context cancellation: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Start did not return after context cancellation")
	}

	current, ok := srv.taskScheduler.Registry().SnapshotByID(tk.ID)
	if !ok || current.State != task.TaskStateCancelled {
		t.Errorf("task after Start returned: got %#v, present=%v; want Cancelled", current, ok)
	}
	select {
	case <-cancelled:
	default:
		t.Error("executor context was not cancelled when Start returned")
	}
	if got := srv.taskScheduler.InflightCount(); got != 0 {
		t.Errorf("scheduler inflight after Start returned: got %d, want 0", got)
	}
}

func TestServerSchedulerShutdownTimeoutLogsWarning(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	finished := make(chan struct{})
	var releaseOnce sync.Once
	releaseExecutor := func() {
		releaseOnce.Do(func() { close(release) })
	}
	executor := lifecycleExecutorFunc(func(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
		close(started)
		<-release
		close(finished)
		return 0, "ok", nil, 1
	})
	srv := newLifecycleServer(t, executor)
	defer func() {
		srv.taskScheduler.Close()
		releaseExecutor()
		select {
		case <-finished:
		case <-time.After(time.Second):
			t.Error("executor did not finish during test cleanup")
		}
		if !srv.taskScheduler.WaitForIdle(time.Second) {
			t.Error("scheduler did not become idle during test cleanup")
		}
	}()

	tk := &task.Task{
		ID:        "shutdown-timeout",
		SessionID: "owner-a",
		Steps:     []task.Step{{Action: "ignore-cancellation"}},
	}
	if err := srv.taskScheduler.Submit(context.Background(), tk); err != nil {
		t.Fatalf("Submit failed: %v", err)
	}
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("executor did not start")
	}

	var logs bytes.Buffer
	previousLogWriter := log.Writer()
	log.SetOutput(&logs)
	defer log.SetOutput(previousLogWriter)

	const timeout = 25 * time.Millisecond
	startedAt := time.Now()
	srv.shutdownTaskScheduler(timeout)
	elapsed := time.Since(startedAt)
	if elapsed > time.Second {
		t.Fatalf("scheduler shutdown ignored its bound: elapsed=%s", elapsed)
	}
	if !strings.Contains(logs.String(), "task scheduler shutdown timed out") {
		t.Fatalf("shutdown timeout did not log a warning: %q", logs.String())
	}
	if got := srv.taskScheduler.InflightCount(); got != 1 {
		t.Fatalf("scheduler inflight after shutdown timeout: got %d, want 1", got)
	}
}
