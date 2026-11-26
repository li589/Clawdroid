package server

import (
	"context"
	"errors"
	"fmt"
	"math"
	"slices"
	"sort"
	"time"

	"clawdroid/runtime/internal/ipc"
	"clawdroid/runtime/internal/task"
)

func (s *Server) handleRequest(sess *session, req ipc.Request) ipc.Response {
	return s.handleRequestWithContext(context.Background(), sess, req)
}

func (s *Server) handleRequestWithContext(ctx context.Context, sess *session, req ipc.Request) ipc.Response {
	if !s.withinTimestampSkew(req.Timestamp) {
		return s.invalidTimestampResponse(sess, req)
	}
	// Layer-4 capability gate. Every action maps to a capability token; the
	// session must hold that token or the request is rejected. Capabilities are
	// synced during the get_capabilities handshake; finalize lazily if a client
	// skips it so the gate never reads a nil capability set.
	if cap, ok := ipc.ExpectedCapability(req.Action); ok {
		capabilities := sess.capabilitiesSnapshot()
		if capabilities == nil {
			s.finalizeCapabilityState(sess)
			capabilities = sess.capabilitiesSnapshot()
		}
		if !slices.Contains(capabilities, cap) {
			return ipc.Response{
				RequestID: req.RequestID,
				OK:        false,
				Code:      ipc.CodeErrCapabilityNotGranted,
				Message:   ipc.ErrorMessage(ipc.CodeErrCapabilityNotGranted),
				Data: map[string]interface{}{
					"action":     req.Action,
					"capability": cap,
				},
			}
		}
	}
	switch req.Action {
	case ipc.ActionPing:
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        true,
			Code:      ipc.CodeOK,
			Message:   ipc.ErrorMessage(ipc.CodeOK),
			Data: mergeData(
				s.sessionData(sess),
				mergeData(s.versionState(), map[string]interface{}{
					"daemon_status": "ok",
					"latency_ms":    1,
				}),
			),
		}
	case ipc.ActionGetCapabilities:
		s.finalizeCapabilityState(sess)

		packageName := sess.snapshot().packageName
		rootAvailable := detectRootAvailable()
		accessibilityEnabled := detectAccessibilityEnabled(packageName)
		lsposedAvailable := detectLSPosedAvailable()
		lsposedRuntimeMarker := detectLSPosedRuntimeMarker(packageName)

		data := mergeData(s.sessionData(sess), mergeData(s.versionState(), mergeData(s.diagnosticsState(), map[string]interface{}{
			"root":                      rootAvailable,
			"accessibility":             accessibilityEnabled,
			"lsposed":                   lsposedAvailable,
			"lsposed_runtime_loaded":    lsposedRuntimeMarker.XposedInjected,
			"lsposed_runtime_process":   lsposedRuntimeMarker.ProcessName,
			"lsposed_runtime_loaded_at": lsposedRuntimeMarker.LoadedAtEpochMS,
			"capabilities":              s.capabilityList(),
			"audit_dir":                 s.cfg.AuditDir,
			"request_timeout_ms":        s.cfg.RequestTimeoutMS,
			"session_ttl_seconds":       s.cfg.SessionTTLSec,
			"challenge_ttl_seconds":     s.cfg.ChallengeTTLSec,
			"input_inject_enabled":      s.cfg.InputInjectEnabled,
			"screenshot_enabled":        s.cfg.ScreenshotEnabled,
			"shell_enabled":             s.cfg.ShellEnabled,
			"file_bridge_enabled":       s.cfg.FileBridgeEnabled,
			"server_time":               time.Now().Unix(),
		})))
		if degradedReason := sess.snapshot().degradedReason; degradedReason != "" {
			data["degraded_reason"] = degradedReason
		}

		return ipc.Response{
			RequestID: req.RequestID,
			OK:        true,
			Code:      ipc.CodeOK,
			Message:   ipc.ErrorMessage(ipc.CodeOK),
			Data:      data,
		}
	case ipc.ActionGetRuntimeStatus:
		s.finalizeCapabilityState(sess)
		return s.handleGetRuntimeStatus(sess, req)
	case ipc.ActionCaptureScreen:
		s.finalizeCapabilityState(sess)
		return s.handleCaptureScreenContext(ctx, sess, req)
	case ipc.ActionReadFileLimited:
		s.finalizeCapabilityState(sess)
		return s.handleReadFileLimited(sess, req)
	case ipc.ActionWriteFileLimited:
		s.finalizeCapabilityState(sess)
		return s.handleWriteFileLimited(sess, req)
	case ipc.ActionStatFileLimited:
		s.finalizeCapabilityState(sess)
		return s.handleStatFileLimited(sess, req)
	case ipc.ActionListDirLimited:
		s.finalizeCapabilityState(sess)
		return s.handleListDirLimited(sess, req)
	case ipc.ActionInjectTap:
		s.finalizeCapabilityState(sess)
		return s.handleInjectTapContext(ctx, sess, req)
	case ipc.ActionInjectSwipe:
		s.finalizeCapabilityState(sess)
		return s.handleInjectSwipeContext(ctx, sess, req)
	case ipc.ActionInjectKeyevent:
		s.finalizeCapabilityState(sess)
		return s.handleInjectKeyeventContext(ctx, sess, req)
	case ipc.ActionExecShellLimited:
		s.finalizeCapabilityState(sess)
		return s.handleExecShellLimitedContext(ctx, sess, req)
	case ipc.ActionShellJobList:
		s.finalizeCapabilityState(sess)
		return s.handleShellJobList(sess, req)
	case ipc.ActionShellJobGet:
		s.finalizeCapabilityState(sess)
		return s.handleShellJobGet(sess, req)
	case ipc.ActionTaskSubmit:
		s.finalizeCapabilityState(sess)
		return s.handleTaskSubmitContext(ctx, sess, req)
	case ipc.ActionTaskGet:
		s.finalizeCapabilityState(sess)
		return s.handleTaskGet(sess, req)
	case ipc.ActionTaskList:
		s.finalizeCapabilityState(sess)
		return s.handleTaskList(sess, req)
	case ipc.ActionTaskCancel:
		s.finalizeCapabilityState(sess)
		return s.handleTaskCancel(sess, req)
	case ipc.ActionReportXposedFocus:
		s.finalizeCapabilityState(sess)
		return s.handleReportXposedFocus(sess, req)
	case ipc.ActionReportXposedView:
		s.finalizeCapabilityState(sess)
		return s.handleReportXposedView(sess, req)
	default:
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrActionNotAllowed,
			Message:   ipc.ErrorMessage(ipc.CodeErrActionNotAllowed),
			Data:      map[string]interface{}{},
		}
	}
}

func (s *Server) handleGetRuntimeStatus(sess *session, req ipc.Request) ipc.Response {
	module := detectMagiskModuleStatus()
	data := mergeData(s.sessionData(sess), mergeData(s.healthState(sess), map[string]interface{}{
		"module":                 module.asMap(),
		"allowed_shell_commands": sortedAllowedShellCommands,
		"allowed_keyevents":      allowedKeyeventList(),
		"actions":                sortedActionNames(),
		"task_scheduler": map[string]interface{}{
			"max_concurrent_tasks": s.taskScheduler.Options().MaxConcurrent,
			"max_inflight_tasks":   s.taskScheduler.Options().MaxInflight,
			"inflight":             s.taskScheduler.InflightCount(),
			"running":              s.taskScheduler.RunningCount(),
		},
		"recent_shell_jobs": s.shellJobs.snapshotMaps(8),
	}))
	if degradedReason := sess.snapshot().degradedReason; degradedReason != "" {
		data["degraded_reason"] = degradedReason
	}
	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data:      data,
	}
}

func (s *Server) sessionData(sess *session) map[string]interface{} {
	snapshot := sess.snapshot()
	expiresAt := int64(0)
	if !snapshot.authenticatedAt.IsZero() && s.cfg.SessionTTLSec > 0 {
		expiresAt = snapshot.authenticatedAt.Add(time.Duration(s.cfg.SessionTTLSec) * time.Second).Unix()
	}
	return map[string]interface{}{
		"session_id":         snapshot.id,
		"package_name":       snapshot.packageName,
		"signature_digest":   snapshot.signatureDigest,
		"session_state":      string(snapshot.state),
		"state_trace":        snapshot.trace,
		"auth_mode":          snapshot.authMode,
		"session_expires_at": expiresAt,
		"peer_pid":           snapshot.peerPID,
		"peer_uid":           snapshot.peerUID,
		"peer_gid":           snapshot.peerGID,
		"peer_verified":      snapshot.peerVerified,
		"peer_auth_method":   snapshot.peerVerificationMethod,
		"peer_package_bound": snapshot.peerPackageBound,
		"peer_packages":      append([]string(nil), snapshot.peerKnownPackages...),
	}
}

func mergeData(base map[string]interface{}, extra map[string]interface{}) map[string]interface{} {
	result := make(map[string]interface{}, len(base)+len(extra))
	for key, value := range base {
		result[key] = value
	}
	for key, value := range extra {
		result[key] = value
	}
	return result
}

func (s *Server) capabilityList() []string {
	capabilities := []string{
		ipc.CapabilitySystemPing,
		ipc.CapabilitySystemInspect,
		ipc.CapabilityEventSubscribe,
		ipc.CapabilityEventReport,
		ipc.CapabilityTaskManage,
	}

	if s.cfg.ScreenshotEnabled {
		capabilities = append(capabilities, ipc.CapabilityScreenCapture)
	}
	if s.cfg.InputInjectEnabled {
		capabilities = append(capabilities, ipc.CapabilityInputInject)
	}
	if s.cfg.ShellEnabled {
		capabilities = append(capabilities, ipc.CapabilityShellExecLimited)
	}
	if s.cfg.FileBridgeEnabled {
		capabilities = append(capabilities, ipc.CapabilityFileReadLimited, ipc.CapabilityFileWriteLimited)
	}

	return capabilities
}

func expectedCapabilityForAction(action string) (string, bool) {
	return ipc.ExpectedCapability(action)
}

func sortedActionNames() []string {
	actions := ipc.KnownActions()
	sort.Strings(actions)
	return actions
}

func principalForSession(sess *session) (task.Principal, bool) {
	snapshot := sess.snapshot()
	principal := task.Principal{
		PackageName:     snapshot.packageName,
		UID:             snapshot.peerUID,
		SignatureDigest: snapshot.signatureDigest,
	}
	return principal, snapshot.peerVerified && principal.PackageName != "" &&
		principal.UID > 0 && principal.SignatureDigest != ""
}

func (s *Server) taskPrincipalError(req ipc.Request, sess *session) ipc.Response {
	return ipc.Response{
		RequestID: req.RequestID,
		OK:        false,
		Code:      ipc.CodeErrPeerVerifyFailed,
		Message:   "task operations require an authenticated package, uid, and signature",
		Data:      s.sessionData(sess),
	}
}

// handleTaskSubmit submits a new multi-step task for execution.
func (s *Server) handleTaskSubmit(sess *session, req ipc.Request) ipc.Response {
	return s.handleTaskSubmitContext(context.Background(), sess, req)
}

func (s *Server) handleTaskSubmitContext(ctx context.Context, sess *session, req ipc.Request) ipc.Response {
	principal, ok := principalForSession(sess)
	if !ok {
		return s.taskPrincipalError(req, sess)
	}
	// Parse task from request args.
	rawTask, ok := req.Args["task"]
	if !ok {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   "missing required field: task",
			Data:      s.sessionData(sess),
		}
	}

	taskMap, ok := rawTask.(map[string]interface{})
	if !ok {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   "field 'task' must be an object",
			Data:      s.sessionData(sess),
		}
	}

	// Build Task from map. Ownership comes only from the authenticated snapshot.
	t, err := taskFromMap(taskMap)
	if err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}
	t.SessionID = sess.id // Diagnostic only; never used as ownership identity.
	t.Owner = principal
	t.OwnerKey = principal.OwnerKey()

	if err := s.taskScheduler.Submit(ctx, t); err != nil {
		code := ipc.CodeErrTaskSubmitFailed
		if errors.Is(err, task.ErrQueueFull) {
			code = ipc.CodeErrTaskQueueFull
		}
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      code,
			Message:   err.Error(),
			Data: mergeData(s.sessionData(sess), map[string]interface{}{
				"max_concurrent_tasks": s.taskScheduler.Options().MaxConcurrent,
				"max_inflight_tasks":   s.taskScheduler.Options().MaxInflight,
				"inflight":             s.taskScheduler.InflightCount(),
			}),
		}
	}

	state := task.TaskStateQueued
	if submitted, ok := s.taskScheduler.Registry().SnapshotByID(t.ID); ok {
		state = submitted.State
	}
	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data: mergeData(s.sessionData(sess), map[string]interface{}{
			"task_id": t.ID,
			"state":   string(state),
		}),
	}
}

// handleTaskGet retrieves the current state of a task owned by the caller.
func (s *Server) handleTaskGet(sess *session, req ipc.Request) ipc.Response {
	principal, ok := principalForSession(sess)
	if !ok {
		return s.taskPrincipalError(req, sess)
	}
	taskID, ok := req.Args["task_id"].(string)
	if !ok || taskID == "" {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   "missing required field: task_id",
			Data:      s.sessionData(sess),
		}
	}

	// Cross-connection lookup is allowed only within the same stable principal.
	t, ok := s.taskScheduler.Registry().SnapshotByID(taskID)
	if !ok || t.OwnerKey != principal.OwnerKey() {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrTaskNotFound,
			Message:   ipc.ErrorMessage(ipc.CodeErrTaskNotFound),
			Data:      s.sessionData(sess),
		}
	}

	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data:      mergeData(s.sessionData(sess), t.StateSnapshot()),
	}
}

// handleTaskList lists all tasks for the current stable owner.
func (s *Server) handleTaskList(sess *session, req ipc.Request) ipc.Response {
	principal, ok := principalForSession(sess)
	if !ok {
		return s.taskPrincipalError(req, sess)
	}
	tasks := s.taskScheduler.Registry().List(principal.OwnerKey())

	taskSnapshots := make([]map[string]interface{}, 0, len(tasks))
	for _, t := range tasks {
		taskSnapshots = append(taskSnapshots, t.StateSnapshot())
	}

	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data: mergeData(s.sessionData(sess), map[string]interface{}{
			"tasks":       taskSnapshots,
			"total_count": len(taskSnapshots),
		}),
	}
}

// handleTaskCancel requests cancellation of a queued, running, waiting, or
// retrying task. Compensating and terminal states are protocol errors (7002),
// not system cancellation failures (7004).
func (s *Server) handleTaskCancel(sess *session, req ipc.Request) ipc.Response {
	principal, ok := principalForSession(sess)
	if !ok {
		return s.taskPrincipalError(req, sess)
	}
	taskID, ok := req.Args["task_id"].(string)
	if !ok || taskID == "" {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   "missing required field: task_id",
			Data:      s.sessionData(sess),
		}
	}

	ownerKey := principal.OwnerKey()
	current, ok := s.taskScheduler.Registry().SnapshotByID(taskID)
	if !ok || current.OwnerKey != ownerKey {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrTaskNotFound,
			Message:   ipc.ErrorMessage(ipc.CodeErrTaskNotFound),
			Data:      s.sessionData(sess),
		}
	}
	if !taskStateCanCancel(current.State) {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrTaskStateInvalid,
			Message:   fmt.Sprintf("task cannot be cancelled from state %s", current.State),
			Data:      s.sessionData(sess),
		}
	}

	if s.taskScheduler.Cancel(taskID) {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        true,
			Code:      ipc.CodeOK,
			Message:   ipc.ErrorMessage(ipc.CodeOK),
			Data: mergeData(s.sessionData(sess), map[string]interface{}{
				"task_id":   taskID,
				"cancelled": true,
			}),
		}
	}

	// Completion or cancellation may have won the race after the precheck.
	if latest, found := s.taskScheduler.Registry().SnapshotByID(taskID); !found || latest.OwnerKey != ownerKey {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrTaskNotFound,
			Message:   ipc.ErrorMessage(ipc.CodeErrTaskNotFound),
			Data:      s.sessionData(sess),
		}
	} else if !taskStateCanCancel(latest.State) {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrTaskStateInvalid,
			Message:   fmt.Sprintf("task cannot be cancelled from state %s", latest.State),
			Data:      s.sessionData(sess),
		}
	}
	return ipc.Response{
		RequestID: req.RequestID,
		OK:        false,
		Code:      ipc.CodeErrTaskCancelFailed,
		Message:   ipc.ErrorMessage(ipc.CodeErrTaskCancelFailed),
		Data:      s.sessionData(sess),
	}
}

func taskStateCanCancel(state task.TaskState) bool {
	switch state {
	case task.TaskStateQueued, task.TaskStateRunning, task.TaskStateWaitingSignal, task.TaskStateRetrying:
		return true
	default:
		return false
	}
}

// taskFromMap constructs a Task from a map. Connection and owner identity are not
// accepted from the request; the server attaches its authenticated snapshot.
func taskFromMap(m map[string]interface{}) (*task.Task, error) {
	t := &task.Task{}

	if id, ok := m["task_id"].(string); ok {
		t.ID = id
	} else {
		return nil, fmt.Errorf("missing required field: task_id")
	}

	if name, ok := m["name"].(string); ok {
		t.Name = name
	}

	rawSteps, ok := m["steps"].([]interface{})
	if !ok || len(rawSteps) == 0 {
		return nil, fmt.Errorf("missing or invalid required field: steps (must be a non-empty array)")
	}
	for i, rawStep := range rawSteps {
		stepMap, ok := rawStep.(map[string]interface{})
		if !ok {
			return nil, fmt.Errorf("steps[%d] must be an object", i)
		}
		step, err := stepFromMap(stepMap)
		if err != nil {
			return nil, fmt.Errorf("steps[%d]: %w", i, err)
		}
		t.Steps = append(t.Steps, *step)
	}

	// Parse retry policy if provided. Missing fields retain defaults; an explicit
	// all-zero policy remains distinguishable so max_attempts=0 disables retries.
	if rawRetry, exists := m["retry_policy"]; exists {
		retryMap, ok := rawRetry.(map[string]interface{})
		if !ok {
			return nil, fmt.Errorf("retry_policy must be an object")
		}
		t.RetryPolicy = task.DefaultRetryPolicy()
		if err := parseBoundedInt(retryMap, "max_attempts", 0, task.MaxRetryAttempts, &t.RetryPolicy.MaxAttempts); err != nil {
			return nil, fmt.Errorf("retry_policy: %w", err)
		}
		if err := parseBoundedInt(retryMap, "initial_delay_ms", 0, task.MaxRetryInitialMS, &t.RetryPolicy.InitialDelayMS); err != nil {
			return nil, fmt.Errorf("retry_policy: %w", err)
		}
		if err := parseBoundedInt(retryMap, "max_delay_ms", 0, task.MaxRetryDelayMS, &t.RetryPolicy.MaxDelayMS); err != nil {
			return nil, fmt.Errorf("retry_policy: %w", err)
		}
		if err := t.RetryPolicy.Validate(); err != nil {
			return nil, fmt.Errorf("retry_policy: %w", err)
		}
		t.RetryPolicyConfigured = true
	}

	t.CreatedAt = time.Now()
	return t, nil
}

func stepFromMap(m map[string]interface{}) (*task.Step, error) {
	s := &task.Step{}

	if action, ok := m["action"].(string); ok {
		s.Action = action
	} else {
		return nil, fmt.Errorf("missing required field: action")
	}

	if args, ok := m["args"].(map[string]interface{}); ok {
		s.Args = args
	} else {
		s.Args = make(map[string]interface{})
	}

	if err := parseBoundedInt(m, "timeout_ms", task.MinStepTimeoutMS, task.MaxStepTimeoutMS, &s.TimeoutMS); err != nil {
		return nil, err
	}

	if onFailure, ok := m["on_failure"].(string); ok {
		s.OnFailure = task.StepFailurePolicy(onFailure)
	}

	if compAction, ok := m["compensate_action"].(string); ok {
		s.CompensateAction = compAction
	}
	if compArgs, ok := m["compensate_args"].(map[string]interface{}); ok {
		s.CompensateArgs = compArgs
	}

	if desc, ok := m["description"].(string); ok {
		s.Description = desc
	}

	return s, nil
}

func parseBoundedInt(m map[string]interface{}, field string, min, max int, dst *int) error {
	raw, exists := m[field]
	if !exists {
		return nil
	}
	value, ok := raw.(float64)
	if !ok || math.IsNaN(value) || math.IsInf(value, 0) || math.Trunc(value) != value || value < float64(min) || value > float64(max) {
		return fmt.Errorf("%s must be an integer between %d and %d", field, min, max)
	}
	*dst = int(value)
	return nil
}
