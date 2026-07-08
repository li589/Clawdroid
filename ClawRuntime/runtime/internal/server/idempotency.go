package server

import (
	"clawdroid/runtime/internal/ipc"
	"sync"
	"time"
)

var idempotentActions = map[string]struct{}{
	"ping":              {},
	"get_capabilities":  {},
	"read_file_limited": {},
	"subscribe_events":  {},
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

func (c *idempotencyCache) get(action, requestID string) (ipc.Response, bool) {
	if !isIdempotentAction(action) {
		return ipc.Response{}, false
	}

	c.mu.RLock()
	entry, ok := c.entries[requestID]
	c.mu.RUnlock()
	if !ok {
		return ipc.Response{}, false
	}

	if time.Since(entry.createdAt) > c.maxAge {
		c.delete(requestID)
		return ipc.Response{}, false
	}

	return entry.response, true
}

func (c *idempotencyCache) put(action, requestID string, resp ipc.Response) {
	if !isIdempotentAction(action) {
		return
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	if _, exists := c.entries[requestID]; !exists {
		c.order = append(c.order, requestID)
		if len(c.order) > c.maxSize {
			evict := c.order[0]
			delete(c.entries, evict)
			c.order = c.order[1:]
		}
	}

	c.entries[requestID] = idempotencyEntry{
		response:  resp,
		createdAt: time.Now(),
	}
}

func (c *idempotencyCache) delete(requestID string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.entries, requestID)
}
