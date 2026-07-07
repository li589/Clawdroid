package server

import (
	"reflect"
	"testing"
	"time"

	"clawdroid/runtime/internal/config"
)

func TestTraceSnapshotReturnsCopy(t *testing.T) {
	t.Parallel()

	sess := newSession()
	sess.transition(StateSocketConnected)

	snapshot := sess.traceSnapshot()
	snapshot[0] = "Mutated"

	if reflect.DeepEqual(snapshot, sess.trace) {
		t.Fatal("expected trace snapshot to be isolated from session trace")
	}
	if sess.trace[0] != string(StateDisconnected) {
		t.Fatalf("unexpected trace mutation: got %q", sess.trace[0])
	}
}

func TestSessionExpiredUsesAuthenticatedAt(t *testing.T) {
	t.Parallel()

	sess := newSession()
	sess.authenticatedAt = time.Now().Add(-3 * time.Second)

	if !sess.sessionExpired(1) {
		t.Fatal("expected session to expire when authenticatedAt is older than ttl")
	}
	if sess.sessionExpired(0) {
		t.Fatal("expected zero ttl to disable expiration")
	}
}

func TestAllowRequestPrunesExpiredEntries(t *testing.T) {
	t.Parallel()

	sess := newSession()
	sess.requestTimes = []time.Time{
		time.Now().Add(-2 * time.Minute),
		time.Now().Add(-30 * time.Second),
	}

	if !sess.allowRequest(2) {
		t.Fatal("expected request to pass after pruning old entries")
	}
	if len(sess.requestTimes) != 2 {
		t.Fatalf("unexpected request history length after prune: got %d want %d", len(sess.requestTimes), 2)
	}
}

func TestFinalizeCapabilityStateReadyWhenAnyPrivilegedCapabilityEnabled(t *testing.T) {
	t.Parallel()

	srv := &Server{cfg: config.Config{ScreenshotEnabled: true}}
	sess := newSession()

	srv.finalizeCapabilityState(sess)

	if sess.state != StateReady {
		t.Fatalf("unexpected session state: got %q want %q", sess.state, StateReady)
	}
	if sess.degradedReason != "" {
		t.Fatalf("expected degraded reason to be cleared, got %q", sess.degradedReason)
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

	if sess.state != StateDegraded {
		t.Fatalf("unexpected session state: got %q want %q", sess.state, StateDegraded)
	}
	if sess.degradedReason == "" {
		t.Fatal("expected degraded reason to be populated")
	}
}
