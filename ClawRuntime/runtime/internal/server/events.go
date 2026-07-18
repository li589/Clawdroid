package server

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"os"
	"os/exec"
	"slices"
	"strconv"
	"sort"
	"strings"
	"sync"
	"time"

	"clawdroid/runtime/internal/ipc"
	"clawdroid/runtime/internal/task"
)

const (
	subscribeEventTaskStateChanged     = "task_state_changed"
	subscribeEventDaemonStatus         = "daemon_status_changed"
	subscribeEventCapabilityChanged    = "capability_changed"
	subscribeEventWindowChanged        = "window_changed"
	subscribeEventXposedFocusChanged   = "xposed_focus_changed"
	subscribeEventXposedViewChanged    = "xposed_view_changed"
	subscribeEventSubscriptionClosed   = "subscription_closed"
	maxSubscribeEvents                 = 16
	eventStreamPollInterval            = 2 * time.Second
	eventStreamHeartbeatEvery          = 5
	eventWriteTimeout                  = 5 * time.Second
)

var allowedSubscribeEvents = map[string]struct{}{
	subscribeEventTaskStateChanged:   {},
	subscribeEventDaemonStatus:       {},
	subscribeEventCapabilityChanged:  {},
	subscribeEventWindowChanged:      {},
	subscribeEventXposedFocusChanged: {},
	subscribeEventXposedViewChanged:  {},
}

type subscribeEventsArgs struct {
	Events []string `json:"events"`
}

type eventFrame struct {
	Event     string                 `json:"event"`
	Timestamp int64                  `json:"timestamp"`
	Data      map[string]interface{} `json:"data"`
}

type eventSnapshot struct {
	daemonStatus     map[string]interface{}
	capabilityState  map[string]interface{}
	windowState      map[string]interface{}
	taskState        map[string]interface{}
	xposedFocusState map[string]interface{}
	xposedViewState  map[string]interface{}
}

func (s *Server) handleSubscribeEvents(ctx context.Context, sess *session, req ipc.Request, conn net.Conn, writer *bufio.Writer) error {
	s.finalizeCapabilityState(sess)

	// Security: enforce event.subscribe capability
	if !slices.Contains(sess.capabilities, ipc.CapabilityEventSubscribe) {
		return writeResponseFrame(writer, ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrCapabilityNotGranted,
			Message:   "event.subscribe capability not granted",
			Data:      s.sessionData(sess),
		})
	}

	// Rate limiting is enforced uniformly for all actions by the main request
	// loop in handleConnection (server.go) before routing here. Re-checking
	// here would double-consume the per-minute token budget for subscribe_events
	// versus every other action.

	args, err := parseSubscribeEventsArgs(req.Args)
	if err != nil {
		return writeResponseFrame(writer, ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		})
	}

	if err := writeResponseFrame(writer, ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data: mergeData(s.sessionData(sess), map[string]interface{}{
			"subscribed":            args.Events,
			"stream_mode":           "continuous",
			"initial_snapshot_len":  len(args.Events),
			"poll_interval_ms":      eventStreamPollInterval.Milliseconds(),
		}),
	}); err != nil {
		return err
	}

	if err := conn.SetDeadline(time.Time{}); err != nil {
		return err
	}

	// Register a per-subscriber wake channel so report_xposed_* wakes this loop
	// without being stolen by another subscriber (the previous single shared
	// channel only ever woke one subscriber per report).
	wake := make(chan struct{}, 1)
	s.registerEventSub(wake)
	defer s.unregisterEventSub(wake)

	currentSnapshot := s.captureEventSnapshot(sess)
	for _, frame := range buildSnapshotEventFrames(currentSnapshot, args.Events) {
		if err := writeEventFrame(conn, writer, frame); err != nil {
			return err
		}
	}

	ticker := time.NewTicker(eventStreamPollInterval)
	defer ticker.Stop()

	heartbeatCounter := 0
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		case <-wake:
		}
		heartbeatCounter++
		nextSnapshot := s.captureEventSnapshot(sess)
		changedFrames := diffSnapshotEventFrames(currentSnapshot, nextSnapshot, args.Events)
		if len(changedFrames) == 0 && heartbeatCounter >= eventStreamHeartbeatEvery && slices.Contains(args.Events, subscribeEventDaemonStatus) {
			changedFrames = append(changedFrames, eventFrame{
				Event:     subscribeEventDaemonStatus,
				Timestamp: time.Now().Unix(),
				Data:      copyMap(nextSnapshot.daemonStatus),
			})
		}

		for _, frame := range changedFrames {
			if err := writeEventFrame(conn, writer, frame); err != nil {
				return err
			}
			heartbeatCounter = 0
		}
		currentSnapshot = nextSnapshot
	}
}

func parseSubscribeEventsArgs(args map[string]interface{}) (subscribeEventsArgs, error) {
	var parsed subscribeEventsArgs
	rawEvents, ok := args["events"].([]interface{})
	if !ok || len(rawEvents) == 0 {
		return parsed, fmt.Errorf("events must be a non-empty array")
	}
	if len(rawEvents) > maxSubscribeEvents {
		return parsed, fmt.Errorf("events must contain between 1 and %d items", maxSubscribeEvents)
	}

	seen := make(map[string]struct{}, len(rawEvents))
	for _, raw := range rawEvents {
		name := strings.TrimSpace(fmt.Sprint(raw))
		if name == "" {
			return parsed, fmt.Errorf("event name must not be empty")
		}
		if _, allowed := allowedSubscribeEvents[name]; !allowed {
			return parsed, fmt.Errorf("unsupported event: %s", name)
		}
		if _, exists := seen[name]; exists {
			continue
		}
		seen[name] = struct{}{}
		parsed.Events = append(parsed.Events, name)
	}
	if len(parsed.Events) == 0 {
		return parsed, fmt.Errorf("events must contain at least one supported event")
	}

	return parsed, nil
}

func (s *Server) captureEventSnapshot(sess *session) eventSnapshot {
	rootAvailable := detectRootAvailable()
	accessibilityEnabled := detectAccessibilityEnabled(sess.packageName)
	lsposedAvailable := detectLSPosedAvailable()
	lsposedRuntimeMarker := detectLSPosedRuntimeMarker(sess.packageName)
	load1, load5, load15 := readLoadAverage()
	memTotalKB, memAvailableKB := readMemoryInfo()
	runtimePID, runtimeRSSKB := readCurrentProcessMetrics()

	taskState := s.taskStateForSession(sess.id)

	return eventSnapshot{
		daemonStatus: mergeData(s.versionState(), mergeData(mergeData(s.diagnosticsState(), map[string]interface{}{
			"daemon_status":    "ok",
			"root":             rootAvailable,
			"load_1":           load1,
			"load_5":           load5,
			"load_15":          load15,
			"mem_total_kb":     memTotalKB,
			"mem_available_kb": memAvailableKB,
			"server_time":      time.Now().Unix(),
		}), processMetricsState(runtimePID, runtimeRSSKB))),
		capabilityState: map[string]interface{}{
			"root":                      rootAvailable,
			"accessibility":             accessibilityEnabled,
			"lsposed":                   lsposedAvailable,
			"lsposed_runtime_loaded":    lsposedRuntimeMarker.XposedInjected,
			"lsposed_runtime_process":   lsposedRuntimeMarker.ProcessName,
			"lsposed_runtime_loaded_at": lsposedRuntimeMarker.LoadedAtEpochMS,
			"capabilities":              s.capabilityList(),
		},
		windowState: map[string]interface{}{
			"focused_window": detectFocusedWindow(),
		},
		taskState:        taskState,
		xposedFocusState: s.captureXposedFocusState(),
		xposedViewState:  s.captureXposedViewState(),
	}
}

// taskStateForSession returns the most relevant task state snapshot for a session.
// Priority: running task > queued task > most recent completed task.
func (s *Server) taskStateForSession(sessionID string) map[string]interface{} {
	tasks := s.taskScheduler.Registry().List(sessionID)
	if len(tasks) == 0 {
		return map[string]interface{}{
			"session_id": sessionID,
			"phase":     "idle",
			"task_count": 0,
		}
	}

	// Find running, queued, then most recent.
	var running, queued, latest *task.Task
	for _, t := range tasks {
		if t.State == task.TaskStateRunning {
			running = t
		} else if t.State == task.TaskStateQueued {
			queued = t
		}
		if latest == nil || t.CreatedAt.After(latest.CreatedAt) {
			latest = t
		}
	}

	chosen := running
	if chosen == nil {
		chosen = queued
	}
	if chosen == nil {
		chosen = latest
	}

	snapshot := chosen.StateSnapshot()
	snapshot["phase"] = string(chosen.State)
	snapshot["session_id"] = sessionID
	snapshot["task_count"] = len(tasks)
	return snapshot
}

func buildSnapshotEventFrames(snapshot eventSnapshot, eventNames []string) []eventFrame {
	now := time.Now().Unix()
	frames := make([]eventFrame, 0, len(eventNames))
	for _, eventName := range eventNames {
		switch eventName {
		case subscribeEventTaskStateChanged:
			frames = append(frames, eventFrame{Event: eventName, Timestamp: now, Data: copyMap(snapshot.taskState)})
		case subscribeEventDaemonStatus:
			frames = append(frames, eventFrame{Event: eventName, Timestamp: now, Data: copyMap(snapshot.daemonStatus)})
		case subscribeEventCapabilityChanged:
			frames = append(frames, eventFrame{Event: eventName, Timestamp: now, Data: copyMap(snapshot.capabilityState)})
		case subscribeEventWindowChanged:
			frames = append(frames, eventFrame{Event: eventName, Timestamp: now, Data: copyMap(snapshot.windowState)})
		case subscribeEventXposedFocusChanged:
			frames = append(frames, eventFrame{Event: eventName, Timestamp: now, Data: copyMap(snapshot.xposedFocusState)})
		case subscribeEventXposedViewChanged:
			frames = append(frames, eventFrame{Event: eventName, Timestamp: now, Data: copyMap(snapshot.xposedViewState)})
		}
	}
	return frames
}

func diffSnapshotEventFrames(previous eventSnapshot, current eventSnapshot, eventNames []string) []eventFrame {
	now := time.Now().Unix()
	frames := make([]eventFrame, 0, len(eventNames))
	for _, eventName := range eventNames {
		switch eventName {
		case subscribeEventTaskStateChanged:
			if !mapsEqual(previous.taskState, current.taskState) {
				frames = append(frames, eventFrame{Event: eventName, Timestamp: now, Data: copyMap(current.taskState)})
			}
		case subscribeEventDaemonStatus:
			if !mapsEqual(previous.daemonStatus, current.daemonStatus) {
				frames = append(frames, eventFrame{Event: eventName, Timestamp: now, Data: copyMap(current.daemonStatus)})
			}
		case subscribeEventCapabilityChanged:
			if !mapsEqual(previous.capabilityState, current.capabilityState) {
				frames = append(frames, eventFrame{Event: eventName, Timestamp: now, Data: copyMap(current.capabilityState)})
			}
		case subscribeEventWindowChanged:
			if !mapsEqual(previous.windowState, current.windowState) {
				frames = append(frames, eventFrame{Event: eventName, Timestamp: now, Data: copyMap(current.windowState)})
			}
		case subscribeEventXposedFocusChanged:
			if !mapsEqual(previous.xposedFocusState, current.xposedFocusState) {
				frames = append(frames, eventFrame{Event: eventName, Timestamp: now, Data: copyMap(current.xposedFocusState)})
			}
		case subscribeEventXposedViewChanged:
			if !mapsEqual(previous.xposedViewState, current.xposedViewState) {
				frames = append(frames, eventFrame{Event: eventName, Timestamp: now, Data: copyMap(current.xposedViewState)})
			}
		}
	}
	return frames
}

func writeEventFrame(conn net.Conn, writer *bufio.Writer, frame eventFrame) error {
	payload, err := json.Marshal(frame)
	if err != nil {
		return err
	}
	return writeJSONFrameWithTimeout(conn, writer, payload)
}

// focusedWindowCacheTTL bounds how often detectFocusedWindow may fork dumpsys
// subprocesses. Without it, every eventWake signal (e.g. each report_xposed_*)
// would trigger up to three dumpsys calls across all subscribers.
const focusedWindowCacheTTL = 500 * time.Millisecond

var (
	focusedWindowMu       sync.Mutex
	focusedWindowCache    string
	focusedWindowCachedAt time.Time
)

// detectFocusedWindow returns the focused window, cached for focusedWindowCacheTTL.
// The lock is held across the uncached call so concurrent subscribers that miss
// the cache block on the lock and then observe the freshly-populated cache,
// preventing an N×3 dumpsys stampede when a single notifyEventWake broadcast
// hits multiple subscribers at once.
func detectFocusedWindow() string {
	focusedWindowMu.Lock()
	defer focusedWindowMu.Unlock()
	if !focusedWindowCachedAt.IsZero() && time.Since(focusedWindowCachedAt) < focusedWindowCacheTTL {
		return focusedWindowCache
	}
	result := detectFocusedWindowUncached()
	focusedWindowCache = result
	focusedWindowCachedAt = time.Now()
	return result
}

func detectFocusedWindowUncached() string {
	for _, resolver := range []func() string{
		detectFocusedWindowFromWindows,
		detectFocusedWindowFromActivityTop,
		detectFocusedWindowFromActivityActivities,
	} {
		if resolved := resolver(); !isUnknownFocusedWindow(resolved) {
			return resolved
		}
	}
	return unknownFocusedWindowJSON()
}

func marshalFocusedWindow(raw string) string {
	source := ""
	switch {
	case strings.Contains(raw, "mCurrentFocus"):
		source = "mCurrentFocus"
	case strings.Contains(raw, "mFocusedApp"):
		source = "mFocusedApp"
	}

	component := extractFocusedComponent(raw)
	packageName := component
	activityName := ""
	if slash := strings.Index(component, "/"); slash >= 0 {
		packageName = component[:slash]
		activityName = component[slash+1:]
	}
	if packageName == "" {
		packageName = "unknown"
	}
	summary := packageName
	if activityName != "" {
		summary = packageName + "/" + activityName
	}

	payload, err := json.Marshal(map[string]string{
		"summary":  summary,
		"source":   source,
		"package":  packageName,
		"activity": activityName,
		"raw":      raw,
	})
	if err != nil {
		return `{"summary":"unknown","source":"","package":"","activity":"","raw":""}`
	}
	return string(payload)
}

func detectFocusedWindowFromWindows() string {
	output, err := exec.Command("dumpsys", "window", "windows").Output()
	if err != nil {
		return unknownFocusedWindowJSON()
	}

	lines := strings.Split(string(output), "\n")
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.Contains(trimmed, "mCurrentFocus") || strings.Contains(trimmed, "mFocusedApp") {
			return marshalFocusedWindow(trimmed)
		}
	}
	return unknownFocusedWindowJSON()
}

func detectFocusedWindowFromActivityTop() string {
	output, err := exec.Command("dumpsys", "activity", "top").Output()
	if err != nil {
		return unknownFocusedWindowJSON()
	}

	lines := strings.Split(string(output), "\n")
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "ACTIVITY ") || strings.Contains(trimmed, "ACTIVITY ") {
			component := extractFocusedComponent(trimmed)
			if component != "" {
				return marshalFocusedWindowWithSource("activity_top", component, trimmed)
			}
		}
	}
	return unknownFocusedWindowJSON()
}

func detectFocusedWindowFromActivityActivities() string {
	output, err := exec.Command("dumpsys", "activity", "activities").Output()
	if err != nil {
		return unknownFocusedWindowJSON()
	}

	lines := strings.Split(string(output), "\n")
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.Contains(trimmed, "mResumedActivity") || strings.Contains(trimmed, "topResumedActivity") {
			component := extractFocusedComponent(trimmed)
			if component != "" {
				return marshalFocusedWindowWithSource("activity_resumed", component, trimmed)
			}
		}
	}
	return unknownFocusedWindowJSON()
}

func marshalFocusedWindowWithSource(source string, component string, raw string) string {
	packageName := component
	activityName := ""
	if slash := strings.Index(component, "/"); slash >= 0 {
		packageName = component[:slash]
		activityName = component[slash+1:]
	}
	if packageName == "" {
		packageName = "unknown"
	}
	summary := packageName
	if activityName != "" {
		summary = packageName + "/" + activityName
	}

	payload, err := json.Marshal(map[string]string{
		"summary":  summary,
		"source":   source,
		"package":  packageName,
		"activity": activityName,
		"raw":      raw,
	})
	if err != nil {
		return unknownFocusedWindowJSON()
	}
	return string(payload)
}

func extractFocusedComponent(raw string) string {
	tokens := strings.Fields(raw)
	for _, token := range tokens {
		cleaned := strings.Trim(token, "{}[](),")
		if strings.Contains(cleaned, "/") && strings.Contains(cleaned, ".") {
			return cleaned
		}
	}
	return ""
}

func unknownFocusedWindowJSON() string {
	return `{"summary":"unknown","source":"","package":"","activity":"","raw":""}`
}

func isUnknownFocusedWindow(raw string) bool {
	return raw == "" || raw == unknownFocusedWindowJSON()
}

func readLoadAverage() (float64, float64, float64) {
	content, err := os.ReadFile("/proc/loadavg")
	if err != nil {
		slog.Warn("readLoadAverage: failed to read /proc/loadavg", "error", err)
		return 0, 0, 0
	}

	fields := strings.Fields(string(content))
	if len(fields) < 3 {
		slog.Warn("readLoadAverage: /proc/loadavg has fewer than 3 fields", "fields", fields)
		return 0, 0, 0
	}

	load1, err1 := strconv.ParseFloat(fields[0], 64)
	load5, err2 := strconv.ParseFloat(fields[1], 64)
	load15, err3 := strconv.ParseFloat(fields[2], 64)
	if err1 != nil || err2 != nil || err3 != nil {
		slog.Warn("readLoadAverage: failed to parse load values", "error1", err1, "error2", err2, "error3", err3)
		return 0, 0, 0
	}
	return load1, load5, load15
}

func readMemoryInfo() (int64, int64) {
	content, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return 0, 0
	}

	var memTotalKB int64
	var memAvailableKB int64
	for _, line := range strings.Split(string(content), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		value, parseErr := strconv.ParseInt(fields[1], 10, 64)
		if parseErr != nil {
			continue
		}
		switch fields[0] {
		case "MemTotal:":
			memTotalKB = value
		case "MemAvailable:":
			memAvailableKB = value
		}
	}
	return memTotalKB, memAvailableKB
}

func readCurrentProcessMetrics() (int, int64) {
	pid := os.Getpid()
	content, err := os.ReadFile("/proc/self/status")
	if err != nil {
		return pid, 0
	}

	var rssKB int64
	for _, line := range strings.Split(string(content), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		if fields[0] != "VmRSS:" {
			continue
		}
		value, parseErr := strconv.ParseInt(fields[1], 10, 64)
		if parseErr == nil {
			rssKB = value
		}
		break
	}
	return pid, rssKB
}

func copyMap(source map[string]interface{}) map[string]interface{} {
	result := make(map[string]interface{}, len(source))
	for key, value := range source {
		result[key] = value
	}
	return result
}

func mapsEqual(left map[string]interface{}, right map[string]interface{}) bool {
	leftJSON, leftErr := json.Marshal(normalizeMapForCompare(left))
	rightJSON, rightErr := json.Marshal(normalizeMapForCompare(right))
	if leftErr != nil || rightErr != nil {
		return false
	}
	return string(leftJSON) == string(rightJSON)
}

func normalizeMapForCompare(source map[string]interface{}) map[string]interface{} {
	keys := make([]string, 0, len(source))
	for key := range source {
		keys = append(keys, key)
	}
	sort.Strings(keys)

	result := make(map[string]interface{}, len(source))
	for _, key := range keys {
		value := source[key]
		switch typed := value.(type) {
		case []string:
			copied := append([]string(nil), typed...)
			sort.Strings(copied)
			result[key] = copied
		default:
			result[key] = value
		}
	}
	return result
}
