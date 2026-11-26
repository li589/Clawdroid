package server

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"

	"clawdroid/runtime/internal/config"
)

func TestParseXposedViewArgsRequiresPackageAndActivity(t *testing.T) {
	t.Parallel()

	if _, err := parseXposedViewArgs(map[string]interface{}{
		"view_json": `{"package_name":"com.android.settings"}`,
	}); err == nil {
		t.Fatal("expected missing activity_class to fail")
	}

	state, err := parseXposedViewArgs(map[string]interface{}{
		"view_json": `{
			"schema_version":1,
			"package_name":"com.android.settings",
			"activity_class":"com.android.settings.Settings",
			"node_count":3,
			"compose_surface":false
		}`,
	})
	if err != nil {
		t.Fatalf("parse view args: %v", err)
	}
	if state["package_name"] != "com.android.settings" {
		t.Fatalf("unexpected package_name: %#v", state["package_name"])
	}
	if state["source"] != "report" {
		t.Fatalf("unexpected source: %#v", state["source"])
	}
}

func TestCaptureXposedViewStateFiltersStaleAndUntimedSnapshots(t *testing.T) {
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
			srv.latestXposedView = tc.memory
			if tc.file != nil {
				writeXposedViewFileForTest(t, auditDir, tc.file)
			}

			got := srv.captureXposedViewState()
			if tc.want == "" {
				if len(got) != 0 {
					t.Fatalf("stale or untimed view snapshot was returned: %#v", got)
				}
				return
			}
			if got["package_name"] != tc.want || got["source"] != tc.wantSrc {
				t.Fatalf("unexpected view snapshot: %#v", got)
			}
		})
	}
}

func writeXposedViewFileForTest(t *testing.T, auditDir string, state map[string]interface{}) {
	t.Helper()
	path := filepath.Join(filepath.Dir(auditDir), xposedViewLatestRelative)
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
