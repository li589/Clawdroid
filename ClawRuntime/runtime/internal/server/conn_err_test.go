package server

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"testing"
)

func TestIsBenignClientDisconnect(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"nil", nil, false},
		{"bare io.EOF", io.EOF, true},
		{"bare net.ErrClosed", net.ErrClosed, true},
		{"bare context.Canceled", context.Canceled, true},
		{"bare io.ErrUnexpectedEOF", io.ErrUnexpectedEOF, true},
		{"wrapped io.EOF", fmt.Errorf("handshake read failed: %w", io.EOF), true},
		{"wrapped net.ErrClosed", fmt.Errorf("accept: %w", net.ErrClosed), true},
		{"wrapped context.Canceled", fmt.Errorf("serve loop: %w", context.Canceled), true},
		{"wrapped io.ErrUnexpectedEOF", fmt.Errorf("frame decode: %w", io.ErrUnexpectedEOF), true},
		{"broken pipe message", errors.New("write unix @clawdroid_secure_ipc->@: write: broken pipe"), true},
		{"connection reset message", errors.New("read: connection reset by peer"), true},
		{"use of closed network connection message", errors.New("read: use of closed network connection"), true},
		{"signature mismatch", errors.New("signature mismatch"), false},
		{"random error", errors.New("something else went wrong"), false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := isBenignClientDisconnect(tc.err); got != tc.want {
				t.Fatalf("isBenignClientDisconnect(%v)=%v want %v", tc.err, got, tc.want)
			}
		})
	}
}
