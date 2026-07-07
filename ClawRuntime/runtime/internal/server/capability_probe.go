package server

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

const accessibilityServiceClass = "ClawAccessibilityService"

type lsposedRuntimeMarker struct {
	XposedInjected  bool   `json:"xposed_injected"`
	ProcessName     string `json:"process_name"`
	LoadedAtEpochMS int64  `json:"loaded_at_epoch_ms"`
	PackageName     string `json:"package_name"`
}

func detectRootAvailable() bool {
	if runtime.GOOS != "android" && runtime.GOOS != "linux" {
		return false
	}

	command := exec.Command("id", "-u")
	output, err := command.Output()
	if err != nil {
		return false
	}
	return strings.TrimSpace(string(output)) == "0"
}

func detectAccessibilityEnabled(packageName string) bool {
	if runtime.GOOS != "android" && runtime.GOOS != "linux" {
		return false
	}

	enabledFlag, err := runCommandTrimmed("settings", "get", "secure", "accessibility_enabled")
	if err != nil || enabledFlag != "1" {
		return false
	}

	enabledServices, err := runCommandTrimmed("settings", "get", "secure", "enabled_accessibility_services")
	if err != nil || enabledServices == "" || enabledServices == "null" {
		return false
	}

	return containsAccessibilityService(enabledServices, packageName)
}

func detectLSPosedAvailable() bool {
	if runtime.GOOS != "android" && runtime.GOOS != "linux" {
		return false
	}

	paths := []string{
		"/data/adb/lspd",
		"/data/adb/modules/zygisk_lsposed",
		"/data/adb/modules/riru_lsposed",
		"/data/adb/modules/lsposed",
	}
	for _, path := range paths {
		if _, err := os.Stat(path); err == nil {
			return true
		}
	}

	return false
}

func detectLSPosedRuntimeMarker(packageName string) lsposedRuntimeMarker {
	if runtime.GOOS != "android" && runtime.GOOS != "linux" {
		return lsposedRuntimeMarker{}
	}

	for _, markerPath := range lsposedMarkerCandidatePaths(packageName) {
		content, err := os.ReadFile(markerPath)
		if err != nil {
			continue
		}

		if marker, ok := parseLSPosedRuntimeMarker(content, packageName); ok {
			return marker
		}
	}

	return lsposedRuntimeMarker{}
}

func containsAccessibilityService(enabledServices string, packageName string) bool {
	for _, entry := range strings.Split(enabledServices, ":") {
		service := strings.TrimSpace(entry)
		if service == "" {
			continue
		}
		if strings.Contains(service, packageName+"/") && strings.Contains(service, accessibilityServiceClass) {
			return true
		}
	}
	return false
}

func lsposedMarkerCandidatePaths(packageName string) []string {
	return []string{
		filepath.Join("/data/user/0", packageName, "files", "xposed_runtime_marker.json"),
		filepath.Join("/data/data", packageName, "files", "xposed_runtime_marker.json"),
	}
}

func parseLSPosedRuntimeMarker(content []byte, packageName string) (lsposedRuntimeMarker, bool) {
	var marker lsposedRuntimeMarker
	if err := json.Unmarshal(content, &marker); err != nil {
		return lsposedRuntimeMarker{}, false
	}
	if marker.PackageName != "" && marker.PackageName != packageName {
		return lsposedRuntimeMarker{}, false
	}
	return marker, true
}

func runCommandTrimmed(name string, args ...string) (string, error) {
	command := exec.Command(name, args...)
	output, err := command.Output()
	if err != nil {
		return "", fmt.Errorf("run %s failed: %w", name, err)
	}
	return strings.TrimSpace(string(output)), nil
}
