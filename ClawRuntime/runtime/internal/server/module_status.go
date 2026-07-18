package server

import (
	"encoding/json"
	"os"
	"path/filepath"
)

const (
	magiskModuleID   = "clawruntime"
	magiskModulePath = "/data/adb/modules/clawruntime"
)

// Test-only override for module path discovery.
var magiskModulePathOverride string

func resolveMagiskModulePath() string {
	if magiskModulePathOverride != "" {
		return magiskModulePathOverride
	}
	return magiskModulePath
}

type magiskModuleStatus struct {
	ModuleID         string `json:"module_id"`
	ModulePath       string `json:"module_path"`
	Installed        bool   `json:"installed"`
	Enabled          bool   `json:"enabled"`
	DisableMarked    bool   `json:"disable_marked"`
	RemoveMarked     bool   `json:"remove_marked"`
	RuntimeBinExists bool   `json:"runtime_bin_exists"`
	ConfigExists     bool   `json:"config_exists"`
	WebrootExists    bool   `json:"webroot_exists"`
	StatusJSONExists bool   `json:"status_json_exists"`
	VerifyJSONExists bool   `json:"verify_json_exists"`
	RuntimeState     string `json:"runtime_state"`
	RuntimePID       int    `json:"runtime_pid"`
	VerifyStatus     string `json:"verify_status"`
	VerifySummary    string `json:"verify_summary"`
}

func detectMagiskModuleStatus() magiskModuleStatus {
	modulePath := resolveMagiskModulePath()
	status := magiskModuleStatus{
		ModuleID:     magiskModuleID,
		ModulePath:   modulePath,
		RuntimeState: "unknown",
		VerifyStatus: "unknown",
	}

	status.Installed = pathExists(modulePath)
	if !status.Installed {
		status.Enabled = false
		status.RuntimeState = "module_missing"
		return status
	}

	status.DisableMarked = pathExists(filepath.Join(modulePath, "disable"))
	status.RemoveMarked = pathExists(filepath.Join(modulePath, "remove"))
	status.Enabled = !status.DisableMarked && !status.RemoveMarked
	status.RuntimeBinExists = pathExists(filepath.Join(modulePath, "bin", "clawdroid-runtime"))
	status.ConfigExists = pathExists(filepath.Join(modulePath, "config", "runtime.yaml"))
	status.WebrootExists = pathExists(filepath.Join(modulePath, "webroot"))
	status.StatusJSONExists = pathExists(filepath.Join(modulePath, "webroot", "status.json"))
	status.VerifyJSONExists = pathExists(filepath.Join(modulePath, "webroot", "verify.json"))

	if content, err := os.ReadFile(filepath.Join(modulePath, "webroot", "status.json")); err == nil {
		var payload map[string]interface{}
		if json.Unmarshal(content, &payload) == nil {
			if value, ok := payload["runtime_state"].(string); ok && value != "" {
				status.RuntimeState = value
			}
			if value, ok := payload["runtime_pid"].(float64); ok {
				status.RuntimePID = int(value)
			}
		}
	}

	if content, err := os.ReadFile(filepath.Join(modulePath, "webroot", "verify.json")); err == nil {
		var payload map[string]interface{}
		if json.Unmarshal(content, &payload) == nil {
			if value, ok := payload["status"].(string); ok && value != "" {
				status.VerifyStatus = value
			}
			if value, ok := payload["summary"].(string); ok {
				status.VerifySummary = value
			}
		}
	}

	if status.RuntimeState == "unknown" {
		if status.Enabled {
			status.RuntimeState = "installed"
		} else {
			status.RuntimeState = "disabled"
		}
	}

	return status
}

func (s magiskModuleStatus) asMap() map[string]interface{} {
	return map[string]interface{}{
		"module_id":          s.ModuleID,
		"module_path":        s.ModulePath,
		"installed":          s.Installed,
		"enabled":            s.Enabled,
		"disable_marked":     s.DisableMarked,
		"remove_marked":      s.RemoveMarked,
		"runtime_bin_exists": s.RuntimeBinExists,
		"config_exists":      s.ConfigExists,
		"webroot_exists":     s.WebrootExists,
		"status_json_exists": s.StatusJSONExists,
		"verify_json_exists": s.VerifyJSONExists,
		"runtime_state":      s.RuntimeState,
		"runtime_pid":        s.RuntimePID,
		"verify_status":      s.VerifyStatus,
		"verify_summary":     s.VerifySummary,
	}
}

func pathExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}
