//go:build windows

package server

import "os/exec"

func configureCommandCancellation(cmd *exec.Cmd) {
	// Windows has no portable process-group primitive in os/exec. Keep the
	// direct-child kill used by CommandContext; command.go still bounds WaitDelay
	// so descendants retaining stdout/stderr pipes cannot block Run forever.
	cmd.Cancel = func() error {
		return cmd.Process.Kill()
	}
}
