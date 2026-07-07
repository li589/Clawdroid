package server

import (
	"path/filepath"
	"reflect"
	"testing"
)

func TestContainsAccessibilityServiceMatchesConfiguredComponent(t *testing.T) {
	t.Parallel()

	enabledServices := "com.other/.OtherService: com.clawdroid.app/.ClawAccessibilityService "
	if !containsAccessibilityService(enabledServices, "com.clawdroid.app") {
		t.Fatal("expected accessibility service entry to be detected")
	}
}

func TestContainsAccessibilityServiceRejectsOtherPackages(t *testing.T) {
	t.Parallel()

	enabledServices := "com.other/.ClawAccessibilityService"
	if containsAccessibilityService(enabledServices, "com.clawdroid.app") {
		t.Fatal("expected accessibility service from another package to be ignored")
	}
}

func TestLSPosedMarkerCandidatePaths(t *testing.T) {
	t.Parallel()

	got := lsposedMarkerCandidatePaths("com.clawdroid.app")
	want := []string{
		filepath.Join("/data/user/0", "com.clawdroid.app", "files", "xposed_runtime_marker.json"),
		filepath.Join("/data/data", "com.clawdroid.app", "files", "xposed_runtime_marker.json"),
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("unexpected marker candidate paths: got %#v want %#v", got, want)
	}
}

func TestParseLSPosedRuntimeMarkerAcceptsMatchingPackage(t *testing.T) {
	t.Parallel()

	content := []byte(`{"xposed_injected":true,"process_name":"zygote64","loaded_at_epoch_ms":123,"package_name":"com.clawdroid.app"}`)
	got, ok := parseLSPosedRuntimeMarker(content, "com.clawdroid.app")
	if !ok {
		t.Fatal("expected runtime marker to parse successfully")
	}
	if !got.XposedInjected || got.ProcessName != "zygote64" || got.LoadedAtEpochMS != 123 {
		t.Fatalf("unexpected parsed marker: %#v", got)
	}
}

func TestParseLSPosedRuntimeMarkerRejectsMismatchedPackage(t *testing.T) {
	t.Parallel()

	content := []byte(`{"xposed_injected":true,"package_name":"com.other.app"}`)
	if _, ok := parseLSPosedRuntimeMarker(content, "com.clawdroid.app"); ok {
		t.Fatal("expected mismatched marker package to be rejected")
	}
}

func TestParseLSPosedRuntimeMarkerRejectsInvalidJSON(t *testing.T) {
	t.Parallel()

	if _, ok := parseLSPosedRuntimeMarker([]byte("{invalid"), "com.clawdroid.app"); ok {
		t.Fatal("expected invalid json marker to be rejected")
	}
}
