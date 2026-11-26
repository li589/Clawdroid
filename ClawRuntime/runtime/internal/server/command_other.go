//go:build !unix && !windows

package server

import "os/exec"

func configureCommandCancellation(cmd *exec.Cmd) {
	cmd.Cancel = func() error {
		return cmd.Process.Kill()
	}
}
