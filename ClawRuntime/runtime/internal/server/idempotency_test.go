package server

import (
	"testing"
	"time"

	"clawdroid/runtime/internal/ipc"
)

func TestIdempotencyCacheGetReturnsFalseForNonIdempotentAction(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	_, ok := c.get("inject_tap", "req-001")
	if ok {
		t.Fatal("expected non-idempotent action to return false")
	}
}

func TestIdempotencyCacheGetReturnsFalseForUnknownAction(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	_, ok := c.get("unknown_action", "req-001")
	if ok {
		t.Fatal("expected unknown action to return false")
	}
}

func TestIdempotencyCachePutGet(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	resp := ipc.Response{
		RequestID: "req-ping-001",
		OK:        true,
		Code:      0,
		Message:   "pong",
		Data:      map[string]interface{}{"result": "ok"},
	}

	c.put("ping", "req-ping-001", resp)

	got, ok := c.get("ping", "req-ping-001")
	if !ok {
		t.Fatal("expected to retrieve cached response")
	}
	if got.RequestID != resp.RequestID {
		t.Fatalf("request_id: got %q want %q", got.RequestID, resp.RequestID)
	}
	if got.OK != resp.OK {
		t.Fatalf("ok: got %v want %v", got.OK, resp.OK)
	}
	if got.Message != resp.Message {
		t.Fatalf("message: got %q want %q", got.Message, resp.Message)
	}
}

func TestIdempotencyCacheGetReturnsFalseForUnknownRequestID(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	c.put("ping", "req-ping-001", ipc.Response{RequestID: "req-ping-001", OK: true})
	_, ok := c.get("ping", "req-ping-999")
	if ok {
		t.Fatal("expected unknown request ID to return false")
	}
}

func TestIdempotencyCacheLRUEviction(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	c.maxSize = 3

	for i := 0; i < 3; i++ {
		c.put("ping", "req-00"+string(rune('1'+i)), ipc.Response{RequestID: "req-00" + string(rune('1'+i))})
	}

	_, ok1 := c.get("ping", "req-001")
	if !ok1 {
		t.Fatal("expected req-001 to still be cached")
	}

	c.put("ping", "req-004", ipc.Response{RequestID: "req-004"})

	_, ok2 := c.get("ping", "req-001")
	if ok2 {
		t.Fatal("expected req-001 to be evicted after LRU eviction")
	}

	_, ok3 := c.get("ping", "req-004")
	if !ok3 {
		t.Fatal("expected req-004 to be cached")
	}
}

func TestIdempotencyCacheAllIdempotentActions(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	resp := ipc.Response{RequestID: "req-001", OK: true}

	actions := []string{"ping", "get_capabilities", "read_file_limited", "subscribe_events"}
	for _, action := range actions {
		reqID := "req-" + action + "-all"
		respCopy := resp
		respCopy.RequestID = reqID
		c.put(action, reqID, respCopy)
		got, ok := c.get(action, reqID)
		if !ok {
			t.Fatalf("expected %q to be cached as idempotent action", action)
		}
		if got.RequestID != reqID {
			t.Fatalf("request_id mismatch for %q: got %q want %q", action, got.RequestID, reqID)
		}
	}
}

func TestIdempotencyCachePutDoesNothingForNonIdempotentAction(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	c.put("inject_tap", "req-001", ipc.Response{RequestID: "req-001"})
	_, ok := c.get("inject_tap", "req-001")
	if ok {
		t.Fatal("expected non-idempotent action to not store anything")
	}
}

func TestIdempotencyCacheDelete(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	c.put("ping", "req-001", ipc.Response{RequestID: "req-001"})

	_, ok := c.get("ping", "req-001")
	if !ok {
		t.Fatal("expected req-001 to be cached before delete")
	}

	c.delete("req-001")

	_, ok = c.get("ping", "req-001")
	if ok {
		t.Fatal("expected req-001 to be deleted")
	}
}

func TestIdempotencyCachePutOverwritesExisting(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	resp1 := ipc.Response{RequestID: "req-001", OK: true, Message: "first"}
	resp2 := ipc.Response{RequestID: "req-001", OK: true, Message: "second"}

	c.put("ping", "req-001", resp1)
	c.put("ping", "req-001", resp2)

	got, ok := c.get("ping", "req-001")
	if !ok {
		t.Fatal("expected to retrieve cached response")
	}
	if got.Message != "second" {
		t.Fatalf("expected second message after overwrite, got %q", got.Message)
	}
}

func TestIdempotencyCacheConcurrency(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	c.maxSize = 500
	done := make(chan struct{})
	for i := 0; i < 50; i++ {
		go func(idx int) {
			for j := 0; j < 20; j++ {
				reqID := "req-concurrent-" + string(rune('a'+idx)) + string(rune('0'+j))
				c.put("ping", reqID, ipc.Response{RequestID: reqID})
				c.get("ping", reqID)
			}
			done <- struct{}{}
		}(i)
	}
	for i := 0; i < 50; i++ {
		<-done
	}
	_ = t
}

func TestIdempotencyCacheEntryPreservesAllFields(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	resp := ipc.Response{
		RequestID: "req-cap-001",
		OK:        true,
		Code:      0,
		Message:   "capabilities returned",
		Data: map[string]interface{}{
			"capabilities": []interface{}{"screen_capture", "input_injection"},
		},
	}

	c.put("get_capabilities", "req-cap-001", resp)
	got, ok := c.get("get_capabilities", "req-cap-001")

	if !ok {
		t.Fatal("expected to retrieve cached response")
	}
	if !got.OK {
		t.Fatal("expected OK to be true")
	}
	if got.Code != 0 {
		t.Fatalf("expected code 0, got %d", got.Code)
	}
	data, ok := got.Data["capabilities"]
	if !ok {
		t.Fatal("expected capabilities in data")
	}
	caps, ok := data.([]interface{})
	if !ok {
		t.Fatalf("expected capabilities to be []interface{}, got %T", data)
	}
	if len(caps) != 2 {
		t.Fatalf("expected 2 capabilities, got %d", len(caps))
	}
}

func TestIdempotencyCacheRefreshOnAccess(t *testing.T) {
	t.Parallel()

	c := newIdempotencyCache()
	c.maxAge = 50 * time.Millisecond

	c.put("ping", "req-001", ipc.Response{RequestID: "req-001"})

	time.Sleep(30 * time.Millisecond)
	_, ok := c.get("ping", "req-001")
	if !ok {
		t.Fatal("expected entry to still be valid after short sleep")
	}

	time.Sleep(30 * time.Millisecond)
	_, ok = c.get("ping", "req-001")
	if ok {
		t.Fatal("expected entry to be expired after TTL")
	}
}
