package server

import (
	"context"
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"clawdroid/runtime/internal/ipc"
)

func mustArgsDigest(t *testing.T, args map[string]interface{}) string {
	t.Helper()
	digest, err := canonicalArgsDigest(args)
	if err != nil {
		t.Fatalf("canonicalArgsDigest() error = %v", err)
	}
	return digest
}

// replayWaitGateContext pauses a duplicate at the point where it has claimed a
// specific in-flight entry and is about to wait for that entry's result.
type replayWaitGateContext struct {
	context.Context
	waiting chan struct{}
	proceed chan struct{}
	once    sync.Once
}

func newReplayWaitGateContext() *replayWaitGateContext {
	return &replayWaitGateContext{
		Context: context.Background(),
		waiting: make(chan struct{}),
		proceed: make(chan struct{}),
	}
}

func (c *replayWaitGateContext) Done() <-chan struct{} {
	c.once.Do(func() { close(c.waiting) })
	<-c.proceed
	return c.Context.Done()
}

func TestReplayCacheCachesNonStreamingActions(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	actions := []string{
		ipc.ActionPing,
		ipc.ActionGetCapabilities,
		ipc.ActionInjectTap,
		ipc.ActionTaskCancel,
	}
	for _, action := range actions {
		reqID := "req-" + action
		resp := ipc.Response{RequestID: reqID, OK: true, Message: "accepted"}
		digest := mustArgsDigest(t, nil)
		got, status := c.execute(context.Background(), "principal-a", action, reqID, digest, func() ipc.Response {
			return resp
		})
		if status != replayExecuted || got.Message != resp.Message {
			t.Fatalf("first %s execution: status=%v response=%#v", action, status, got)
		}

		var replayCalls int
		got, status = c.execute(context.Background(), "principal-a", action, reqID, digest, func() ipc.Response {
			replayCalls++
			return ipc.Response{Message: "must not execute"}
		})
		if status != replayHit || replayCalls != 0 || got.Message != resp.Message {
			t.Fatalf("replayed %s: status=%v calls=%d response=%#v", action, status, replayCalls, got)
		}
	}
}

func TestReplayCacheIsPrincipalScopedNotSessionScoped(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	digest := mustArgsDigest(t, nil)
	first := ipc.Response{RequestID: "req-001", OK: true, Message: "first"}
	_, status := c.execute(context.Background(), "principal-a", ipc.ActionPing, "req-001", digest, func() ipc.Response {
		return first
	})
	if status != replayExecuted {
		t.Fatalf("first execution status = %v", status)
	}

	got, status := c.execute(context.Background(), "principal-a", ipc.ActionPing, "req-001", digest, func() ipc.Response {
		return ipc.Response{Message: "wrong"}
	})
	if status != replayHit || got.Message != first.Message {
		t.Fatalf("same principal replay: status=%v response=%#v", status, got)
	}

	var otherCalls int
	_, status = c.execute(context.Background(), "principal-b", ipc.ActionPing, "req-001", digest, func() ipc.Response {
		otherCalls++
		return ipc.Response{RequestID: "req-001", OK: true}
	})
	if status != replayExecuted || otherCalls != 1 {
		t.Fatalf("different principal must execute independently: status=%v calls=%d", status, otherCalls)
	}
}

func TestCanonicalArgsDigestNormalizesJSON(t *testing.T) {
	t.Parallel()

	first := map[string]interface{}{
		"b": 2,
		"a": map[string]interface{}{"z": "last", "a": []interface{}{true, nil, "x"}},
	}
	second := map[string]interface{}{
		"a": map[string]interface{}{"a": []interface{}{true, nil, "x"}, "z": "last"},
		"b": 2.0,
	}
	if mustArgsDigest(t, first) != mustArgsDigest(t, second) {
		t.Fatal("map insertion order and equivalent JSON numbers changed the digest")
	}
	if mustArgsDigest(t, nil) != mustArgsDigest(t, map[string]interface{}{}) {
		t.Fatal("nil and empty args must normalize to the same digest")
	}
	if mustArgsDigest(t, first) == mustArgsDigest(t, map[string]interface{}{"b": 3, "a": first["a"]}) {
		t.Fatal("different arguments produced the same digest")
	}
}

func TestReplayCacheRejectsArgsConflict(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	firstDigest := mustArgsDigest(t, map[string]interface{}{"x": 1})
	_, status := c.execute(context.Background(), "principal-a", ipc.ActionInjectTap, "req-001", firstDigest, func() ipc.Response {
		return ipc.Response{RequestID: "req-001", OK: true}
	})
	if status != replayExecuted {
		t.Fatalf("first execution status = %v", status)
	}

	var calls int
	got, status := c.execute(context.Background(), "principal-a", ipc.ActionInjectTap, "req-001",
		mustArgsDigest(t, map[string]interface{}{"x": 2}), func() ipc.Response {
			calls++
			return ipc.Response{OK: true}
		})
	if status != replayArgsConflict || calls != 0 || got.OK {
		t.Fatalf("conflicting replay: status=%v calls=%d response=%#v", status, calls, got)
	}
}

func TestReplayCacheCapacityEviction(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	c.maxSize = 3
	digest := mustArgsDigest(t, nil)
	for _, reqID := range []string{"req-001", "req-002", "req-003"} {
		_, status := c.execute(context.Background(), "principal-a", ipc.ActionPing, reqID, digest, func() ipc.Response {
			return ipc.Response{RequestID: reqID, OK: true}
		})
		if status != replayExecuted {
			t.Fatalf("execute %s status = %v", reqID, status)
		}
	}

	_, status := c.execute(context.Background(), "principal-a", ipc.ActionPing, "req-001", digest, func() ipc.Response {
		return ipc.Response{}
	})
	if status != replayHit {
		t.Fatalf("accessing req-001 status = %v", status)
	}

	_, status = c.execute(context.Background(), "principal-a", ipc.ActionPing, "req-004", digest, func() ipc.Response {
		return ipc.Response{}
	})
	if status != replayExecuted {
		t.Fatalf("execute req-004 status = %v", status)
	}

	var evictedCalls int
	_, status = c.execute(context.Background(), "principal-a", ipc.ActionPing, "req-001", digest, func() ipc.Response {
		evictedCalls++
		return ipc.Response{}
	})
	if status != replayExecuted || evictedCalls != 1 {
		t.Fatalf("oldest entry was not evicted: status=%v calls=%d", status, evictedCalls)
	}
	if len(c.entries) != c.maxSize {
		t.Fatalf("cache size = %d, want %d", len(c.entries), c.maxSize)
	}
}

func TestReplayCacheKeepsInFlightEntriesInEvictionOrder(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	c.maxSize = 2
	digest := mustArgsDigest(t, nil)
	started := make(chan struct{})
	release := make(chan struct{})
	var releaseOnce sync.Once
	releaseFirst := func() { releaseOnce.Do(func() { close(release) }) }
	firstDone := make(chan struct{})
	go func() {
		defer close(firstDone)
		_, _ = c.execute(context.Background(), "principal-a", ipc.ActionPing, "req-001", digest, func() ipc.Response {
			close(started)
			<-release
			return ipc.Response{RequestID: "req-001", OK: true}
		})
	}()
	<-started
	defer func() {
		releaseFirst()
		<-firstDone
	}()

	for _, reqID := range []string{"req-002", "req-003"} {
		_, status := c.execute(context.Background(), "principal-a", ipc.ActionPing, reqID, digest, func() ipc.Response {
			return ipc.Response{RequestID: reqID, OK: true}
		})
		if status != replayExecuted {
			t.Fatalf("execute %s status = %v", reqID, status)
		}
	}
	releaseFirst()
	<-firstDone

	_, status := c.execute(context.Background(), "principal-a", ipc.ActionPing, "req-004", digest, func() ipc.Response {
		return ipc.Response{RequestID: "req-004", OK: true}
	})
	if status != replayExecuted {
		t.Fatalf("execute req-004 status = %v", status)
	}
	var calls int
	_, status = c.execute(context.Background(), "principal-a", ipc.ActionPing, "req-001", digest, func() ipc.Response {
		calls++
		return ipc.Response{}
	})
	if status != replayExecuted || calls != 1 {
		t.Fatalf("oldest completed entry was not evicted: status=%v calls=%d", status, calls)
	}
}

func TestReplayCacheRejectsWhenAllEntriesInFlight(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	c.maxSize = 1
	digest := mustArgsDigest(t, nil)
	started := make(chan struct{})
	release := make(chan struct{})
	firstDone := make(chan struct{})
	go func() {
		defer close(firstDone)
		_, _ = c.execute(context.Background(), "principal-a", ipc.ActionInjectTap, "req-001", digest, func() ipc.Response {
			close(started)
			<-release
			return ipc.Response{OK: true}
		})
	}()
	<-started
	defer func() {
		close(release)
		<-firstDone
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel()
	var calls int
	_, status := c.execute(ctx, "principal-a", ipc.ActionInjectTap, "req-002", digest, func() ipc.Response {
		calls++
		return ipc.Response{}
	})
	if status != replayCacheFull || calls != 0 {
		t.Fatalf("full in-flight cache: status=%v calls=%d", status, calls)
	}
}

func TestReplayCacheExpiresEntries(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	c.maxAge = 20 * time.Millisecond
	digest := mustArgsDigest(t, nil)
	_, status := c.execute(context.Background(), "principal-a", ipc.ActionPing, "req-001", digest, func() ipc.Response {
		return ipc.Response{OK: true}
	})
	if status != replayExecuted {
		t.Fatalf("first execution status = %v", status)
	}

	time.Sleep(40 * time.Millisecond)
	var calls int
	_, status = c.execute(context.Background(), "principal-a", ipc.ActionPing, "req-001", digest, func() ipc.Response {
		calls++
		return ipc.Response{OK: true}
	})
	if status != replayExecuted || calls != 1 {
		t.Fatalf("expired entry was reused: status=%v calls=%d", status, calls)
	}
}

func TestReplayCacheSuppressesConcurrentDuplicates(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	digest := mustArgsDigest(t, nil)
	started := make(chan struct{})
	release := make(chan struct{})
	firstDone := make(chan ipc.Response, 1)
	var calls atomic.Int32
	go func() {
		resp, _ := c.execute(context.Background(), "principal-a", ipc.ActionInjectTap, "req-001", digest, func() ipc.Response {
			calls.Add(1)
			close(started)
			<-release
			return ipc.Response{RequestID: "req-001", OK: true, Message: "first"}
		})
		firstDone <- resp
	}()
	<-started

	ctx, cancel := context.WithCancel(context.Background())
	secondStarted := make(chan struct{})
	secondDone := make(chan struct {
		response ipc.Response
		status   replayStatus
	}, 1)
	go func() {
		close(secondStarted)
		resp, status := c.execute(ctx, "principal-a", ipc.ActionInjectTap, "req-001", digest, func() ipc.Response {
			calls.Add(1)
			return ipc.Response{Message: "duplicate"}
		})
		secondDone <- struct {
			response ipc.Response
			status   replayStatus
		}{resp, status}
	}()
	<-secondStarted
	cancel()
	select {
	case result := <-secondDone:
		if result.status != replayContextCanceled {
			t.Fatalf("canceled duplicate status = %v", result.status)
		}
	case <-time.After(time.Second):
		close(release)
		t.Fatal("canceled duplicate did not return")
	}

	close(release)
	first := <-firstDone
	if calls.Load() != 1 || first.Message != "first" {
		t.Fatalf("concurrent duplicate executed twice: calls=%d response=%#v", calls.Load(), first)
	}

	got, status := c.execute(context.Background(), "principal-a", ipc.ActionInjectTap, "req-001", digest, func() ipc.Response {
		calls.Add(1)
		return ipc.Response{}
	})
	if status != replayHit || got.Message != "first" || calls.Load() != 1 {
		t.Fatalf("completed duplicate replay: status=%v calls=%d response=%#v", status, calls.Load(), got)
	}
}

func TestReplayCachePreservesResponseAndDefensivelyCopiesData(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	digest := mustArgsDigest(t, nil)
	resp := ipc.Response{
		RequestID: "req-cap-001",
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   "capabilities returned",
		Data:      map[string]interface{}{"result": "original"},
	}
	_, status := c.execute(context.Background(), "principal-a", ipc.ActionGetCapabilities, "req-cap-001", digest, func() ipc.Response {
		return resp
	})
	if status != replayExecuted {
		t.Fatalf("first execution status = %v", status)
	}
	resp.Data["result"] = "mutated"

	got, status := c.execute(context.Background(), "principal-a", ipc.ActionGetCapabilities, "req-cap-001", digest, func() ipc.Response {
		return ipc.Response{}
	})
	if status != replayHit || got.RequestID != resp.RequestID || !got.OK || got.Code != ipc.CodeOK ||
		got.Message != resp.Message || !reflect.DeepEqual(got.Data, map[string]interface{}{"result": "original"}) {
		t.Fatalf("cached response mismatch: status=%v response=%#v", status, got)
	}
	got.Data["result"] = "changed by caller"

	got, status = c.execute(context.Background(), "principal-a", ipc.ActionGetCapabilities, "req-cap-001", digest, func() ipc.Response {
		return ipc.Response{}
	})
	if status != replayHit || got.Data["result"] != "original" {
		t.Fatalf("caller mutated cached response: status=%v response=%#v", status, got)
	}
}

func TestReplayCacheConcurrentIndependentKeys(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	digest := mustArgsDigest(t, nil)
	done := make(chan struct{}, 50)
	for i := 0; i < 50; i++ {
		go func() {
			defer func() { done <- struct{}{} }()
			_, _ = c.execute(context.Background(), "principal-a", ipc.ActionPing, randomHex(8), digest, func() ipc.Response {
				return ipc.Response{OK: true}
			})
		}()
	}
	for i := 0; i < 50; i++ {
		<-done
	}
	if len(c.entries) > c.maxSize {
		t.Fatalf("cache size = %d, limit = %d", len(c.entries), c.maxSize)
	}
}

func TestReplayCacheWaiterReturnsClaimedEntryAfterCapacityEviction(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	c.maxSize = 1
	digest := mustArgsDigest(t, nil)
	started := make(chan struct{})
	release := make(chan struct{})
	var releaseOnce sync.Once
	releaseFirst := func() { releaseOnce.Do(func() { close(release) }) }
	t.Cleanup(releaseFirst)

	firstDone := make(chan ipc.Response, 1)
	var calls atomic.Int32
	go func() {
		response, _ := c.execute(context.Background(), "principal-a", ipc.ActionPing, "req-claimed", digest, func() ipc.Response {
			calls.Add(1)
			close(started)
			<-release
			return ipc.Response{RequestID: "req-claimed", OK: true, Message: "claimed response"}
		})
		firstDone <- response
	}()
	<-started

	waitCtx := newReplayWaitGateContext()
	var proceedOnce sync.Once
	proceed := func() { proceedOnce.Do(func() { close(waitCtx.proceed) }) }
	t.Cleanup(proceed)
	duplicateDone := make(chan struct {
		response ipc.Response
		status   replayStatus
	}, 1)
	go func() {
		response, status := c.execute(waitCtx, "principal-a", ipc.ActionPing, "req-claimed", digest, func() ipc.Response {
			calls.Add(1)
			return ipc.Response{RequestID: "req-claimed", OK: true, Message: "duplicate executed"}
		})
		duplicateDone <- struct {
			response ipc.Response
			status   replayStatus
		}{response, status}
	}()
	select {
	case <-waitCtx.waiting:
	case <-time.After(time.Second):
		t.Fatal("duplicate did not wait on the claimed entry")
	}

	releaseFirst()
	first := <-firstDone
	if first.Message != "claimed response" {
		t.Fatalf("first response = %#v", first)
	}

	_, status := c.execute(context.Background(), "principal-a", ipc.ActionPing, "req-evictor", digest, func() ipc.Response {
		return ipc.Response{RequestID: "req-evictor", OK: true}
	})
	if status != replayExecuted {
		t.Fatalf("capacity evictor status = %v", status)
	}

	proceed()
	select {
	case duplicate := <-duplicateDone:
		if duplicate.status != replayHit || duplicate.response.Message != "claimed response" {
			t.Fatalf("waiter lost its claimed entry after eviction: status=%v response=%#v", duplicate.status, duplicate.response)
		}
		if calls.Load() != 1 {
			t.Fatalf("non-idempotent action executed %d times, want 1", calls.Load())
		}
	case <-time.After(time.Second):
		t.Fatal("duplicate did not return after its claimed entry was evicted")
	}
}

func TestReplayCacheDoesNotEvictNonIdempotentWithinTTL(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	c.maxSize = 1
	digest := mustArgsDigest(t, nil)
	calls := 0
	_, status := c.execute(context.Background(), "principal-a", ipc.ActionInjectTap, "req-tap", digest, func() ipc.Response {
		calls++
		return ipc.Response{RequestID: "req-tap", OK: true}
	})
	if status != replayExecuted {
		t.Fatalf("first execute status = %v", status)
	}

	_, status = c.execute(context.Background(), "principal-a", ipc.ActionPing, "req-ping", digest, func() ipc.Response {
		calls++
		return ipc.Response{RequestID: "req-ping", OK: true}
	})
	if status != replayCacheFull || calls != 1 {
		t.Fatalf("non-idempotent entry was evicted: status=%v calls=%d", status, calls)
	}

	_, status = c.execute(context.Background(), "principal-a", ipc.ActionInjectTap, "req-tap", digest, func() ipc.Response {
		calls++
		return ipc.Response{RequestID: "req-tap", OK: true}
	})
	if status != replayHit || calls != 1 {
		t.Fatalf("non-idempotent replay status=%v calls=%d", status, calls)
	}
}

func TestReplayCacheAbortWakesWaiterWithExplicitStatus(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	digest := mustArgsDigest(t, nil)
	started := make(chan struct{})
	release := make(chan struct{})
	var releaseOnce sync.Once
	releaseFirst := func() { releaseOnce.Do(func() { close(release) }) }
	t.Cleanup(releaseFirst)

	ownerDone := make(chan struct{})
	var calls atomic.Int32
	go func() {
		defer close(ownerDone)
		defer func() { _ = recover() }()
		_, _ = c.execute(context.Background(), "principal-a", ipc.ActionInjectTap, "req-aborted", digest, func() ipc.Response {
			calls.Add(1)
			close(started)
			<-release
			panic("execution aborted")
		})
	}()
	<-started

	waitCtx := newReplayWaitGateContext()
	var proceedOnce sync.Once
	proceed := func() { proceedOnce.Do(func() { close(waitCtx.proceed) }) }
	t.Cleanup(proceed)
	duplicateDone := make(chan struct {
		response ipc.Response
		status   replayStatus
	}, 1)
	go func() {
		response, status := c.execute(waitCtx, "principal-a", ipc.ActionInjectTap, "req-aborted", digest, func() ipc.Response {
			calls.Add(1)
			return ipc.Response{RequestID: "req-aborted", OK: true, Message: "duplicate executed"}
		})
		duplicateDone <- struct {
			response ipc.Response
			status   replayStatus
		}{response, status}
	}()
	select {
	case <-waitCtx.waiting:
	case <-time.After(time.Second):
		t.Fatal("duplicate did not wait on the claimed entry")
	}

	releaseFirst()
	<-ownerDone
	proceed()
	select {
	case duplicate := <-duplicateDone:
		switch duplicate.status {
		case replayExecuted, replayHit, replayArgsConflict, replayCacheFull, replayContextCanceled:
			t.Fatalf("aborted entry reused an existing status instead of an explicit abort state: status=%v response=%#v", duplicate.status, duplicate.response)
		}
		if calls.Load() != 1 {
			t.Fatalf("aborted non-idempotent action executed %d times, want 1", calls.Load())
		}
	case <-time.After(time.Second):
		t.Fatal("duplicate did not return after owner abort")
	}
}
