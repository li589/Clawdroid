package server

import (
	"context"
	"os/exec"
	"time"
)

const commandWaitDelay = 2 * time.Second

// newCommandContext creates a cancelable command with bounded pipe cleanup.
// Platform configuration below determines whether cancellation kills a process
// group (Unix/Android) or only the direct child (Windows fallback).
func newCommandContext(ctx context.Context, name string, args ...string) *exec.Cmd {
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.WaitDelay = commandWaitDelay
	configureCommandCancellation(cmd)
	return cmd
}

func runCommandContext(ctx context.Context, name string, args ...string) ([]byte, error) {
	return newCommandContext(ctx, name, args...).CombinedOutput()
}
