//go:build !(linux || android)

package server

import (
	"fmt"
	"net"
)

type peerCredentials struct {
	PID    int
	UID    int
	GID    int
	Method string
}

func readPeerCredentials(conn net.Conn) (peerCredentials, error) {
	_ = conn
	return peerCredentials{}, fmt.Errorf("peer credential verification is unsupported on this platform")
}
