package server

import (
	"time"

	"clawdroid/runtime/internal/ipc"
)

var frozenActionCapabilities = map[string]string{
	"ping":              "system.ping",
	"get_capabilities":  "system.inspect",
	"capture_screen":    "screen.capture",
	"inject_tap":        "input.inject",
	"inject_swipe":      "input.inject",
	"read_file_limited": "file.read.limited",
	"exec_shell_limited": "shell.exec.limited",
	"subscribe_events":  "event.subscribe",
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
	for key, value := range extra {
		base[key] = value
	}
	return base
}

func (s *Server) capabilityList() []string {
	capabilities := []string{"system.ping", "system.inspect", "event.subscribe"}

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
