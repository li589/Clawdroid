package server

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"clawdroid/runtime/internal/config"
)

func TestParseXposedFocusArgsRequiresPackageAndActivity(t *testing.T) {
	t.Parallel()

	if _, err := parseXposedFocusArgs(map[string]interface{}{
		"focus_json": `{"package_name":"com.android.settings"}`,
	}); err == nil {
		t.Fatal("expected missing activity_class to fail")
	}

	state, err := parseXposedFocusArgs(map[string]interface{}{
		"focus_json": `{
			"schema_version":2,
			"package_name":"com.android.settings",
			"activity_class":"com.android.settings.Settings",
			"adapter_id":"settings_detail",
			"active":true
		}`,
	})
	if err != nil {
		t.Fatalf("parse focus args: %v", err)
	}
	if state["package_name"] != "com.android.settings" {
		t.Fatalf("unexpected package_name: %#v", state["package_name"])
	}
	if state["source"] != "report" {
		t.Fatalf("unexpected source: %#v", state["source"])
	}
	if _, ok := state["focus_json"].(string); !ok {
		t.Fatal("expected focus_json string in state")
	}
}

func TestCaptureXposedFocusStateFiltersStaleAndUntimedSnapshots(t *testing.T) {
	t.Parallel()

	now := time.Now()
	stale := now.Add(-31 * time.Second).UnixMilli()
	fresh := now.UnixMilli()
	for _, tc := range []struct {
		name    string
		memory  map[string]interface{}
		file    map[string]interface{}
		want    string
		wantSrc string
	}{
		{
			name:    "fresh memory survives stale file",
			memory:  map[string]interface{}{"package_name": "com.example.memory", "source": "report", "reported_at_epoch_ms": fresh},
			file:    map[string]interface{}{"package_name": "com.example.file", "activity_class": "FileActivity", "loaded_at_epoch_ms": stale},
			want:    "com.example.memory",
			wantSrc: "report",
		},
		{
			name:    "fresh file survives stale memory",
			memory:  map[string]interface{}{"package_name": "com.example.memory", "source": "report", "reported_at_epoch_ms": stale},
			file:    map[string]interface{}{"package_name": "com.example.file", "activity_class": "FileActivity", "loaded_at_epoch_ms": fresh},
			want:    "com.example.file",
			wantSrc: "file",
		},
		{
			name:   "stale memory is empty",
			memory: map[string]interface{}{"package_name": "com.example.memory", "source": "report", "reported_at_epoch_ms": stale},
		},
		{
			name: "stale file is empty",
			file: map[string]interface{}{"package_name": "com.example.file", "activity_class": "FileActivity", "loaded_at_epoch_ms": stale},
		},
		{
			name:   "future memory is empty",
			memory: map[string]interface{}{"package_name": "com.example.memory", "source": "report", "reported_at_epoch_ms": now.Add(time.Minute).UnixMilli()},
		},
		{
			name:   "untimed memory is empty",
			memory: map[string]interface{}{"package_name": "com.example.memory"},
		},
		{
			name: "untimed file is empty",
			file: map[string]interface{}{"package_name": "com.example.file", "activity_class": "FileActivity"},
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			auditDir := filepath.Join(t.TempDir(), "audit")
			srv := &Server{cfg: config.Config{AuditDir: auditDir}}
			srv.latestXposedFocus = tc.memory
			if tc.file != nil {
				writeXposedFocusFileForTest(t, auditDir, tc.file)
			}

			got := srv.captureXposedFocusState()
			if tc.want == "" {
				if len(got) != 0 {
					t.Fatalf("stale or untimed focus snapshot was returned: %#v", got)
				}
				return
			}
			if got["package_name"] != tc.want || got["source"] != tc.wantSrc {
				t.Fatalf("unexpected focus snapshot: %#v", got)
			}
		})
	}
}

func writeXposedFocusFileForTest(t *testing.T, auditDir string, state map[string]interface{}) {
	t.Helper()
	path := filepath.Join(filepath.Dir(auditDir), xposedFocusLatestRelative)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatalf("create xposed fixture dir: %v", err)
	}
	payload, err := json.Marshal(state)
	if err != nil {
		t.Fatalf("marshal xposed fixture: %v", err)
	}
	if err := os.WriteFile(path, payload, 0o600); err != nil {
		t.Fatalf("write xposed fixture: %v", err)
	}
}
