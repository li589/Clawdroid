package server

import (
	"encoding/json"
	"fmt"
	"time"
)

const (
	xposedStateTTL        = 30 * time.Second
	xposedStateFutureSkew = time.Second
)

// stateEpochMs returns the best-known freshness timestamp for an xposed state map.
// Prefers reported_at_epoch_ms (in-memory report), then loaded_at_epoch_ms (file).
func stateEpochMs(state map[string]interface{}) int64 {
	if len(state) == 0 {
		return 0
	}
	for _, key := range []string{"reported_at_epoch_ms", "loaded_at_epoch_ms"} {
		if ms, ok := asInt64(state[key]); ok && ms > 0 {
			return ms
		}
	}
	return 0
}

func asInt64(value interface{}) (int64, bool) {
	switch n := value.(type) {
	case int64:
		return n, true
	case int:
		return int64(n), true
	case float64:
		return int64(n), true
	case json.Number:
		v, err := n.Int64()
		if err != nil {
			return 0, false
		}
		return v, true
	case string:
		var parsed int64
		if _, err := fmt.Sscan(n, &parsed); err == nil {
			return parsed, true
		}
	}
	return 0, false
}

func freshXposedState(state map[string]interface{}, now time.Time) map[string]interface{} {
	epochMs := stateEpochMs(state)
	if epochMs <= 0 {
		return map[string]interface{}{}
	}
	epoch := time.UnixMilli(epochMs)
	if epoch.After(now.Add(xposedStateFutureSkew)) || now.Sub(epoch) > xposedStateTTL {
		return map[string]interface{}{}
	}
	return state
}

// pickNewerXposedState chooses the fresher of in-memory vs file-backed state.
func pickNewerXposedState(memory, file map[string]interface{}) map[string]interface{} {
	if len(memory) == 0 {
		return file
	}
	if len(file) == 0 {
		return memory
	}
	if stateEpochMs(file) > stateEpochMs(memory) {
		return file
	}
	return memory
}
