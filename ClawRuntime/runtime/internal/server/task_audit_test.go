package server

import (
	"bufio"
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"clawdroid/runtime/internal/audit"
	"clawdroid/runtime/internal/config"
	"clawdroid/runtime/internal/ipc"
	"clawdroid/runtime/internal/task"
)

func TestExecuteStepAppliesPrincipalRateLimit(t *testing.T) {
	t.Parallel()

	srv := newLifecycleServer(t, nil)
	srv.cfg.RateLimitPerMinute = 2
	srv.rateLimiter = newPrincipalRateLimiter(2, maxTrackedRateLimitPrincipals)
	owner := newLifecycleSession(srv, "com.example.app", 10123, "sha256:signature-a")
	srv.registerSession(owner)
	submitLifecycleTask(t, srv, owner, "rate-task", ipc.ActionPing, map[string]interface{}{}, "")
	waitForLifecycleTaskState(t, srv, "rate-task", task.TaskStateSucceeded)

	first, _, _, _ := srv.ExecuteStep(context.Background(), owner.id, "rate-task", ipc.ActionGetCapabilities, nil, 0)
	second, _, _, _ := srv.ExecuteStep(context.Background(), owner.id, "rate-task", ipc.ActionGetCapabilities, nil, 0)
	if first != ipc.CodeOK {
		t.Fatalf("first step code = %d, want 0", first)
	}
	if second != ipc.CodeErrRateLimited {
		t.Fatalf("second step code = %d, want %d", second, ipc.CodeErrRateLimited)
	}
}

func TestExecuteStepWritesAuditRecord(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	logger, err := audit.NewLoggerWithFileLogger(dir)
	if err != nil {
		t.Fatalf("NewLoggerWithFileLogger: %v", err)
	}
	defer logger.Close()
	cfg := config.Default()
	cfg.AuditDir = dir
	srv := New(cfg, logger)
	t.Cleanup(srv.taskScheduler.Close)
	owner := newLifecycleSession(srv, "com.example.app", 10123, "sha256:signature-a")
	srv.registerSession(owner)
	submitLifecycleTask(t, srv, owner, "audit-task", ipc.ActionPing, map[string]interface{}{}, "")
	waitForLifecycleTaskState(t, srv, "audit-task", task.TaskStateSucceeded)

	code, _, _, _ := srv.ExecuteStep(context.Background(), owner.id, "audit-task", ipc.ActionGetCapabilities, nil, 0)
	if code != ipc.CodeOK {
		t.Fatalf("ExecuteStep code = %d", code)
	}

	files, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("ReadDir: %v", err)
	}
	found := false
	for _, file := range files {
		if !strings.HasPrefix(file.Name(), "audit-") || !strings.HasSuffix(file.Name(), ".jsonl") {
			continue
		}
		path := filepath.Join(dir, file.Name())
		stream, err := os.Open(path)
		if err != nil {
			t.Fatalf("open audit file: %v", err)
		}
		scanner := bufio.NewScanner(stream)
		for scanner.Scan() {
			var entry audit.AuditLogEntry
			if err := json.Unmarshal(scanner.Bytes(), &entry); err != nil {
				_ = stream.Close()
				t.Fatalf("decode audit entry: %v", err)
			}
			if entry.Action == ipc.ActionGetCapabilities && entry.PackageName == "com.example.app" && strings.HasPrefix(entry.RequestID, "task-step-") {
				found = true
			}
		}
		_ = stream.Close()
	}
	if !found {
		t.Fatal("no task-step audit record found")
	}
}
