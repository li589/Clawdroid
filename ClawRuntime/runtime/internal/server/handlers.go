package server

import (
	"context"
	"fmt"
	"time"

	"clawdroid/runtime/internal/ipc"
	"clawdroid/runtime/internal/task"
)

var frozenActionCapabilities = map[string]string{
	"ping":               "system.ping",
	"get_capabilities":   "system.inspect",
	"capture_screen":     "screen.capture",
	"inject_tap":         "input.inject",
	"inject_swipe":       "input.inject",
	"read_file_limited":  "file.read.limited",
	"exec_shell_limited": "shell.exec.limited",
	"subscribe_events":   "event.subscribe",
	"task_submit":        "task.manage",
	"task_get":           "task.manage",
	"task_list":          "task.manage",
	"task_cancel":        "task.manage",
}

func (s *Server) handleRequest(sess *session, req ipc.Request) ipc.Response {
	switch req.Action {
	case "ping":
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
	case "get_capabilities":
		s.finalizeCapabilityState(sess)

		rootAvailable := detectRootAvailable()
		accessibilityEnabled := detectAccessibilityEnabled(sess.packageName)
		lsposedAvailable := detectLSPosedAvailable()
		lsposedRuntimeMarker := detectLSPosedRuntimeMarker(sess.packageName)

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
		if sess.degradedReason != "" {
			data["degraded_reason"] = sess.degradedReason
		}

		return ipc.Response{
			RequestID: req.RequestID,
			OK:        true,
			Code:      ipc.CodeOK,
			Message:   ipc.ErrorMessage(ipc.CodeOK),
			Data:      data,
		}
	case "capture_screen":
		s.finalizeCapabilityState(sess)
		return s.handleCaptureScreen(sess, req)
	case "read_file_limited":
		s.finalizeCapabilityState(sess)
		return s.handleReadFileLimited(sess, req)
	case "inject_tap":
		s.finalizeCapabilityState(sess)
		return s.handleInjectTap(sess, req)
	case "inject_swipe":
		s.finalizeCapabilityState(sess)
		return s.handleInjectSwipe(sess, req)
	case "exec_shell_limited":
		s.finalizeCapabilityState(sess)
		return s.handleExecShellLimited(sess, req)
	case "task_submit":
		s.finalizeCapabilityState(sess)
		return s.handleTaskSubmit(sess, req)
	case "task_get":
		s.finalizeCapabilityState(sess)
		return s.handleTaskGet(sess, req)
	case "task_list":
		s.finalizeCapabilityState(sess)
		return s.handleTaskList(sess, req)
	case "task_cancel":
		s.finalizeCapabilityState(sess)
		return s.handleTaskCancel(sess, req)
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

func (s *Server) sessionData(sess *session) map[string]interface{} {
	return map[string]interface{}{
		"session_id":         sess.id,
		"package_name":       sess.packageName,
		"signature_digest":   sess.signatureDigest,
		"session_state":      string(sess.state),
		"state_trace":        sess.traceSnapshot(),
		"auth_mode":          sess.authMode,
		"session_expires_at": sess.sessionExpiresAt(s.cfg.SessionTTLSec),
		"peer_pid":           sess.peerPID,
		"peer_uid":           sess.peerUID,
		"peer_gid":           sess.peerGID,
		"peer_verified":      sess.peerVerified,
		"peer_auth_method":   sess.peerVerificationMethod,
		"peer_package_bound": sess.peerPackageBound,
		"peer_packages":      append([]string(nil), sess.peerKnownPackages...),
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
	capabilities := []string{"system.ping", "system.inspect", "event.subscribe", "task.manage"}

	if s.cfg.ScreenshotEnabled {
		capabilities = append(capabilities, "screen.capture")
	}
	if s.cfg.InputInjectEnabled {
		capabilities = append(capabilities, "input.inject")
	}
	if s.cfg.ShellEnabled {
		capabilities = append(capabilities, "shell.exec.limited")
	}
	if s.cfg.FileBridgeEnabled {
		capabilities = append(capabilities, "file.read.limited")
	}

	return capabilities
}

func expectedCapabilityForAction(action string) (string, bool) {
	capability, ok := frozenActionCapabilities[action]
	return capability, ok
}

// handleTaskSubmit submits a new multi-step task for execution.
func (s *Server) handleTaskSubmit(sess *session, req ipc.Request) ipc.Response {
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

	// Build Task from map.
	t, err := taskFromMap(taskMap, sess.id)
	if err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}
	t.SessionID = sess.id // Ensure task is owned by this session.

	if err := s.taskScheduler.Submit(context.Background(), t); err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrTaskSubmitFailed,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data: mergeData(s.sessionData(sess), map[string]interface{}{
			"task_id": t.ID,
			"state":    string(t.State),
		}),
	}
}

// handleTaskGet retrieves the current state of a task.
func (s *Server) handleTaskGet(sess *session, req ipc.Request) ipc.Response {
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

	t, ok := s.taskScheduler.Registry().Get(sess.id, taskID)
	if !ok {
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
		Data: mergeData(s.sessionData(sess), t.StateSnapshot()),
	}
}

// handleTaskList lists all tasks for the current session.
func (s *Server) handleTaskList(sess *session, req ipc.Request) ipc.Response {
	tasks := s.taskScheduler.Registry().List(sess.id)

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

// handleTaskCancel requests cancellation of a running task.
func (s *Server) handleTaskCancel(sess *session, req ipc.Request) ipc.Response {
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

	// Verify task belongs to this session.
	if _, ok := s.taskScheduler.Registry().Get(sess.id, taskID); !ok {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrTaskNotFound,
			Message:   ipc.ErrorMessage(ipc.CodeErrTaskNotFound),
			Data:      s.sessionData(sess),
		}
	}

	ok = s.taskScheduler.Cancel(taskID)
	if !ok {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrTaskCancelFailed,
			Message:   ipc.ErrorMessage(ipc.CodeErrTaskCancelFailed),
			Data:      s.sessionData(sess),
		}
	}

	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data: mergeData(s.sessionData(sess), map[string]interface{}{
			"task_id": taskID,
			"cancelled": true,
		}),
	}
}

// taskFromMap constructs a Task from a map. SessionID is not set here.
func taskFromMap(m map[string]interface{}, sessionID string) (*task.Task, error) {
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

	// Parse retry policy if provided.
	if rawRetry, ok := m["retry_policy"]; ok {
		if retryMap, ok := rawRetry.(map[string]interface{}); ok {
			if ma, ok := retryMap["max_attempts"].(float64); ok {
				t.RetryPolicy.MaxAttempts = int(ma)
			}
			if id, ok := retryMap["initial_delay_ms"].(float64); ok {
				t.RetryPolicy.InitialDelayMS = int(id)
			}
			if md, ok := retryMap["max_delay_ms"].(float64); ok {
				t.RetryPolicy.MaxDelayMS = int(md)
			}
		}
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

	if timeout, ok := m["timeout_ms"].(float64); ok {
		s.TimeoutMS = int(timeout)
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
