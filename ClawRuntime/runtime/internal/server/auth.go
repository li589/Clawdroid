package server

import (
	"bufio"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os/exec"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"time"
)

type controlFrame struct {
	Type            string   `json:"type"`
	SessionID       string   `json:"session_id,omitempty"`
	Nonce           string   `json:"nonce,omitempty"`
	AuthMode        string   `json:"auth_mode,omitempty"`
	PackageName     string   `json:"package_name,omitempty"`
	SignatureDigest string   `json:"signature_digest,omitempty"`
	ClientTimestamp int64    `json:"client_timestamp,omitempty"`
	ResponseDigest  string   `json:"response_digest,omitempty"`
	AllowedPackages []string `json:"allowed_packages,omitempty"`
	OK              bool     `json:"ok,omitempty"`
	Code            int      `json:"code,omitempty"`
	Message         string   `json:"message,omitempty"`
	SessionState    string   `json:"session_state,omitempty"`
	StateTrace      []string `json:"state_trace,omitempty"`
}

func (s *Server) performHandshake(sess *session, reader *bufio.Reader, writer *bufio.Writer) error {
	s.issueChallenge(sess)

	if err := writeControlFrame(writer, controlFrame{
		Type:         "challenge",
		SessionID:    sess.id,
		Nonce:        sess.challengeNonce,
		AuthMode:     sess.authMode,
		Message:      "respond with package_name, signature_digest, client_timestamp, response_digest",
		SessionState: string(sess.state),
		StateTrace:   sess.traceSnapshot(),
	}); err != nil {
		return err
	}

	frame, err := readControlFrame(reader)
	if err != nil {
		return err
	}
	if frame.Type != "auth" {
		sess.setAuthFailure(1003, "unexpected auth frame")
		sess.transition(StateClosed)
		_ = writeControlFrame(writer, controlFrame{
			Type:         "auth_result",
			SessionID:    sess.id,
			OK:           false,
			Code:         1003,
			Message:      "unexpected auth frame",
			SessionState: string(sess.state),
			StateTrace:   sess.traceSnapshot(),
		})
		return fmt.Errorf("unexpected auth frame type: %s", frame.Type)
	}

	if !s.verifyAuthResponse(sess, frame) {
		sess.transition(StateClosed)
		_ = writeControlFrame(writer, controlFrame{
			Type:         "auth_result",
			SessionID:    sess.id,
			OK:           false,
			Code:         sess.lastAuthErrorCode(s),
			Message:      sess.lastAuthErrorMessage(s),
			SessionState: string(sess.state),
			StateTrace:   sess.traceSnapshot(),
		})
		return fmt.Errorf(sess.lastAuthErrorMessage(s))
	}

	if err := writeControlFrame(writer, controlFrame{
		Type:         "auth_result",
		SessionID:    sess.id,
		OK:           true,
		Code:         0,
		Message:      "authenticated",
		AuthMode:     sess.authMode,
		SessionState: string(sess.state),
		StateTrace:   sess.traceSnapshot(),
	}); err != nil {
		return err
	}

	return nil
}

func readControlFrame(reader *bufio.Reader) (controlFrame, error) {
	var frame controlFrame
	payload, err := readJSONFrame(reader, 262144)
	if err != nil {
		return frame, err
	}

	err = json.Unmarshal(payload, &frame)
	return frame, err
}

func writeControlFrame(writer *bufio.Writer, frame controlFrame) error {
	payload, err := json.Marshal(frame)
	if err != nil {
		return err
	}
	return writeJSONFrame(writer, payload)
}

func (s *Server) verifyAuthResponse(sess *session, frame controlFrame) bool {
	if frame.SessionID != sess.id || frame.PackageName == "" || frame.ResponseDigest == "" {
		sess.setAuthFailure(1003, "missing auth fields")
		return false
	}
	if sess.challengeNonce == "" {
		sess.setAuthFailure(1003, "challenge not issued")
		return false
	}
	if sess.challengeUsed {
		sess.setAuthFailure(1003, "challenge already consumed")
		return false
	}
	if time.Since(sess.challengeAt) > time.Duration(s.cfg.ChallengeTTLSec)*time.Second {
		sess.setAuthFailure(1004, "challenge expired")
		return false
	}
	// Verify package name first to avoid wasting resources on disallowed packages.
	if !s.isAllowedPackage(frame.PackageName) {
		sess.setAuthFailure(1001, "package not allowed")
		return false
	}
	if !s.verifyPeerPackageBinding(sess, frame.PackageName) {
		return false
	}
	if !s.isAllowedSignature(frame.SignatureDigest) {
		sess.setAuthFailure(1002, "signature mismatch")
		return false
	}
	if !s.withinTimestampSkew(frame.ClientTimestamp) {
		sess.setAuthFailure(1004, "timestamp skew exceeded")
		return false
	}
	sess.challengeUsed = true
	expected := authDigest(
		s.cfg.AuthSharedSecret,
		sess.challengeNonce,
		frame.PackageName,
		frame.SignatureDigest,
		frame.ClientTimestamp,
	)
	if !hmac.Equal([]byte(strings.ToLower(frame.ResponseDigest)), []byte(expected)) {
		sess.setAuthFailure(1003, "challenge verification failed")
		return false
	}

	sess.packageName = frame.PackageName
	sess.signatureDigest = strings.ToLower(frame.SignatureDigest)
	sess.clientTimestamp = frame.ClientTimestamp
	sess.authenticatedAt = time.Now()
	sess.transition(StateAuthenticated)
	return true
}

func (s *Server) verifyPeerPackageBinding(sess *session, packageName string) bool {
	if !sess.peerVerified {
		sess.setAuthFailure(1001, "peer credentials not verified")
		return false
	}
	if runtime.GOOS != "android" {
		return true
	}

	packages, err := lookupPackagesForUID(sess.peerUID)
	if err != nil {
		sess.setAuthFailure(1001, "failed to resolve uid packages: "+err.Error())
		return false
	}
	sess.peerKnownPackages = packages
	if !slices.Contains(packages, packageName) {
		sess.setAuthFailure(1001, fmt.Sprintf("package %s does not belong to peer uid %d", packageName, sess.peerUID))
		return false
	}
	sess.peerPackageBound = true
	return true
}

func (s *Server) isAllowedPackage(packageName string) bool {
	for _, allowed := range s.cfg.AllowedPackages {
		if allowed == packageName {
			return true
		}
	}
	return false
}

func (s *Server) isAllowedSignature(signatureDigest string) bool {
	if len(s.cfg.AllowedSignatures) == 0 {
		return true
	}
	normalized := strings.ToLower(strings.TrimSpace(signatureDigest))
	if normalized == "" {
		return false
	}
	if s.signaturePrefixMap == nil {
		return false
	}
	_, found := s.signaturePrefixMap[normalized]
	return found
}

func (s *Server) withinTimestampSkew(clientTimestamp int64) bool {
	now := time.Now().Unix()
	diff := now - clientTimestamp
	if diff < 0 {
		diff = -diff
	}
	return diff <= s.cfg.TimestampSkewSec
}

func authDigest(secret, nonce, packageName, signatureDigest string, clientTimestamp int64) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(nonce))
	mac.Write([]byte("|"))
	mac.Write([]byte(packageName))
	mac.Write([]byte("|"))
	mac.Write([]byte(strings.ToLower(signatureDigest)))
	mac.Write([]byte("|"))
	mac.Write([]byte(strconv.FormatInt(clientTimestamp, 10)))
	return hex.EncodeToString(mac.Sum(nil))
}

func lookupPackagesForUID(uid int) ([]string, error) {
	if uid <= 0 {
		return nil, fmt.Errorf("uid must be > 0")
	}

	commands := [][]string{
		{"cmd", "package", "list", "packages", "--uid", strconv.Itoa(uid)},
		{"pm", "list", "packages", "--uid", strconv.Itoa(uid)},
	}
	var lastErr error
	for _, commandArgs := range commands {
		output, err := exec.Command(commandArgs[0], commandArgs[1:]...).CombinedOutput()
		if err != nil {
			lastErr = fmt.Errorf("%s: %w", strings.Join(commandArgs, " "), err)
			continue
		}
		packages := parseUIDPackageListOutput(string(output))
		if len(packages) > 0 {
			return packages, nil
		}
		lastErr = fmt.Errorf("%s returned no packages", strings.Join(commandArgs, " "))
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no package manager command available")
	}
	return nil, lastErr
}

func parseUIDPackageListOutput(output string) []string {
	seen := make(map[string]struct{})
	packages := make([]string, 0, 4)
	for _, line := range strings.Split(output, "\n") {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}
		index := strings.Index(trimmed, "package:")
		if index < 0 {
			continue
		}
		candidate := strings.TrimSpace(trimmed[index+len("package:"):])
		if space := strings.IndexAny(candidate, " \t"); space >= 0 {
			candidate = candidate[:space]
		}
		candidate = strings.Trim(candidate, "\"")
		if candidate == "" {
			continue
		}
		if _, exists := seen[candidate]; exists {
			continue
		}
		seen[candidate] = struct{}{}
		packages = append(packages, candidate)
	}
	return packages
}

func (sess *session) lastAuthErrorCode(s *Server) int {
	if sess.authFailureCode != 0 {
		return sess.authFailureCode
	}
	_ = s
	return 1003
}

func (sess *session) lastAuthErrorMessage(s *Server) string {
	if sess.authFailureMsg != "" {
		return sess.authFailureMsg
	}
	_ = s
	return "challenge verification failed"
}
