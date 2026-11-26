package server

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"maps"
	"sync"
	"time"

	"clawdroid/runtime/internal/ipc"
)

type replayStatus uint8

const (
	replayExecuted replayStatus = iota
	replayHit
	replayArgsConflict
	replayCacheFull
	replayContextCanceled
	replayAborted
)

type replayKey struct {
	principal string
	action    string
	requestID string
}

var replayEvictableActions = map[string]struct{}{
	ipc.ActionPing:             {},
	ipc.ActionGetCapabilities:  {},
	ipc.ActionGetRuntimeStatus: {},
	ipc.ActionReadFileLimited:  {},
	ipc.ActionStatFileLimited:  {},
	ipc.ActionListDirLimited:   {},
	ipc.ActionTaskGet:          {},
	ipc.ActionTaskList:         {},
	ipc.ActionShellJobList:     {},
	ipc.ActionShellJobGet:      {},
}

type idempotencyEntry struct {
	response   ipc.Response
	argsDigest string
	createdAt  time.Time
	done       chan struct{}
	aborted    bool
}

type idempotencyCache struct {
	mu      sync.Mutex
	entries map[replayKey]*idempotencyEntry
	order   []replayKey
	maxSize int
	maxAge  time.Duration
}

func newIdempotencyCache() *idempotencyCache {
	return &idempotencyCache{
		entries: make(map[replayKey]*idempotencyEntry),
		order:   make([]replayKey, 0, 1000),
		maxSize: 1000,
		maxAge:  5 * time.Minute,
	}
}

// canonicalArgsDigest hashes normalized JSON. encoding/json sorts map keys, so
// semantically identical decoded request arguments have the same digest.
func canonicalArgsDigest(args map[string]interface{}) (string, error) {
	if args == nil {
		args = map[string]interface{}{}
	}
	encoded, err := json.Marshal(args)
	if err != nil {
		return "", err
	}
	digest := sha256.Sum256(encoded)
	return hex.EncodeToString(digest[:]), nil
}

func cloneReplayResponse(resp ipc.Response) ipc.Response {
	resp.Data = maps.Clone(resp.Data)
	return resp
}

// execute atomically claims a replay key before running a non-idempotent action.
// Concurrent duplicates wait on the claimed entry itself, so later capacity
// eviction cannot make them execute the action again.
func (c *idempotencyCache) execute(
	ctx context.Context,
	principal, action, requestID, digest string,
	execute func() ipc.Response,
) (ipc.Response, replayStatus) {
	key := replayKey{principal: principal, action: action, requestID: requestID}
	for {
		if ctx.Err() != nil {
			return ipc.Response{}, replayContextCanceled
		}

		c.mu.Lock()
		c.pruneExpiredLocked(time.Now())
		if entry, ok := c.entries[key]; ok {
			if entry.argsDigest != digest {
				c.mu.Unlock()
				return ipc.Response{}, replayArgsConflict
			}
			if entry.done != nil {
				done := entry.done
				c.mu.Unlock()
				select {
				case <-ctx.Done():
					return ipc.Response{}, replayContextCanceled
				case <-done:
				}
				c.mu.Lock()
				aborted := entry.aborted
				response := cloneReplayResponse(entry.response)
				c.mu.Unlock()
				if aborted {
					return ipc.Response{}, replayAborted
				}
				return response, replayHit
			}
			response := cloneReplayResponse(entry.response)
			c.mu.Unlock()
			return response, replayHit
		}
		if c.maxSize <= 0 {
			c.mu.Unlock()
			return ipc.Response{}, replayCacheFull
		}
		if len(c.entries) >= c.maxSize && !c.evictOldestCompletedLocked() {
			c.mu.Unlock()
			return ipc.Response{}, replayCacheFull
		}
		entry := &idempotencyEntry{
			argsDigest: digest,
			createdAt:  time.Now(),
			done:       make(chan struct{}),
		}
		c.entries[key] = entry
		c.order = append(c.order, key)
		c.mu.Unlock()

		finished := false
		defer func() {
			if !finished {
				c.abort(key, entry)
			}
		}()

		response := execute()
		c.mu.Lock()
		entry.response = cloneReplayResponse(response)
		entry.createdAt = time.Now()
		done := entry.done
		entry.done = nil
		close(done)
		c.mu.Unlock()
		finished = true
		return response, replayExecuted
	}
}

func (c *idempotencyCache) abort(key replayKey, entry *idempotencyEntry) {
	c.mu.Lock()
	defer c.mu.Unlock()
	current, ok := c.entries[key]
	if !ok || current != entry || entry.done == nil {
		return
	}
	done := entry.done
	entry.done = nil
	entry.aborted = true
	c.removeKeyLocked(key)
	close(done)
}

func (c *idempotencyCache) removeKeyLocked(key replayKey) {
	delete(c.entries, key)
	for i, candidate := range c.order {
		if candidate == key {
			c.order = append(c.order[:i], c.order[i+1:]...)
			return
		}
	}
}

func (c *idempotencyCache) pruneExpiredLocked(now time.Time) {
	if c.maxAge <= 0 {
		return
	}
	for _, key := range append([]replayKey(nil), c.order...) {
		entry, ok := c.entries[key]
		if !ok {
			c.removeKeyLocked(key)
			continue
		}
		if entry.done == nil && now.Sub(entry.createdAt) > c.maxAge {
			c.removeKeyLocked(key)
		}
	}
}

func (c *idempotencyCache) evictOldestCompletedLocked() bool {
	for i := 0; i < len(c.order); {
		key := c.order[i]
		entry, ok := c.entries[key]
		if !ok {
			c.order = append(c.order[:i], c.order[i+1:]...)
			continue
		}
		if entry.done != nil {
			i++
			continue
		}
		if _, evictable := replayEvictableActions[key.action]; !evictable {
			// Non-idempotent keys are replay tombstones until TTL expiry. Evicting
			// one would allow the same request_id to execute a dangerous action twice.
			i++
			continue
		}
		delete(c.entries, key)
		c.order = append(c.order[:i], c.order[i+1:]...)
		return true
	}
	return false
}
