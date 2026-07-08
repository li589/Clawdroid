package audit

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestComputeArgDigest(t *testing.T) {
	t.Parallel()

	digest := ComputeArgDigest(map[string]interface{}{
		"x":          float64(540),
		"y":          float64(1200),
		"display_id": float64(0),
	})
	if digest == "" {
		t.Fatal("expected non-empty digest")
	}
	if len(digest) != 16 {
		t.Fatalf("expected 16-char truncated digest, got %d chars", len(digest))
	}

	digest2 := ComputeArgDigest(map[string]interface{}{
		"x":          float64(540),
		"y":          float64(1200),
		"display_id": float64(0),
	})
	if digest != digest2 {
		t.Fatal("expected deterministic digest")
	}

	digest3 := ComputeArgDigest(map[string]interface{}{
		"x":          float64(999),
		"y":          float64(1200),
		"display_id": float64(0),
	})
	if digest == digest3 {
		t.Fatal("expected different args to produce different digest")
	}

	empty := ComputeArgDigest(nil)
	if empty != "" {
		t.Fatalf("expected empty digest for nil args, got %q", empty)
	}

	empty = ComputeArgDigest(map[string]interface{}{})
	if empty != "" {
		t.Fatalf("expected empty digest for empty args, got %q", empty)
	}
}

func TestAuditLevelForAction(t *testing.T) {
	t.Parallel()

	tests := []struct {
		action string
		level  AuditLevel
	}{
		{"ping", AuditLevelLow},
		{"get_capabilities", AuditLevelLow},
		{"subscribe_events", AuditLevelLow},
		{"capture_screen", AuditLevelMedium},
		{"read_file_limited", AuditLevelMedium},
		{"inject_tap", AuditLevelHigh},
		{"inject_swipe", AuditLevelHigh},
		{"exec_shell_limited", AuditLevelHigh},
		{"unknown_action", AuditLevelMedium},
	}

	for _, tc := range tests {
		t.Run(tc.action, func(t *testing.T) {
			if got := AuditLevelForAction(tc.action); got != tc.level {
				t.Fatalf("got %q want %q", got, tc.level)
			}
		})
	}
}

func TestFileLoggerWritesJSONL(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()

	fl, err := NewFileLogger(dir)
	if err != nil {
		t.Fatalf("create file logger: %v", err)
	}
	defer fl.Close()

	entry := AuditLogEntry{
		RequestID:   "req-001",
		SessionID:   "sess-002",
		CallerUID:   1000,
		CallerPID:   1234,
		PackageName: "com.clawdroid.app",
		Action:      "inject_tap",
		ArgDigest:  "a1b2c3d4e5f60000",
		AuditLevel: AuditLevelHigh,
		StartedAt:  1234567890,
		EndedAt:    1234567895,
		ResultCode: 0,
		ErrorMessage: "",
		LatencyMS:   5,
		DaemonVersion: "0.2.0",
	}

	if err := fl.Log(entry); err != nil {
		t.Fatalf("log entry: %v", err)
	}

	files, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("read audit dir: %v", err)
	}

	var found string
	for _, f := range files {
		if f.Name() == "audit-high-"+filepath.Base(dir)+".jsonl" {
			continue
		}
		if len(f.Name()) > 20 && f.Name()[:11] == "audit-high-" {
			found = f.Name()
			break
		}
	}

	filename := "audit-high.jsonl"
	for _, f := range files {
		if f.Name()[:11] == "audit-high-" {
			filename = f.Name()
			found = f.Name()
			break
		}
	}

	if found == "" {
		t.Fatalf("no audit-high-*.jsonl file found in %s, files: %v", dir, files)
	}

	path := filepath.Join(dir, filename)
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read audit file: %v", err)
	}

	var decoded AuditLogEntry
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("decode JSONL line: %v", err)
	}

	if decoded.RequestID != entry.RequestID {
		t.Fatalf("request_id: got %q want %q", decoded.RequestID, entry.RequestID)
	}
	if decoded.SessionID != entry.SessionID {
		t.Fatalf("session_id: got %q want %q", decoded.SessionID, entry.SessionID)
	}
	if decoded.Action != entry.Action {
		t.Fatalf("action: got %q want %q", decoded.Action, entry.Action)
	}
	if decoded.ArgDigest != entry.ArgDigest {
		t.Fatalf("arg_digest: got %q want %q", decoded.ArgDigest, entry.ArgDigest)
	}
	if decoded.ResultCode != entry.ResultCode {
		t.Fatalf("result_code: got %d want %d", decoded.ResultCode, entry.ResultCode)
	}
	if decoded.LatencyMS != entry.LatencyMS {
		t.Fatalf("latency_ms: got %d want %d", decoded.LatencyMS, entry.LatencyMS)
	}
}

func TestVersionInfo(t *testing.T) {
	t.Parallel()

	SetVersionInfo("1.0.0", "2025-01-01")
	version, buildTime := GetVersionInfo()

	if version != "1.0.0" {
		t.Fatalf("version: got %q want %q", version, "1.0.0")
	}
	if buildTime != "2025-01-01" {
		t.Fatalf("build_time: got %q want %q", buildTime, "2025-01-01")
	}
}
