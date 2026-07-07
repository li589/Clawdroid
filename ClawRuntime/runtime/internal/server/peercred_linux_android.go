//go:build linux || android

package server

import (
	"fmt"
	"net"
	"syscall"
)

type peerCredentials struct {
	PID    int
	UID    int
	GID    int
	Method string
}

func readPeerCredentials(conn net.Conn) (peerCredentials, error) {
	unixConn, ok := conn.(*net.UnixConn)
	if !ok {
		return peerCredentials{}, fmt.Errorf("peer verification requires a unix domain socket")
	}

	rawConn, err := unixConn.SyscallConn()
	if err != nil {
		return peerCredentials{}, fmt.Errorf("obtain raw unix conn: %w", err)
	}

	var credentials peerCredentials
	var syscallErr error
	if err := rawConn.Control(func(fd uintptr) {
		ucred, err := syscall.GetsockoptUcred(int(fd), syscall.SOL_SOCKET, syscall.SO_PEERCRED)
		if err != nil {
			syscallErr = err
			return
		}
		credentials = peerCredentials{
			PID:    int(ucred.Pid),
			UID:    int(ucred.Uid),
			GID:    int(ucred.Gid),
			Method: "SO_PEERCRED",
		}
	}); err != nil {
		return peerCredentials{}, fmt.Errorf("control unix conn: %w", err)
	}
	if syscallErr != nil {
		return peerCredentials{}, fmt.Errorf("getsockopt SO_PEERCRED: %w", syscallErr)
	}
	return credentials, nil
}
