package server

import (
	"reflect"
	"slices"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"clawdroid/runtime/internal/audit"
	"clawdroid/runtime/internal/config"
	"clawdroid/runtime/internal/ipc"
	"clawdroid/runtime/internal/task"
)

func TestTraceSnapshotReturnsCopy(t *testing.T) {
	t.Parallel()

	sess := newSession()
	sess.transition(StateSocketConnected)

	snapshot := sess.traceSnapshot()
	snapshot[0] = "Mutated"

	if reflect.DeepEqual(snapshot, sess.traceSnapshot()) {
		t.Fatal("expected trace snapshot to be isolated from session trace")
	}
	if trace := sess.traceSnapshot(); trace[0] != string(StateDisconnected) {
		t.Fatalf("unexpected trace mutation: got %q", trace[0])
	}
}

func TestSessionExpiredUsesAuthenticatedAt(t *testing.T) {
	t.Parallel()

	sess := newSession()
	sess.mu.Lock()
	sess.authenticatedAt = time.Now().Add(-3 * time.Second)
	sess.mu.Unlock()

	if !sess.sessionExpired(1) {
		t.Fatal("expected session to expire when authenticatedAt is older than ttl")
	}
	if sess.sessionExpired(0) {
		t.Fatal("expected zero ttl to disable expiration")
	}
}

func TestHandleRequestRejectsInvalidTimestamp(t *testing.T) {
	t.Parallel()

	srv := New(config.Default(), audit.NewLogger())
	t.Cleanup(srv.taskScheduler.Close)
	sess := newSession()
	sess.setPeerCredentials(peerCredentials{PID: 1, UID: 12345, Method: "test"})
	sess.completeAuthentication("com.example.app", "sha256:signature", time.Now().Unix())
	srv.finalizeCapabilityState(sess)

	now := time.Now().Unix()
	for _, tc := range []struct {
		name      string
		timestamp int64
	}{
		{name: "missing", timestamp: 0},
		{name: "too old", timestamp: now - srv.cfg.TimestampSkewSec - 1},
		{name: "in the future", timestamp: now + srv.cfg.TimestampSkewSec + 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			response := srv.handleRequest(sess, ipc.Request{
				RequestID: "timestamp-" + tc.name,
				Timestamp: tc.timestamp,
				Action:    ipc.ActionPing,
			})
			if response.OK || response.Code != ipc.CodeErrInvalidRequest {
				t.Fatalf("invalid timestamp was accepted: %#v", response)
			}
		})
	}

	valid := srv.handleRequest(sess, ipc.Request{
		RequestID: "timestamp-valid",
		Timestamp: time.Now().Unix(),
		Action:    ipc.ActionPing,
	})
	if !valid.OK {
		t.Fatalf("current timestamp was rejected: %#v", valid)
	}
}

func TestHandleRequestInitializesCapabilitiesBeforePing(t *testing.T) {
	t.Parallel()

	cfg := config.Default()
	cfg.ScreenshotEnabled = true
	srv := New(cfg, audit.NewLogger())
	t.Cleanup(srv.taskScheduler.Close)
	sess := newSession()
	sess.completeAuthentication("com.example.app", "sha256:signature", time.Now().Unix())

	response := srv.handleRequest(sess, ipc.Request{
		RequestID: "ping-before-capabilities",
		Timestamp: time.Now().Unix(),
		Action:    ipc.ActionPing,
	})
	if !response.OK {
		t.Fatalf("ping before capability sync was rejected: %#v", response)
	}
	if !slices.Contains(sess.capabilitiesSnapshot(), ipc.CapabilitySystemPing) {
		t.Fatalf("ping capability was not initialized: %#v", sess.capabilitiesSnapshot())
	}
	if state := sess.currentState(); state != StateReady {
		t.Fatalf("unexpected session state after ping: got %q want %q", state, StateReady)
	}
}

func TestAuthenticatedSignatureDigestIsCanonical(t *testing.T) {
	t.Parallel()

	sess := newSession()
	sess.completeAuthentication("com.example.app", "  SHA256:SIGNATURE  ", time.Now().Unix())
	if got := sess.snapshot().signatureDigest; got != "sha256:signature" {
		t.Fatalf("authenticated signature digest = %q, want canonical form", got)
	}
}

func TestGlobalRateLimitSharesPrincipalAcrossSessions(t *testing.T) {
	t.Parallel()

	cfg := config.Default()
	cfg.RateLimitPerMinute = 2
	srv := New(cfg, audit.NewLogger())
	t.Cleanup(srv.taskScheduler.Close)
	first := newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature")
	second := newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature")
	reconnected := newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature")

	if !srv.allowPrincipalRequest(first) || !srv.allowPrincipalRequest(second) {
		t.Fatal("requests below the principal-wide limit were rejected")
	}
	if srv.allowPrincipalRequest(reconnected) {
		t.Fatal("reconnecting with the same principal bypassed the global rate limit")
	}
}

func TestPrincipalRateLimiterUsesStablePrincipalComponents(t *testing.T) {
	t.Parallel()

	limiter := newPrincipalRateLimiter(1, 16)
	now := time.Now()
	base := task.Principal{PackageName: "com.example.app", UID: 12345, SignatureDigest: "sha256:signature"}
	if !limiter.allow(base, now) {
		t.Fatal("first principal request was rejected")
	}
	if limiter.allow(base, now) {
		t.Fatal("second request from the same principal was allowed")
	}
	for _, changed := range []task.Principal{
		{PackageName: "com.example.other", UID: base.UID, SignatureDigest: base.SignatureDigest},
		{PackageName: base.PackageName, UID: base.UID + 1, SignatureDigest: base.SignatureDigest},
		{PackageName: base.PackageName, UID: base.UID, SignatureDigest: "sha256:other"},
	} {
		if !limiter.allow(changed, now) {
			t.Fatalf("distinct principal was not isolated: %#v", changed)
		}
	}
}

func TestPrincipalRateLimiterBoundsAndCleansPrincipalMap(t *testing.T) {
	t.Parallel()

	limiter := newPrincipalRateLimiter(1, 2)
	now := time.Now()
	principals := []task.Principal{
		{PackageName: "com.example.a", UID: 1, SignatureDigest: "sha256:a"},
		{PackageName: "com.example.b", UID: 2, SignatureDigest: "sha256:b"},
		{PackageName: "com.example.c", UID: 3, SignatureDigest: "sha256:c"},
	}
	if !limiter.allow(principals[0], now) || !limiter.allow(principals[1], now) {
		t.Fatal("failed to populate bounded principal map")
	}
	if limiter.allow(principals[2], now) {
		t.Fatal("new principal bypassed a full principal map")
	}
	if len(limiter.entries) > limiter.maxPrincipals {
		t.Fatalf("principal map exceeded bound: %d > %d", len(limiter.entries), limiter.maxPrincipals)
	}

	limiter.mu.Lock()
	for _, window := range limiter.entries {
		for i := range window.requestTimes {
			window.requestTimes[i] = now.Add(-2 * time.Minute)
		}
	}
	limiter.mu.Unlock()
	if !limiter.allow(principals[2], now) {
		t.Fatal("expired principal windows were not cleaned")
	}
	if len(limiter.entries) != 1 {
		t.Fatalf("cleanup left %d principals, want 1", len(limiter.entries))
	}
}

func TestPrincipalRateLimiterConcurrentLimit(t *testing.T) {
	t.Parallel()

	limiter := newPrincipalRateLimiter(16, 16)
	principal := task.Principal{PackageName: "com.example.app", UID: 12345, SignatureDigest: "sha256:signature"}
	var allowed atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if limiter.allow(principal, time.Now()) {
				allowed.Add(1)
			}
		}()
	}
	wg.Wait()
	if allowed.Load() != 16 {
		t.Fatalf("allowed %d concurrent requests, want 16", allowed.Load())
	}
}

func TestAllowRequestPrunesExpiredEntries(t *testing.T) {
	t.Parallel()

	sess := newSession()
	sess.mu.Lock()
	sess.requestTimes = []time.Time{
		time.Now().Add(-2 * time.Minute),
		time.Now().Add(-30 * time.Second),
	}
	sess.mu.Unlock()

	if !sess.allowRequest(2) {
		t.Fatal("expected request to pass after pruning old entries")
	}
	sess.mu.RLock()
	requestCount := len(sess.requestTimes)
	sess.mu.RUnlock()
	if requestCount != 2 {
		t.Fatalf("unexpected request history length after prune: got %d want %d", requestCount, 2)
	}
}

func TestAllowRequestConcurrentLimit(t *testing.T) {
	t.Parallel()

	const (
		limit = 16
		calls = 64
	)
	sess := newSession()
	start := make(chan struct{})
	var allowed atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < calls; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			if sess.allowRequest(limit) {
				allowed.Add(1)
			}
		}()
	}
	close(start)
	wg.Wait()

	if got := allowed.Load(); got != limit {
		t.Fatalf("concurrent requests admitted: got %d, want %d", got, limit)
	}
}

func TestSessionConcurrentSnapshotsAndTransitions(t *testing.T) {
	t.Parallel()

	srv := &Server{cfg: config.Config{ScreenshotEnabled: true, SessionTTLSec: 60}}
	sess := newSession()
	start := make(chan struct{})
	var wg sync.WaitGroup

	wg.Add(4)
	go func() {
		defer wg.Done()
		<-start
		for i := 0; i < 300; i++ {
			sess.transition([]SessionState{StateSocketConnected, StatePeerVerified, StateReady, StateClosed}[i%4])
		}
	}()
	go func() {
		defer wg.Done()
		<-start
		for i := 0; i < 300; i++ {
			srv.finalizeCapabilityState(sess)
		}
	}()
	go func() {
		defer wg.Done()
		<-start
		for i := 0; i < 300; i++ {
			sess.setAuthFailure(1003, "concurrent auth failure")
			sess.completeAuthentication("com.example.concurrent", "sha256:concurrent", time.Now().Unix())
		}
	}()
	go func() {
		defer wg.Done()
		<-start
		for i := 0; i < 300; i++ {
			sess.allowRequest(1_000_000)
		}
	}()

	wg.Add(4)
	go func() {
		defer wg.Done()
		<-start
		for i := 0; i < 300; i++ {
			_ = sess.traceSnapshot()
		}
	}()
	go func() {
		defer wg.Done()
		<-start
		for i := 0; i < 300; i++ {
			_ = srv.sessionData(sess)
		}
	}()
	go func() {
		defer wg.Done()
		<-start
		for i := 0; i < 300; i++ {
			_ = sess.sessionExpired(srv.cfg.SessionTTLSec)
			_ = sess.lastAuthErrorCode(srv)
			_ = sess.lastAuthErrorMessage(srv)
		}
	}()
	go func() {
		defer wg.Done()
		<-start
		for i := 0; i < 300; i++ {
			_ = srv.handleRequest(sess, ipc.Request{Action: ipc.ActionPing})
		}
	}()

	close(start)
	wg.Wait()
}

func TestFinalizeCapabilityStateReadyWhenAnyPrivilegedCapabilityEnabled(t *testing.T) {
	t.Parallel()

	srv := &Server{cfg: config.Config{ScreenshotEnabled: true}}
	sess := newSession()

	srv.finalizeCapabilityState(sess)

	snapshot := sess.snapshot()
	if snapshot.state != StateReady {
		t.Fatalf("unexpected session state: got %q want %q", snapshot.state, StateReady)
	}
	if snapshot.degradedReason != "" {
		t.Fatalf("expected degraded reason to be cleared, got %q", snapshot.degradedReason)
	}
	wantTrace := []string{
		string(StateDisconnected),
		string(StateCapabilitySynced),
		string(StateReady),
	}
	if !reflect.DeepEqual(sess.traceSnapshot(), wantTrace) {
		t.Fatalf("unexpected state trace: got %#v want %#v", sess.traceSnapshot(), wantTrace)
	}
}

func TestFinalizeCapabilityStateDegradedWithoutPrivilegedCapabilities(t *testing.T) {
	t.Parallel()

	srv := &Server{cfg: config.Config{}}
	sess := newSession()

	srv.finalizeCapabilityState(sess)

	snapshot := sess.snapshot()
	if snapshot.state != StateDegraded {
		t.Fatalf("unexpected session state: got %q want %q", snapshot.state, StateDegraded)
	}
	if snapshot.degradedReason == "" {
		t.Fatal("expected degraded reason to be populated")
	}
}
