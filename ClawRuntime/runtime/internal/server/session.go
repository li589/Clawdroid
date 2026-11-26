package server

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net"
	"slices"
	"strings"
	"sync"
	"time"

	"clawdroid/runtime/internal/task"
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
	mu                     sync.RWMutex
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

// sessionSnapshot is an immutable copy of the session fields exposed to IPC,
// auditing, and health responses. Mutable slices are copied under session.mu.
type sessionSnapshot struct {
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

func cloneStrings(values []string) []string {
	if values == nil {
		return nil
	}
	return append([]string{}, values...)
}

func (s *session) snapshot() sessionSnapshot {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return sessionSnapshot{
		id:                     s.id,
		state:                  s.state,
		trace:                  cloneStrings(s.trace),
		authMode:               s.authMode,
		challengeNonce:         s.challengeNonce,
		createdAt:              s.createdAt,
		authenticatedAt:        s.authenticatedAt,
		challengeAt:            s.challengeAt,
		challengeUsed:          s.challengeUsed,
		degradedReason:         s.degradedReason,
		packageName:            s.packageName,
		signatureDigest:        s.signatureDigest,
		clientTimestamp:        s.clientTimestamp,
		authFailureCode:        s.authFailureCode,
		authFailureMsg:         s.authFailureMsg,
		capabilities:           cloneStrings(s.capabilities),
		peerPID:                s.peerPID,
		peerUID:                s.peerUID,
		peerGID:                s.peerGID,
		peerVerified:           s.peerVerified,
		peerVerificationMethod: s.peerVerificationMethod,
		peerPackageBound:       s.peerPackageBound,
		peerKnownPackages:      cloneStrings(s.peerKnownPackages),
		peerCreds:              s.peerCreds,
	}
}

func (s *session) transition(state SessionState) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.transitionLocked(state)
}

func (s *session) transitionLocked(state SessionState) {
	if s.state == state {
		return
	}
	s.state = state
	s.trace = append(s.trace, string(state))
}

func (s *session) traceSnapshot() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return cloneStrings(s.trace)
}

func (s *session) currentState() SessionState {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.state
}

func (s *session) capabilitiesSnapshot() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return cloneStrings(s.capabilities)
}

func (s *session) hasCapability(capability string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return slices.Contains(s.capabilities, capability)
}

func (s *session) setAuthFailure(code int, message string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.setAuthFailureLocked(code, message)
}

func (s *session) setAuthFailureLocked(code int, message string) {
	s.authFailureCode = code
	s.authFailureMsg = message
}

func (s *session) authFailure() (int, string) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.authFailureCode, s.authFailureMsg
}

func (s *session) sessionExpired(ttlSeconds int64) bool {
	if ttlSeconds <= 0 {
		return false
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.authenticatedAt.IsZero() {
		return false
	}
	return time.Since(s.authenticatedAt) > time.Duration(ttlSeconds)*time.Second
}

func (s *session) sessionExpiresAt(ttlSeconds int64) int64 {
	if ttlSeconds <= 0 {
		return 0
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.authenticatedAt.IsZero() {
		return 0
	}
	return s.authenticatedAt.Add(time.Duration(ttlSeconds) * time.Second).Unix()
}

const maxTrackedRateLimitPrincipals = 2048

type principalRateWindow struct {
	requestTimes []time.Time
}

type principalRateLimiter struct {
	mu             sync.Mutex
	entries        map[string]*principalRateWindow
	limitPerMinute int
	maxPrincipals  int
	lastCleanup    time.Time
}

func newPrincipalRateLimiter(limitPerMinute, maxPrincipals int) *principalRateLimiter {
	if maxPrincipals <= 0 {
		maxPrincipals = maxTrackedRateLimitPrincipals
	}
	return &principalRateLimiter{
		entries:        make(map[string]*principalRateWindow),
		limitPerMinute: limitPerMinute,
		maxPrincipals:  maxPrincipals,
		lastCleanup:    time.Now(),
	}
}

func (l *principalRateLimiter) allow(principal task.Principal, now time.Time) bool {
	if principal.PackageName == "" || principal.UID <= 0 || principal.SignatureDigest == "" {
		return false
	}
	if l.limitPerMinute <= 0 {
		return true
	}
	key := principal.OwnerKey()
	cutoff := now.Add(-time.Minute)

	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.entries) >= l.maxPrincipals || now.Sub(l.lastCleanup) >= time.Minute {
		l.cleanupLocked(cutoff)
		l.lastCleanup = now
	}
	window, exists := l.entries[key]
	if !exists {
		// Fail closed instead of evicting an active principal, which would let a
		// reconnect or a stream of identities reset its rate-limit window.
		if len(l.entries) >= l.maxPrincipals {
			return false
		}
		window = &principalRateWindow{}
		l.entries[key] = window
	}
	kept := window.requestTimes[:0]
	for _, requestedAt := range window.requestTimes {
		if requestedAt.After(cutoff) {
			kept = append(kept, requestedAt)
		}
	}
	window.requestTimes = kept
	if len(window.requestTimes) >= l.limitPerMinute {
		return false
	}
	window.requestTimes = append(window.requestTimes, now)
	return true
}

func (l *principalRateLimiter) cleanupLocked(cutoff time.Time) {
	for key, window := range l.entries {
		kept := window.requestTimes[:0]
		for _, requestedAt := range window.requestTimes {
			if requestedAt.After(cutoff) {
				kept = append(kept, requestedAt)
			}
		}
		window.requestTimes = kept
		if len(window.requestTimes) == 0 {
			delete(l.entries, key)
		}
	}
}

func (s *Server) allowPrincipalRequest(sess *session) bool {
	principal, ok := principalForSession(sess)
	return ok && s.rateLimiter != nil && s.rateLimiter.allow(principal, time.Now())
}

func (s *session) allowRequest(limitPerMinute int) bool {
	if limitPerMinute <= 0 {
		return true
	}
	s.mu.Lock()
	defer s.mu.Unlock()
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

func (s *session) challengeState() (nonce string, used bool, issuedAt time.Time) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.challengeNonce, s.challengeUsed, s.challengeAt
}

// consumeChallenge atomically rejects replay and marks a valid challenge used.
// The expected nonce prevents a concurrent challenge refresh from mixing digests.
func (s *session) consumeChallenge(expectedNonce string, ttlSeconds int64) (string, int, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.challengeNonce == "" || s.challengeNonce != expectedNonce {
		return "", 1003, "challenge not issued"
	}
	if s.challengeUsed {
		return "", 1003, "challenge already consumed"
	}
	if time.Since(s.challengeAt) > time.Duration(ttlSeconds)*time.Second {
		return "", 1004, "challenge expired"
	}
	s.challengeUsed = true
	return s.challengeNonce, 0, ""
}

func (s *session) completeAuthentication(packageName, signatureDigest string, clientTimestamp int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.packageName = packageName
	s.signatureDigest = strings.ToLower(strings.TrimSpace(signatureDigest))
	s.clientTimestamp = clientTimestamp
	s.authenticatedAt = time.Now()
	s.transitionLocked(StateAuthenticated)
}

func (s *session) setPeerCredentials(credentials peerCredentials) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.peerPID = credentials.PID
	s.peerUID = credentials.UID
	s.peerGID = credentials.GID
	s.peerVerified = true
	s.peerVerificationMethod = credentials.Method
	s.peerCreds = credentials
	s.transitionLocked(StatePeerVerified)
}

func (s *session) setPeerPackageBinding(uid int, packages []string, packageName string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.peerUID != uid {
		s.setAuthFailureLocked(1001, "peer credentials changed during package binding")
		return false
	}
	s.peerKnownPackages = cloneStrings(packages)
	if !slices.Contains(packages, packageName) {
		s.setAuthFailureLocked(1001, fmt.Sprintf("package %s does not belong to peer uid %d", packageName, uid))
		return false
	}
	s.peerPackageBound = true
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
	sess.setPeerCredentials(credentials)
	return true
}

func (s *Server) issueChallenge(sess *session) {
	nonce := randomHex(12)
	sess.mu.Lock()
	defer sess.mu.Unlock()
	sess.challengeNonce = nonce
	sess.challengeAt = time.Now()
	sess.challengeUsed = false
	sess.transitionLocked(StateChallengeIssued)
}

func (s *Server) finalizeCapabilityState(sess *session) {
	capabilities := s.capabilityList()
	sess.mu.Lock()
	defer sess.mu.Unlock()
	sess.capabilities = capabilities
	sess.transitionLocked(StateCapabilitySynced)

	if s.cfg.InputInjectEnabled || s.cfg.ScreenshotEnabled || s.cfg.ShellEnabled || s.cfg.FileBridgeEnabled {
		sess.degradedReason = ""
		sess.transitionLocked(StateReady)
		return
	}

	sess.degradedReason = "no privileged execution capabilities enabled yet"
	sess.transitionLocked(StateDegraded)
}

func randomHex(byteLen int) string {
	buf := make([]byte, byteLen)
	if _, err := rand.Read(buf); err != nil {
		return hex.EncodeToString([]byte(time.Now().Format("150405.000000000")))
	}
	return hex.EncodeToString(buf)
}
