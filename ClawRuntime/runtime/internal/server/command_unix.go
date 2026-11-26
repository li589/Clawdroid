//go:build unix

package server

import (
	"errors"
	"os"
	"os/exec"
	"syscall"
)

func configureCommandCancellation(cmd *exec.Cmd) {
	// An independent process group prevents cancellation signals from reaching
	// the daemon itself and lets us terminate shell descendants as one unit.
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	cmd.Cancel = func() error {
		if cmd.Process == nil {
			return os.ErrProcessDone
		}
		err := syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		if errors.Is(err, syscall.ESRCH) {
			return os.ErrProcessDone
		}
		return err
	}
}
