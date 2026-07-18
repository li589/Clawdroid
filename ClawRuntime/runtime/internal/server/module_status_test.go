package server

import (
	"os"
	"path/filepath"
	"testing"

	"clawdroid/runtime/internal/ipc"
)

func TestExpectedCapabilityForNewActions(t *testing.T) {
	cases := map[string]string{
		ipc.ActionGetRuntimeStatus: ipc.CapabilitySystemInspect,
		ipc.ActionInjectKeyevent:   ipc.CapabilityInputInject,
		ipc.ActionPing:             ipc.CapabilitySystemPing,
	}
	for action, want := range cases {
		got, ok := expectedCapabilityForAction(action)
		if !ok {
			t.Fatalf("action %s missing from catalog", action)
		}
		if got != want {
			t.Fatalf("action %s capability=%s want=%s", action, got, want)
		}
	}
}

func TestParseInjectKeyeventArgs(t *testing.T) {
	byName, err := parseInjectKeyeventArgs(map[string]interface{}{"key": "BACK"})
	if err != nil {
		t.Fatalf("parse by name: %v", err)
	}
	if byName.KeyCode != 4 || byName.KeyName != "BACK" {
		t.Fatalf("unexpected keyevent by name: %+v", byName)
	}

	byCode, err := parseInjectKeyeventArgs(map[string]interface{}{"keycode": float64(66)})
	if err != nil {
		t.Fatalf("parse by code: %v", err)
	}
	if byCode.KeyCode != 66 || byCode.KeyName != "ENTER" {
		t.Fatalf("unexpected keyevent by code: %+v", byCode)
	}

	if _, err := parseInjectKeyeventArgs(map[string]interface{}{"key": "POWER"}); err == nil {
		t.Fatal("expected POWER to be rejected")
	}
}

func TestDetectMagiskModuleStatusFromTempTree(t *testing.T) {
	root := t.TempDir()
	moduleDir := filepath.Join(root, "clawruntime")
	webroot := filepath.Join(moduleDir, "webroot")
	if err := os.MkdirAll(filepath.Join(moduleDir, "bin"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(moduleDir, "config"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(webroot, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(moduleDir, "bin", "clawdroid-runtime"), []byte("x"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(moduleDir, "config", "runtime.yaml"), []byte("runtime:\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(webroot, "status.json"), []byte(`{"runtime_state":"running","runtime_pid":42}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(webroot, "verify.json"), []byte(`{"status":"ok","summary":"all good"}`), 0o644); err != nil {
		t.Fatal(err)
	}

	magiskModulePathOverride = moduleDir
	defer func() { magiskModulePathOverride = "" }()

	status := detectMagiskModuleStatus()
	if !status.Installed || !status.Enabled {
		t.Fatalf("expected installed/enabled module, got %+v", status)
	}
	if !status.RuntimeBinExists || !status.ConfigExists || !status.WebrootExists {
		t.Fatalf("expected module files present, got %+v", status)
	}
	if status.RuntimeState != "running" || status.RuntimePID != 42 {
		t.Fatalf("unexpected runtime fields: %+v", status)
	}
	if status.VerifyStatus != "ok" || status.VerifySummary != "all good" {
		t.Fatalf("unexpected verify fields: %+v", status)
	}
}

func TestSortedActionNamesIncludesExtensions(t *testing.T) {
	actions := sortedActionNames()
	want := map[string]bool{
		ipc.ActionGetRuntimeStatus: false,
		ipc.ActionInjectKeyevent:   false,
		ipc.ActionTaskSubmit:       false,
	}
	for _, action := range actions {
		if _, ok := want[action]; ok {
			want[action] = true
		}
	}
	for action, found := range want {
		if !found {
			t.Fatalf("missing action in catalog list: %s", action)
		}
	}
}
