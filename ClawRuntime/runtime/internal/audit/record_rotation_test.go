package audit

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestComputeArgDigestIncludesNestedValues(t *testing.T) {
	t.Parallel()

	first := ComputeArgDigest(map[string]interface{}{
		"task": map[string]interface{}{"steps": []interface{}{map[string]interface{}{"action": "ping"}}},
	})
	second := ComputeArgDigest(map[string]interface{}{
		"task": map[string]interface{}{"steps": []interface{}{map[string]interface{}{"action": "inject_tap"}}},
	})
	if first == "" || first == second {
		t.Fatalf("nested arguments produced identical digests: %q vs %q", first, second)
	}
}

func TestFileLoggerRotationUsesUniqueShards(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	fl, err := NewFileLogger(dir)
	if err != nil {
		t.Fatalf("NewFileLogger: %v", err)
	}
	defer fl.Close()
	fl.maxFileSize = 1

	for i := 0; i < 4; i++ {
		entry := AuditLogEntry{RequestID: "req", Action: "ping", AuditLevel: AuditLevelLow}
		if err := fl.Log(entry); err != nil {
			t.Fatalf("Log %d: %v", i, err)
		}
	}

	files, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("ReadDir: %v", err)
	}
	shards := 0
	for _, file := range files {
		name := file.Name()
		if strings.HasPrefix(name, "audit-low-") && strings.HasSuffix(name, ".jsonl") && name != "audit-low-"+time.Now().Format("20060102")+".jsonl" {
			shards++
		}
	}
	if shards < 2 {
		t.Fatalf("expected at least 2 unique rotated shards, got %d: %v", shards, files)
	}
}

func TestFileLoggerRotateReopenFailureDoesNotDeadlock(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	fl, err := NewFileLogger(dir)
	if err != nil {
		t.Fatalf("NewFileLogger: %v", err)
	}
	defer fl.Close()
	if err := fl.Log(AuditLogEntry{RequestID: "req", Action: "ping", AuditLevel: AuditLevelLow}); err != nil {
		t.Fatalf("initial Log: %v", err)
	}

	blockedPath := filepath.Join(dir, "not-a-directory")
	if err := os.WriteFile(blockedPath, []byte("blocked"), 0o600); err != nil {
		t.Fatalf("create blocker: %v", err)
	}
	fl.auditDir = blockedPath

	done := make(chan struct{})
	go func() {
		fl.rotate(AuditLevelLow, time.Now().Format("20060102"))
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("rotate deadlocked while recording reopen failure")
	}
	if message, _ := fl.LastError(); !strings.Contains(message, "rotate") {
		t.Fatalf("expected rotation error in LastError, got %q", message)
	}
}
