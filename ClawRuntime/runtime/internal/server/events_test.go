package server

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"reflect"
	"sync"
	"testing"
	"time"

	"clawdroid/runtime/internal/audit"
	"clawdroid/runtime/internal/config"
	"clawdroid/runtime/internal/ipc"
	"clawdroid/runtime/internal/task"
)

func TestEventSubscriptionGlobalQuota(t *testing.T) {
	t.Parallel()

	srv := New(config.Default(), audit.NewLogger())
	t.Cleanup(srv.taskScheduler.Close)
	channels := make([]chan struct{}, 0, maxConcurrentEventSubscriptions)
	for i := 0; i < maxConcurrentEventSubscriptions; i++ {
		ch := make(chan struct{}, 1)
		if !srv.registerEventSub(ch) {
			t.Fatalf("subscription %d below quota was rejected", i)
		}
		channels = append(channels, ch)
	}
	if srv.registerEventSub(make(chan struct{}, 1)) {
		t.Fatal("subscription above global quota was accepted")
	}
	srv.unregisterEventSub(channels[0])
	if !srv.registerEventSub(make(chan struct{}, 1)) {
		t.Fatal("released subscription slot was not reusable")
	}
}

func TestRequestLoopSubscribeEventsReplayStartsNewStream(t *testing.T) {
	t.Parallel()

	srv := New(config.Default(), audit.NewLogger())
	t.Cleanup(srv.taskScheduler.Close)
	firstConn, firstReader := startSecurityRequestLoop(t, srv, newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature"))
	secondConn, secondReader := startSecurityRequestLoop(t, srv, newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature"))
	req := securityRequest(srv, "subscribe-replay", ipc.ActionSubscribeEvents, map[string]interface{}{
		"events": []interface{}{subscribeEventCapabilityChanged},
	})

	writeSecurityRequest(t, firstConn, req)
	if response := readSecurityResponse(t, firstReader); !response.OK || response.Data["stream_mode"] != "continuous" {
		t.Fatalf("first subscription response = %#v", response)
	}
	if frame, err := readEventTestFrame(firstReader); err != nil || frame.Event != subscribeEventCapabilityChanged {
		t.Fatalf("first subscription initial frame = %#v, err = %v", frame, err)
	}

	writeSecurityRequest(t, secondConn, req)
	if response := readSecurityResponse(t, secondReader); !response.OK || response.Data["stream_mode"] != "continuous" {
		t.Fatalf("replayed subscription response = %#v", response)
	}
	_ = secondConn.SetReadDeadline(time.Now().Add(2 * time.Second))
	frame, err := readEventTestFrame(secondReader)
	if err != nil {
		t.Fatalf("replayed subscribe_events returned ACK without starting a stream: %v", err)
	}
	if frame.Event != subscribeEventCapabilityChanged {
		t.Fatalf("replayed subscription initial frame = %#v", frame)
	}
}

func TestPrepareEventSubscriptionRejectsWhenFull(t *testing.T) {
	t.Parallel()

	srv := New(config.Default(), audit.NewLogger())
	t.Cleanup(srv.taskScheduler.Close)
	sess := newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature")
	for i := 0; i < maxConcurrentEventSubscriptions; i++ {
		srv.registerEventSub(make(chan struct{}, 1))
	}
	response, subscription := srv.prepareEventSubscription(sess, ipc.Request{
		RequestID: "subscribe-full",
		Action:    ipc.ActionSubscribeEvents,
		Args: map[string]interface{}{
			"events": []interface{}{subscribeEventCapabilityChanged},
		},
	})
	if subscription != nil {
		srv.unregisterEventSub(subscription.wake)
		t.Fatal("full subscription quota returned a live subscription")
	}
	if response.OK || response.Code != ipc.CodeErrRateLimited {
		t.Fatalf("full subscription quota response = %#v", response)
	}
	if len(srv.eventSubs) != maxConcurrentEventSubscriptions {
		t.Fatalf("subscription count = %d, want %d", len(srv.eventSubs), maxConcurrentEventSubscriptions)
	}
}

func TestSubscribeEventsClosesExpiredSessionOnWake(t *testing.T) {
	t.Parallel()

	cfg := config.Default()
	cfg.SessionTTLSec = 1
	srv := New(cfg, audit.NewLogger())
	t.Cleanup(srv.taskScheduler.Close)
	sess := newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature")

	client, server := net.Pipe()
	t.Cleanup(func() {
		_ = client.Close()
		_ = server.Close()
	})
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	response, subscription := srv.prepareEventSubscription(sess, ipc.Request{
		RequestID: "subscribe-ttl",
		Timestamp: time.Now().Unix(),
		Action:    ipc.ActionSubscribeEvents,
		Args: map[string]interface{}{
			"events": []interface{}{subscribeEventCapabilityChanged},
		},
	})
	if !response.OK || subscription == nil {
		t.Fatalf("prepare subscription: response=%#v subscription=%#v", response, subscription)
	}
	t.Cleanup(func() { srv.unregisterEventSub(subscription.wake) })
	done := make(chan error, 1)
	go func() {
		done <- srv.streamEventSubscription(ctx, sess, subscription, server, bufio.NewWriter(server))
	}()

	reader := bufio.NewReader(client)
	if _, err := readEventTestFrame(reader); err != nil {
		t.Fatalf("read initial event: %v", err)
	}

	sess.mu.Lock()
	sess.authenticatedAt = time.Now().Add(-2 * time.Second)
	sess.mu.Unlock()
	srv.notifyEventWake()
	select {
	case err := <-done:
		if !errors.Is(err, errSessionExpired) {
			t.Fatalf("expired subscription error = %v, want errSessionExpired", err)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("subscription did not close after session TTL elapsed on wake")
	}
	if sess.currentState() != StateClosed {
		t.Fatalf("expired subscription session state = %s, want Closed", sess.currentState())
	}
	if len(srv.eventSubs) != 0 {
		t.Fatalf("expired subscription leaked %d global slots", len(srv.eventSubs))
	}
}

func readEventTestFrame(reader *bufio.Reader) (eventFrame, error) {
	var frame eventFrame
	payload, err := readJSONFrame(reader, 2<<20)
	if err != nil {
		return frame, err
	}
	return frame, json.Unmarshal(payload, &frame)
}

func TestParseSubscribeEventsArgsDeduplicatesAndPreservesOrder(t *testing.T) {
	t.Parallel()

	args, err := parseSubscribeEventsArgs(map[string]interface{}{
		"events": []interface{}{
			subscribeEventCapabilityChanged,
			subscribeEventDaemonStatus,
			subscribeEventCapabilityChanged,
		},
	})
	if err != nil {
		t.Fatalf("parse subscribe events args: %v", err)
	}

	want := []string{
		subscribeEventCapabilityChanged,
		subscribeEventDaemonStatus,
	}
	if !reflect.DeepEqual(args.Events, want) {
		t.Fatalf("unexpected events: got %#v want %#v", args.Events, want)
	}
}

func TestParseSubscribeEventsArgsRejectsUnsupportedEvent(t *testing.T) {
	t.Parallel()

	if _, err := parseSubscribeEventsArgs(map[string]interface{}{
		"events": []interface{}{"unsupported_event"},
	}); err == nil {
		t.Fatal("expected unsupported event to be rejected")
	}
}

func TestDiffSnapshotEventFramesIgnoresStringSliceOrdering(t *testing.T) {
	t.Parallel()

	previous := eventSnapshot{
		capabilityState: map[string]interface{}{
			"capabilities": []string{"screen.capture", "input.inject"},
		},
	}
	current := eventSnapshot{
		capabilityState: map[string]interface{}{
			"capabilities": []string{"input.inject", "screen.capture"},
		},
	}

	frames := diffSnapshotEventFrames(previous, current, []string{subscribeEventCapabilityChanged})
	if len(frames) != 0 {
		t.Fatalf("expected no diff frames for reordered capabilities, got %#v", frames)
	}
}

func TestMarshalFocusedWindowIncludesParsedFields(t *testing.T) {
	t.Parallel()

	raw := "mCurrentFocus=Window{123 u0 com.example/.MainActivity}"
	marshaled := marshalFocusedWindow(raw)

	var payload map[string]string
	if err := json.Unmarshal([]byte(marshaled), &payload); err != nil {
		t.Fatalf("unmarshal focused window payload: %v", err)
	}

	if payload["source"] != "mCurrentFocus" {
		t.Fatalf("unexpected source: got %q", payload["source"])
	}
	if payload["package"] != "com.example" {
		t.Fatalf("unexpected package: got %q", payload["package"])
	}
	if payload["activity"] != ".MainActivity" {
		t.Fatalf("unexpected activity: got %q", payload["activity"])
	}
	if payload["summary"] != "com.example/.MainActivity" {
		t.Fatalf("unexpected summary: got %q", payload["summary"])
	}
}

func TestExtractFocusedComponentReturnsEmptyForNoise(t *testing.T) {
	t.Parallel()

	if component := extractFocusedComponent("random line without activity"); component != "" {
		t.Fatalf("expected empty focused component, got %q", component)
	}
}

func TestDiffSnapshotEventFramesDetectsXposedFocusChange(t *testing.T) {
	t.Parallel()

	previous := eventSnapshot{
		xposedFocusState: map[string]interface{}{
			"package_name":   "com.android.settings",
			"activity_class": "com.android.settings.Settings",
		},
	}
	current := eventSnapshot{
		xposedFocusState: map[string]interface{}{
			"package_name":   "com.android.settings",
			"activity_class": "com.android.settings.wifi.WifiSettings",
			"source":         "report",
		},
	}

	frames := diffSnapshotEventFrames(previous, current, []string{subscribeEventXposedFocusChanged})
	if len(frames) != 1 {
		t.Fatalf("expected 1 xposed_focus_changed frame, got %#v", frames)
	}
	if frames[0].Event != subscribeEventXposedFocusChanged {
		t.Fatalf("unexpected event name: %q", frames[0].Event)
	}
	if frames[0].Data["activity_class"] != "com.android.settings.wifi.WifiSettings" {
		t.Fatalf("unexpected activity_class: %#v", frames[0].Data["activity_class"])
	}
}

func TestParseSubscribeEventsArgsAllowsXposedFocusChanged(t *testing.T) {
	t.Parallel()

	args, err := parseSubscribeEventsArgs(map[string]interface{}{
		"events": []interface{}{subscribeEventXposedFocusChanged},
	})
	if err != nil {
		t.Fatalf("parse subscribe events args: %v", err)
	}
	if len(args.Events) != 1 || args.Events[0] != subscribeEventXposedFocusChanged {
		t.Fatalf("unexpected events: %#v", args.Events)
	}
}

func TestParseSubscribeEventsArgsAllowsXposedViewChanged(t *testing.T) {
	t.Parallel()

	args, err := parseSubscribeEventsArgs(map[string]interface{}{
		"events": []interface{}{subscribeEventXposedViewChanged},
	})
	if err != nil {
		t.Fatalf("parse subscribe events args: %v", err)
	}
	if len(args.Events) != 1 || args.Events[0] != subscribeEventXposedViewChanged {
		t.Fatalf("unexpected events: %#v", args.Events)
	}
}

func TestDiffSnapshotEventFramesDetectsXposedViewChange(t *testing.T) {
	t.Parallel()

	previous := eventSnapshot{
		xposedViewState: map[string]interface{}{
			"package_name": "com.android.settings",
			"node_count":   float64(4),
		},
	}
	current := eventSnapshot{
		xposedViewState: map[string]interface{}{
			"package_name":    "com.android.settings",
			"node_count":      float64(8),
			"compose_surface": true,
			"source":          "report",
		},
	}

	frames := diffSnapshotEventFrames(previous, current, []string{subscribeEventXposedViewChanged})
	if len(frames) != 1 {
		t.Fatalf("expected 1 xposed_view_changed frame, got %#v", frames)
	}
	if frames[0].Data["compose_surface"] != true {
		t.Fatalf("unexpected compose_surface: %#v", frames[0].Data["compose_surface"])
	}
}

type snapshotExecutorFunc func(context.Context, string, string, string, map[string]interface{}, int) (int, string, map[string]interface{}, int64)

func (f snapshotExecutorFunc) ExecuteStep(ctx context.Context, sessionID, taskID, action string, args map[string]interface{}, timeoutMS int) (int, string, map[string]interface{}, int64) {
	return f(ctx, sessionID, taskID, action, args, timeoutMS)
}

func TestTaskListAndEventSnapshotsConcurrentWithScheduler(t *testing.T) {
	t.Parallel()

	const taskCount = 96
	terminal := make(chan string, taskCount)
	exec := snapshotExecutorFunc(func(context.Context, string, string, string, map[string]interface{}, int) (int, string, map[string]interface{}, int64) {
		return 0, "ok", map[string]interface{}{"result": "done"}, 1
	})
	sched := task.NewSchedulerWithOptions(exec, func(taskID string, oldState, newState task.TaskState, snapshot map[string]interface{}) {
		if newState.IsTerminal() {
			terminal <- taskID
		}
	}, task.SchedulerOptions{MaxConcurrent: 8, MaxInflight: taskCount})
	defer sched.Close()
	srv := &Server{
		cfg:           config.Config{},
		taskScheduler: sched,
		shellJobs:     newShellJobTracker(),
	}
	sess := newSession()
	sess.completeAuthentication("com.example.app", "sha256:signature-a", time.Now().Unix())
	sess.setPeerCredentials(peerCredentials{PID: 1000, UID: 10123, GID: 10123, Method: "test"})
	srv.finalizeCapabilityState(sess)
	principal := task.Principal{PackageName: "com.example.app", UID: 10123, SignatureDigest: "sha256:signature-a"}

	start := make(chan struct{})
	done := make(chan struct{})
	var readers sync.WaitGroup
	for i := 0; i < 4; i++ {
		readers.Add(1)
		go func() {
			defer readers.Done()
			<-start
			for {
				select {
				case <-done:
					return
				default:
				}
				response := srv.handleTaskList(sess, ipc.Request{})
				if !response.OK {
					t.Errorf("task_list failed: %#v", response)
					return
				}
				tasks, ok := response.Data["tasks"].([]map[string]interface{})
				if !ok {
					t.Errorf("task_list returned unexpected tasks: %#v", response.Data["tasks"])
					return
				}
				for _, summary := range tasks {
					assertTaskSummaryConsistent(t, summary)
				}
				eventState := srv.taskStateForSession(sess)
				assertTaskSummaryConsistent(t, eventState)
				active, ok := eventState["active_tasks"].([]map[string]interface{})
				if !ok {
					t.Errorf("event snapshot returned unexpected active_tasks: %#v", eventState["active_tasks"])
					return
				}
				for _, summary := range active {
					assertTaskSummaryConsistent(t, summary)
				}
			}
		}()
	}
	close(start)

	for i := 0; i < taskCount; i++ {
		taskID := fmt.Sprintf("task-concurrent-%03d", i)
		if err := sched.Submit(context.Background(), &task.Task{
			ID:        taskID,
			SessionID: sess.id,
			Owner:     principal,
			OwnerKey:  principal.OwnerKey(),
			Steps:     []task.Step{{Action: "ping"}},
		}); err != nil {
			close(done)
			readers.Wait()
			t.Fatalf("Submit failed: %v", err)
		}
	}

	timer := time.NewTimer(10 * time.Second)
	defer timer.Stop()
	for i := 0; i < taskCount; i++ {
		select {
		case <-terminal:
		case <-timer.C:
			close(done)
			readers.Wait()
			t.Fatalf("timed out waiting for terminal tasks: completed %d of %d", i, taskCount)
		}
	}
	close(done)
	readers.Wait()
}

func assertTaskSummaryConsistent(t *testing.T, summary map[string]interface{}) {
	t.Helper()
	if summary["phase"] == "idle" {
		return
	}
	currentStep, ok := summary["current_step"].(int)
	if !ok {
		t.Errorf("task snapshot missing current_step: %#v", summary)
		return
	}
	if completed, exists := summary["completed_steps"]; exists {
		completedSteps, ok := completed.(int)
		if !ok || completedSteps > currentStep {
			t.Errorf("inconsistent task snapshot: completed_steps=%v current_step=%d", completed, currentStep)
		}
	}
	if summary["state"] == string(task.TaskStateSucceeded) {
		totalSteps, ok := summary["total_steps"].(int)
		if !ok || currentStep != totalSteps {
			t.Errorf("inconsistent succeeded snapshot: current_step=%d total_steps=%v", currentStep, summary["total_steps"])
		}
	}
}
