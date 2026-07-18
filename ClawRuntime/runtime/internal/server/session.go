package server

import (
	"crypto/rand"
	"encoding/hex"
	"net"
	"time"
)

type SessionState string

const (
	StateDisconnected     SessionState = "Disconnected"
	StateSocketConnected  SessionState = "SocketConnected"
	StatePeerVerified     SessionState = "PeerVerified"
	StateChallengeIssued  SessionState = "ChallengeIssued"
	StateAuthenticated    SessionState = "Authenticated"
	StateCapabilitySynced SessionState = "CapabilitySynced"
	StateReady            SessionState = "Ready"
	StateDegraded         SessionState = "Degraded"
	StateClosed           SessionState = "Closed"
)

type session struct {
	id                     string
	state                  SessionState
	trace                  []string
	authMode               string
	challengeNonce         string
	createdAt              time.Time
	authenticatedAt        time.Time
	challengeAt            time.Time
	challengeUsed          bool
	degradedReason         string
	packageName            string
	signatureDigest        string
	clientTimestamp        int64
	authFailureCode        int
	authFailureMsg         string
	requestTimes           []time.Time
	capabilities           []string
	peerPID                int
	peerUID                int
	peerGID                int
	peerVerified           bool
	peerVerificationMethod string
	peerPackageBound       bool
	peerKnownPackages      []string
	peerCreds              peerCredentials
}

func newSession() *session {
	s := &session{
		id:           randomHex(8),
		state:        StateDisconnected,
		authMode:     "hmac-sha256-local-v1",
		createdAt:    time.Now(),
		capabilities: []string{},
	}
	s.trace = append(s.trace, string(s.state))
	return s
}

func (s *session) transition(state SessionState) {
	if s.state == state {
		return
	}
	s.state = state
	s.trace = append(s.trace, string(state))
}

func (s *session) traceSnapshot() []string {
	snapshot := make([]string, len(s.trace))
	copy(snapshot, s.trace)
	return snapshot
}

func (s *session) setAuthFailure(code int, message string) {
	s.authFailureCode = code
	s.authFailureMsg = message
}

func (s *session) sessionExpired(ttlSeconds int64) bool {
	if s.authenticatedAt.IsZero() || ttlSeconds <= 0 {
		return false
	}
	return time.Since(s.authenticatedAt) > time.Duration(ttlSeconds)*time.Second
}

func (s *session) sessionExpiresAt(ttlSeconds int64) int64 {
	if s.authenticatedAt.IsZero() || ttlSeconds <= 0 {
		return 0
	}
	return s.authenticatedAt.Add(time.Duration(ttlSeconds) * time.Second).Unix()
}

func (s *session) allowRequest(limitPerMinute int) bool {
	if limitPerMinute <= 0 {
		return true
	}
	now := time.Now()
	cutoff := now.Add(-1 * time.Minute)
	filtered := make([]time.Time, 0, len(s.requestTimes))
	for _, item := range s.requestTimes {
		if item.After(cutoff) {
			filtered = append(filtered, item)
		}
	}
	s.requestTimes = filtered
	if len(s.requestTimes) >= limitPerMinute {
		return false
	}
	s.requestTimes = append(s.requestTimes, now)
	return true
}

func (s *Server) prepareSession(sess *session, conn net.Conn) bool {
	sess.transition(StateSocketConnected)
	return s.verifyPeer(sess, conn)
}

func (s *Server) verifyPeer(sess *session, conn net.Conn) bool {
	credentials, err := readPeerCredentials(conn)
	if err != nil {
		sess.setAuthFailure(1001, "peer credential verification failed: "+err.Error())
		return false
	}
	if credentials.UID <= 0 {
		sess.setAuthFailure(1001, "peer uid is invalid")
		return false
	}
	sess.peerPID = credentials.PID
	sess.peerUID = credentials.UID
	sess.peerGID = credentials.GID
	sess.peerVerified = true
	sess.peerVerificationMethod = credentials.Method
	sess.peerCreds = credentials
	sess.transition(StatePeerVerified)
	return true
}

func (s *Server) issueChallenge(sess *session) {
	sess.challengeNonce = randomHex(12)
	sess.challengeAt = time.Now()
	sess.challengeUsed = false
	sess.transition(StateChallengeIssued)
}

func (s *Server) finalizeCapabilityState(sess *session) {
	sess.capabilities = s.capabilityList()
	sess.transition(StateCapabilitySynced)

	if s.cfg.InputInjectEnabled || s.cfg.ScreenshotEnabled || s.cfg.ShellEnabled || s.cfg.FileBridgeEnabled {
		sess.degradedReason = ""
		sess.transition(StateReady)
		return
	}

	sess.degradedReason = "no privileged execution capabilities enabled yet"
	sess.transition(StateDegraded)
}

func randomHex(byteLen int) string {
	buf := make([]byte, byteLen)
	if _, err := rand.Read(buf); err != nil {
		return hex.EncodeToString([]byte(time.Now().Format("150405.000000000")))
	}
	return hex.EncodeToString(buf)
}
