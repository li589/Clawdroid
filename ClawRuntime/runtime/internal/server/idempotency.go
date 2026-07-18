package server

import (
	"sync"
	"time"

	"clawdroid/runtime/internal/ipc"
)

var idempotentActions = map[string]struct{}{
	ipc.ActionPing:             {},
	ipc.ActionGetCapabilities:  {},
	ipc.ActionGetRuntimeStatus: {},
	ipc.ActionReadFileLimited:  {},
	ipc.ActionStatFileLimited:  {},
	ipc.ActionSubscribeEvents:  {},
}

func isIdempotentAction(action string) bool {
	_, ok := idempotentActions[action]
	return ok
}

type idempotencyCache struct {
	mu      sync.RWMutex
	entries map[string]idempotencyEntry
	order   []string
	maxSize int
	maxAge  time.Duration
}

type idempotencyEntry struct {
	response  ipc.Response
	createdAt time.Time
}

func newIdempotencyCache() *idempotencyCache {
	return &idempotencyCache{
		entries: make(map[string]idempotencyEntry),
		order:   make([]string, 0, 1000),
		maxSize: 1000,
		maxAge:  5 * time.Minute,
	}
}

func (c *idempotencyCache) get(action, requestID, sessionID string) (ipc.Response, bool) {
	if !isIdempotentAction(action) {
		return ipc.Response{}, false
	}

	key := action + ":" + requestID + ":" + sessionID
	c.mu.RLock()
	entry, ok := c.entries[key]
	c.mu.RUnlock()
	if !ok {
		return ipc.Response{}, false
	}

	if time.Since(entry.createdAt) > c.maxAge {
		c.delete(key)
		return ipc.Response{}, false
	}

	return entry.response, true
}

func (c *idempotencyCache) put(action, requestID, sessionID string, resp ipc.Response) {
	if !isIdempotentAction(action) {
		return
	}

	key := action + ":" + requestID + ":" + sessionID
	c.mu.Lock()
	defer c.mu.Unlock()

	if _, exists := c.entries[key]; !exists {
		c.order = append(c.order, key)
		if len(c.order) > c.maxSize {
			evict := c.order[0]
			delete(c.entries, evict)
			c.order = c.order[1:]
		}
	}
	// Only store the response body; do NOT include session-specific data.
	c.entries[key] = idempotencyEntry{
		response:  resp,
		createdAt: time.Now(),
	}
}

func (c *idempotencyCache) delete(key string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.entries, key)
}
