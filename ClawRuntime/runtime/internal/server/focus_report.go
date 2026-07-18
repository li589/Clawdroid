package server

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"time"

	"clawdroid/runtime/internal/ipc"
)

const xposedFocusLatestRelative = "xposed/focus_latest.json"

func (s *Server) handleReportXposedFocus(sess *session, req ipc.Request) ipc.Response {
	if !slices.Contains(sess.capabilities, ipc.CapabilityEventReport) {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrCapabilityNotGranted,
			Message:   "event.report capability not granted",
			Data:      s.sessionData(sess),
		}
	}

	state, err := parseXposedFocusArgs(req.Args)
	if err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	s.focusMu.Lock()
	s.latestXposedFocus = state
	s.focusMu.Unlock()
	s.notifyEventWake()

	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data: mergeData(s.sessionData(sess), map[string]interface{}{
			"accepted":            true,
			"updated_at_epoch_ms": state["reported_at_epoch_ms"],
			"package_name":        state["package_name"],
			"activity_class":      state["activity_class"],
		}),
	}
}

func parseXposedFocusArgs(args map[string]interface{}) (map[string]interface{}, error) {
	rawJSON, _ := args["focus_json"].(string)
	rawJSON = strings.TrimSpace(rawJSON)
	if rawJSON == "" {
		return nil, fmt.Errorf("focus_json must be a non-empty string")
	}
	if !strings.HasPrefix(rawJSON, "{") {
		return nil, fmt.Errorf("focus_json must be a JSON object")
	}

	var parsed map[string]interface{}
	if err := json.Unmarshal([]byte(rawJSON), &parsed); err != nil {
		return nil, fmt.Errorf("focus_json is not valid JSON: %w", err)
	}
	packageName := strings.TrimSpace(fmt.Sprint(parsed["package_name"]))
	activityClass := strings.TrimSpace(fmt.Sprint(parsed["activity_class"]))
	if packageName == "" || packageName == "<nil>" || activityClass == "" || activityClass == "<nil>" {
		return nil, fmt.Errorf("focus_json requires package_name and activity_class")
	}

	state := copyMap(parsed)
	state["focus_json"] = rawJSON
	state["package_name"] = packageName
	state["activity_class"] = activityClass
	state["reported_at_epoch_ms"] = time.Now().UnixMilli()
	state["source"] = "report"
	return state, nil
}

func (s *Server) captureXposedFocusState() map[string]interface{} {
	s.focusMu.RLock()
	reported := copyMap(s.latestXposedFocus)
	s.focusMu.RUnlock()
	file := readXposedFocusLatestFile(s.cfg.AuditDir)
	return pickNewerXposedState(reported, file)
}

func readXposedFocusLatestFile(auditDir string) map[string]interface{} {
	if strings.TrimSpace(auditDir) == "" {
		return map[string]interface{}{}
	}
	path := filepath.Join(filepath.Dir(auditDir), xposedFocusLatestRelative)
	raw, err := os.ReadFile(path)
	if err != nil {
		return map[string]interface{}{}
	}
	trimmed := strings.TrimSpace(string(raw))
	if trimmed == "" || !strings.HasPrefix(trimmed, "{") {
		return map[string]interface{}{}
	}
	var parsed map[string]interface{}
	if err := json.Unmarshal([]byte(trimmed), &parsed); err != nil {
		return map[string]interface{}{}
	}
	packageName := strings.TrimSpace(fmt.Sprint(parsed["package_name"]))
	activityClass := strings.TrimSpace(fmt.Sprint(parsed["activity_class"]))
	if packageName == "" || packageName == "<nil>" || activityClass == "" || activityClass == "<nil>" {
		return map[string]interface{}{}
	}
	state := copyMap(parsed)
	state["focus_json"] = trimmed
	state["package_name"] = packageName
	state["activity_class"] = activityClass
	state["source"] = "file"
	return state
}
