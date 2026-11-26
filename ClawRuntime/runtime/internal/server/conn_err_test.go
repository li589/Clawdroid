package server

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"clawdroid/runtime/internal/audit"
	"clawdroid/runtime/internal/config"
	"clawdroid/runtime/internal/ipc"
)

func TestConnectionQuotaAcquireRelease(t *testing.T) {
	t.Parallel()

	quota := newConnectionQuota(2)
	if !quota.acquire() || !quota.acquire() {
		t.Fatal("connections below the quota were rejected")
	}
	if quota.acquire() {
		t.Fatal("connection above the quota was accepted")
	}
	quota.release()
	if !quota.acquire() {
		t.Fatal("released connection slot was not reusable")
	}
	quota.release()
	quota.release()
	if !quota.acquire() {
		t.Fatal("all released slots should be available")
	}
}

func TestConnectionQuotaConcurrentAcquire(t *testing.T) {
	t.Parallel()

	const limit = 8
	quota := newConnectionQuota(limit)
	var acquired atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if quota.acquire() {
				acquired.Add(1)
			}
		}()
	}
	wg.Wait()
	if acquired.Load() != limit {
		t.Fatalf("acquired %d slots, want %d", acquired.Load(), limit)
	}
	for i := int32(0); i < acquired.Load(); i++ {
		quota.release()
	}
	if !quota.acquire() {
		t.Fatal("quota did not recover after every holder released")
	}
}

func TestConnectionQuotaRejectsNonPositiveLimit(t *testing.T) {
	t.Parallel()

	for _, limit := range []int{0, -1} {
		if newConnectionQuota(limit).acquire() {
			t.Fatalf("quota with limit %d accepted a connection", limit)
		}
	}
}

func startSecurityRequestLoop(t *testing.T, srv *Server, sess *session) (net.Conn, *bufio.Reader) {
	t.Helper()
	client, server := net.Pipe()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- srv.handleRequestLoop(ctx, sess, server, bufio.NewReader(server), bufio.NewWriter(server))
	}()
	t.Cleanup(func() {
		cancel()
		_ = client.Close()
		_ = server.Close()
		select {
		case <-done:
		case <-time.After(time.Second):
			t.Error("request loop did not stop during cleanup")
		}
	})
	return client, bufio.NewReader(client)
}

func writeSecurityRequest(t *testing.T, conn net.Conn, req ipc.Request) {
	t.Helper()
	payload, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("marshal request: %v", err)
	}
	_ = conn.SetWriteDeadline(time.Now().Add(time.Second))
	if err := writeJSONFrame(bufio.NewWriter(conn), payload); err != nil {
		t.Fatalf("write request: %v", err)
	}
}

func readSecurityResponse(t *testing.T, reader *bufio.Reader) ipc.Response {
	t.Helper()
	payload, err := readJSONFrame(reader, 2<<20)
	if err != nil {
		t.Fatalf("read response: %v", err)
	}
	var response ipc.Response
	if err := json.Unmarshal(payload, &response); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	return response
}

func securityRequest(srv *Server, requestID, action string, args map[string]interface{}) ipc.Request {
	return ipc.Request{
		Version:   srv.cfg.ProtocolVersion,
		RequestID: requestID,
		Timestamp: time.Now().Unix(),
		Action:    action,
		Args:      args,
	}
}

func TestRequestLoopRateLimitSurvivesReconnect(t *testing.T) {
	t.Parallel()

	cfg := config.Default()
	cfg.RateLimitPerMinute = 2
	srv := New(cfg, audit.NewLogger())
	t.Cleanup(srv.taskScheduler.Close)
	firstConn, firstReader := startSecurityRequestLoop(t, srv, newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature"))
	secondConn, secondReader := startSecurityRequestLoop(t, srv, newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature"))
	reconnected := newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature")
	thirdConn, thirdReader := startSecurityRequestLoop(t, srv, reconnected)

	for i, endpoint := range []struct {
		conn   net.Conn
		reader *bufio.Reader
	}{{firstConn, firstReader}, {secondConn, secondReader}} {
		writeSecurityRequest(t, endpoint.conn, securityRequest(srv, "rate-"+string(rune('1'+i)), ipc.ActionPing, nil))
		if response := readSecurityResponse(t, endpoint.reader); !response.OK {
			t.Fatalf("request below global limit was rejected: %#v", response)
		}
	}
	writeSecurityRequest(t, thirdConn, securityRequest(srv, "rate-3", ipc.ActionPing, nil))
	if response := readSecurityResponse(t, thirdReader); response.OK || response.Code != ipc.CodeErrRateLimited {
		t.Fatalf("reconnected principal bypassed global rate limit: %#v", response)
	}
}

func TestRequestLoopRateLimitDoesNotCountInvalidTimestamps(t *testing.T) {
	t.Parallel()

	cfg := config.Default()
	cfg.RateLimitPerMinute = 1
	srv := New(cfg, audit.NewLogger())
	t.Cleanup(srv.taskScheduler.Close)
	conn, reader := startSecurityRequestLoop(t, srv, newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature"))
	invalid := securityRequest(srv, "invalid-does-not-count", ipc.ActionPing, nil)
	invalid.Timestamp = 0
	writeSecurityRequest(t, conn, invalid)
	if response := readSecurityResponse(t, reader); response.OK || response.Code != ipc.CodeErrInvalidRequest {
		t.Fatalf("missing timestamp response = %#v", response)
	}

	writeSecurityRequest(t, conn, securityRequest(srv, "valid-after-invalid", ipc.ActionPing, nil))
	if response := readSecurityResponse(t, reader); !response.OK {
		t.Fatalf("invalid timestamp consumed rate-limit quota: %#v", response)
	}

	writeSecurityRequest(t, conn, securityRequest(srv, "over-limit", ipc.ActionPing, nil))
	if response := readSecurityResponse(t, reader); response.OK || response.Code != ipc.CodeErrRateLimited {
		t.Fatalf("valid request did not consume the configured quota: %#v", response)
	}
}

func TestRequestLoopReplaysNonIdempotentActionAcrossConnections(t *testing.T) {
	t.Parallel()

	srv := New(config.Default(), audit.NewLogger())
	t.Cleanup(srv.taskScheduler.Close)
	firstSession := newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature")
	secondSession := newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature")
	firstConn, firstReader := startSecurityRequestLoop(t, srv, firstSession)
	secondConn, secondReader := startSecurityRequestLoop(t, srv, secondSession)
	args := map[string]interface{}{
		"focus_json": `{"package_name":"com.example.focus","activity_class":"MainActivity"}`,
	}
	req := securityRequest(srv, "non-idempotent-replay", ipc.ActionReportXposedFocus, args)
	writeSecurityRequest(t, firstConn, req)
	first := readSecurityResponse(t, firstReader)
	if !first.OK {
		t.Fatalf("first focus report failed: %#v", first)
	}

	time.Sleep(5 * time.Millisecond)
	writeSecurityRequest(t, secondConn, req)
	second := readSecurityResponse(t, secondReader)
	if !second.OK {
		t.Fatalf("cached focus report failed: %#v", second)
	}
	if second.Data["session_id"] != secondSession.id {
		t.Fatalf("replay returned stale session metadata: %#v", second.Data)
	}
	if second.Data["updated_at_epoch_ms"] != first.Data["updated_at_epoch_ms"] {
		t.Fatalf("non-idempotent action executed again: first=%#v second=%#v", first.Data, second.Data)
	}
	if second.Data["package_name"] != "com.example.focus" {
		t.Fatalf("replay lost action response data to session metadata: %#v", second.Data)
	}
}

func TestRequestLoopRejectsReplayArgsConflict(t *testing.T) {
	t.Parallel()

	srv := New(config.Default(), audit.NewLogger())
	t.Cleanup(srv.taskScheduler.Close)
	firstConn, firstReader := startSecurityRequestLoop(t, srv, newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature"))
	secondConn, secondReader := startSecurityRequestLoop(t, srv, newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature"))
	writeSecurityRequest(t, firstConn, securityRequest(srv, "args-conflict", ipc.ActionReportXposedFocus, map[string]interface{}{
		"focus_json": `{"package_name":"com.example.first","activity_class":"FirstActivity"}`,
	}))
	if response := readSecurityResponse(t, firstReader); !response.OK {
		t.Fatalf("first focus report failed: %#v", response)
	}

	writeSecurityRequest(t, secondConn, securityRequest(srv, "args-conflict", ipc.ActionReportXposedFocus, map[string]interface{}{
		"focus_json": `{"package_name":"com.example.second","activity_class":"SecondActivity"}`,
	}))
	response := readSecurityResponse(t, secondReader)
	if response.OK || response.Code != ipc.CodeErrInvalidRequest {
		t.Fatalf("conflicting replay was accepted: %#v", response)
	}
	if got := srv.captureXposedFocusState()["activity_class"]; got != "FirstActivity" {
		t.Fatalf("conflicting replay mutated focus state: %#v", got)
	}
}

func TestRequestLoopValidatesTimestampBeforeReplay(t *testing.T) {
	t.Parallel()

	srv := New(config.Default(), audit.NewLogger())
	t.Cleanup(srv.taskScheduler.Close)
	conn, reader := startSecurityRequestLoop(t, srv, newLifecycleSession(srv, "com.example.app", 12345, "sha256:signature"))
	valid := securityRequest(srv, "timestamp-replay", ipc.ActionPing, nil)
	writeSecurityRequest(t, conn, valid)
	if response := readSecurityResponse(t, reader); !response.OK {
		t.Fatalf("valid request failed: %#v", response)
	}

	now := time.Now().Unix()
	for _, tc := range []struct {
		name      string
		timestamp int64
	}{
		{name: "missing", timestamp: 0},
		{name: "stale", timestamp: now - srv.cfg.TimestampSkewSec - 1},
		{name: "future", timestamp: now + srv.cfg.TimestampSkewSec + 1},
	} {
		t.Run(tc.name, func(t *testing.T) {
			req := valid
			req.RequestID = "timestamp-" + tc.name
			req.Timestamp = tc.timestamp
			writeSecurityRequest(t, conn, req)
			if response := readSecurityResponse(t, reader); response.OK || response.Code != ipc.CodeErrInvalidRequest {
				t.Fatalf("invalid timestamp was accepted: %#v", response)
			}
		})
	}

	staleReplay := valid
	staleReplay.Timestamp = now - srv.cfg.TimestampSkewSec - 1
	writeSecurityRequest(t, conn, staleReplay)
	if response := readSecurityResponse(t, reader); response.OK || response.Code != ipc.CodeErrInvalidRequest {
		t.Fatalf("stale request bypassed validation through replay cache: %#v", response)
	}
}

func TestIsBenignClientDisconnect(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		{"bare io.EOF", io.EOF, true},
		{"bare net.ErrClosed", net.ErrClosed, true},
		{"bare context.Canceled", context.Canceled, true},
		{"bare io.ErrUnexpectedEOF", io.ErrUnexpectedEOF, true},
		{"wrapped io.EOF", fmt.Errorf("handshake read failed: %w", io.EOF), true},
		{"wrapped net.ErrClosed", fmt.Errorf("accept: %w", net.ErrClosed), true},
		{"wrapped context.Canceled", fmt.Errorf("serve loop: %w", context.Canceled), true},
		{"wrapped io.ErrUnexpectedEOF", fmt.Errorf("frame decode: %w", io.ErrUnexpectedEOF), true},
		{"broken pipe message", errors.New("write unix @clawdroid_secure_ipc->@: write: broken pipe"), true},
		{"connection reset message", errors.New("read: connection reset by peer"), true},
		{"use of closed network connection message", errors.New("read: use of closed network connection"), true},
		{"signature mismatch", errors.New("signature mismatch"), false},
		{"random error", errors.New("something else went wrong"), false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := isBenignClientDisconnect(tc.err); got != tc.want {
				t.Fatalf("isBenignClientDisconnect(%v)=%v want %v", tc.err, got, tc.want)
			}
		})
	}
}
