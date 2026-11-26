package server

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"testing"
	"time"
)

const commandHelperEnv = "CLAWDROID_TEST_COMMAND_HELPER"

// TestCommandHelperProcess is re-executed as a real child/grandchild process by
// the Windows pipe-lifecycle test. It is a no-op during normal test discovery.
func TestCommandHelperProcess(t *testing.T) {
	switch os.Getenv(commandHelperEnv) {
	case "":
		return
	case "descendant":
		time.Sleep(10 * time.Second)
		os.Exit(0)
	case "pipe-holder":
		executable, err := os.Executable()
		if err != nil {
			os.Exit(2)
		}
		child := exec.Command(executable, "-test.run=^TestCommandHelperProcess$")
		child.Env = append(os.Environ(), commandHelperEnv+"=descendant")
		child.Stdout = os.Stdout
		child.Stderr = os.Stderr
		if err := child.Start(); err != nil {
			os.Exit(2)
		}
		if err := os.WriteFile(os.Getenv("CLAWDROID_TEST_DESCENDANT_PID"), []byte(strconv.Itoa(child.Process.Pid)), 0o600); err != nil {
			os.Exit(2)
		}
		if err := os.WriteFile(os.Getenv("CLAWDROID_TEST_COMMAND_READY"), []byte("ready"), 0o600); err != nil {
			os.Exit(2)
		}
		time.Sleep(30 * time.Second)
		os.Exit(0)
	default:
		os.Exit(2)
	}
}

func waitForCommandFile(t *testing.T, path string, timeout time.Duration) bool {
	t.Helper()
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	ticker := time.NewTicker(time.Millisecond)
	defer ticker.Stop()
	for {
		if _, err := os.Stat(path); err == nil {
			return true
		}
		select {
		case <-ticker.C:
		case <-deadline.C:
			return false
		}
	}
}

func TestExecuteLimitedShellCancelsUnixProcessGroup(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix process-group semantics are not available on Windows")
	}
	sh, err := exec.LookPath("sh")
	if err != nil {
		t.Skip("sh is unavailable")
	}

	dir := t.TempDir()
	readyPath := filepath.Join(dir, "ready")
	markerPath := filepath.Join(dir, "survived")
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	resultCh := make(chan shellExecResult, 1)
	errCh := make(chan error, 1)
	go func() {
		result, runErr := executeLimitedShell(ctx, []string{
			sh,
			"-c",
			`(echo ready > "$1"; sleep 1; echo survived > "$2") & wait`,
			"sh",
			readyPath,
			markerPath,
		}, 30000)
		resultCh <- result
		errCh <- runErr
	}()

	if !waitForCommandFile(t, readyPath, 5*time.Second) {
		t.Fatal("derived shell process did not start")
	}
	cancel()
	select {
	case result := <-resultCh:
		if !result.Cancelled {
			t.Errorf("shell result after cancel: %#v, want Cancelled", result)
		}
		if err := <-errCh; err == nil {
			t.Error("shell command returned no error after cancellation")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("shell Run remained blocked after cancellation")
	}

	// If only the direct child was killed, the background process would create
	// this marker after its one-second sleep.
	timer := time.NewTimer(1500 * time.Millisecond)
	<-timer.C
	if _, err := os.Stat(markerPath); err == nil {
		t.Fatal("derived process survived process-group cancellation")
	}
}

func TestExecuteLimitedShellWaitDelayBoundsWindowsDescendantPipe(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("Windows direct-child kill fallback is specific to Windows")
	}
	executable, err := os.Executable()
	if err != nil {
		t.Fatalf("locate test executable: %v", err)
	}
	dir := t.TempDir()
	pidPath := filepath.Join(dir, "descendant.pid")
	readyPath := filepath.Join(dir, "ready")
	t.Setenv(commandHelperEnv, "pipe-holder")
	t.Setenv("CLAWDROID_TEST_DESCENDANT_PID", pidPath)
	t.Setenv("CLAWDROID_TEST_COMMAND_READY", readyPath)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	resultCh := make(chan shellExecResult, 1)
	errCh := make(chan error, 1)
	started := time.Now()
	go func() {
		result, runErr := executeLimitedShell(ctx, []string{executable, "-test.run=^TestCommandHelperProcess$"}, 30000)
		resultCh <- result
		errCh <- runErr
	}()
	if !waitForCommandFile(t, readyPath, 5*time.Second) {
		t.Fatal("pipe-holding child did not start")
	}
	pidData, err := os.ReadFile(pidPath)
	if err != nil {
		t.Fatalf("read descendant pid: %v", err)
	}
	pid, err := strconv.Atoi(string(pidData))
	if err != nil {
		t.Fatalf("parse descendant pid: %v", err)
	}
	descendant, err := os.FindProcess(pid)
	if err != nil {
		t.Fatalf("find descendant: %v", err)
	}
	t.Cleanup(func() { _ = descendant.Kill() })

	cancel()
	select {
	case result := <-resultCh:
		elapsed := time.Since(started)
		if elapsed > 5*time.Second {
			t.Errorf("Run waited %s for a descendant-held pipe; WaitDelay did not bound it", elapsed)
		}
		if !result.Cancelled {
			t.Errorf("shell result after cancel: %#v, want Cancelled", result)
		}
		if err := <-errCh; err == nil {
			t.Error("shell command returned no error after cancellation")
		}
	case <-time.After(12 * time.Second):
		t.Fatal("shell Run remained blocked on descendant pipes")
	}
}
