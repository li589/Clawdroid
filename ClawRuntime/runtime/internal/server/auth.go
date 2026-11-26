package server

import (
	"bufio"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"runtime"
	"strconv"
	"strings"
	"time"

	"clawdroid/runtime/internal/ipc"
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

func (s *Server) performHandshake(ctx context.Context, sess *session, reader *bufio.Reader, writer *bufio.Writer) error {
	s.issueChallenge(sess)
	challenge := sess.snapshot()

	if err := writeControlFrame(writer, controlFrame{
		Type:         "challenge",
		SessionID:    challenge.id,
		Nonce:        challenge.challengeNonce,
		AuthMode:     challenge.authMode,
		Message:      "respond with package_name, signature_digest, client_timestamp, response_digest",
		SessionState: string(challenge.state),
		StateTrace:   challenge.trace,
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
		failed := sess.snapshot()
		_ = writeControlFrame(writer, controlFrame{
			Type:         "auth_result",
			SessionID:    failed.id,
			OK:           false,
			Code:         1003,
			Message:      "unexpected auth frame",
			SessionState: string(failed.state),
			StateTrace:   failed.trace,
		})
		return fmt.Errorf("unexpected auth frame type: %s", frame.Type)
	}

	if !s.verifyAuthResponse(ctx, sess, frame) {
		sess.transition(StateClosed)
		code, message := sess.authFailure()
		if code == 0 {
			code = 1003
		}
		if message == "" {
			message = "challenge verification failed"
		}
		failed := sess.snapshot()
		_ = writeControlFrame(writer, controlFrame{
			Type:         "auth_result",
			SessionID:    failed.id,
			OK:           false,
			Code:         code,
			Message:      message,
			SessionState: string(failed.state),
			StateTrace:   failed.trace,
		})
		return fmt.Errorf("%s", message)
	}

	authenticated := sess.snapshot()
	if err := writeControlFrame(writer, controlFrame{
		Type:         "auth_result",
		SessionID:    authenticated.id,
		OK:           true,
		Code:         0,
		Message:      "authenticated",
		AuthMode:     authenticated.authMode,
		SessionState: string(authenticated.state),
		StateTrace:   authenticated.trace,
	}); err != nil {
		return err
	}

	return nil
}

func readControlFrame(reader *bufio.Reader) (controlFrame, error) {
	var frame controlFrame
	payload, err := readJSONFrame(reader, 2*1024*1024)
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

func (s *Server) verifyAuthResponse(ctx context.Context, sess *session, frame controlFrame) bool {
	if frame.SessionID != sess.id || frame.PackageName == "" || frame.ResponseDigest == "" {
		sess.setAuthFailure(1003, "missing auth fields")
		return false
	}
	nonce, used, challengeAt := sess.challengeState()
	if nonce == "" {
		sess.setAuthFailure(1003, "challenge not issued")
		return false
	}
	if used {
		sess.setAuthFailure(1003, "challenge already consumed")
		return false
	}
	if time.Since(challengeAt) > time.Duration(s.cfg.ChallengeTTLSec)*time.Second {
		sess.setAuthFailure(1004, "challenge expired")
		return false
	}
	// Verify package name first to avoid wasting resources on disallowed packages.
	if !s.isAllowedPackage(frame.PackageName) {
		sess.setAuthFailure(1001, "package not allowed")
		return false
	}
	if !s.verifyPeerPackageBinding(ctx, sess, frame.PackageName) {
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
	nonce, code, message := sess.consumeChallenge(nonce, s.cfg.ChallengeTTLSec)
	if code != 0 {
		sess.setAuthFailure(code, message)
		return false
	}
	expected := authDigest(
		s.cfg.AuthSharedSecret,
		nonce,
		frame.PackageName,
		frame.SignatureDigest,
		frame.ClientTimestamp,
	)
	if !hmac.Equal([]byte(strings.ToLower(frame.ResponseDigest)), []byte(expected)) {
		sess.setAuthFailure(1003, "challenge verification failed")
		return false
	}

	sess.completeAuthentication(frame.PackageName, strings.ToLower(frame.SignatureDigest), frame.ClientTimestamp)
	return true
}

func (s *Server) verifyPeerPackageBinding(ctx context.Context, sess *session, packageName string) bool {
	peer := sess.snapshot()
	if !peer.peerVerified {
		sess.setAuthFailure(1001, "peer credentials not verified")
		return false
	}
	if runtime.GOOS != "android" {
		return true
	}

	packages, err := lookupPackagesForUID(ctx, peer.peerUID)
	if err != nil {
		sess.setAuthFailure(1001, "failed to resolve uid packages: "+err.Error())
		return false
	}
	return sess.setPeerPackageBinding(peer.peerUID, packages, packageName)
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
	if clientTimestamp <= 0 || s.cfg.TimestampSkewSec < 0 {
		return false
	}
	now := time.Now().Unix()
	// Compare without subtracting first so extreme int64 timestamps cannot wrap.
	if clientTimestamp > now {
		return clientTimestamp-now <= s.cfg.TimestampSkewSec
	}
	return now-clientTimestamp <= s.cfg.TimestampSkewSec
}

func (s *Server) invalidTimestampResponse(sess *session, req ipc.Request) ipc.Response {
	return ipc.Response{
		RequestID: req.RequestID,
		OK:        false,
		Code:      ipc.CodeErrInvalidRequest,
		Message:   "request timestamp is missing or outside the allowed skew",
		Data:      mergeData(s.sessionData(sess), map[string]interface{}{"action": req.Action}),
	}
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

const packageLookupTimeout = 3 * time.Second

func lookupPackagesForUID(ctx context.Context, uid int) ([]string, error) {
	ctx, cancel := context.WithTimeout(ctx, packageLookupTimeout)
	defer cancel()
	return lookupPackagesForUIDWithRunner(ctx, uid, runCommandContext)
}

func lookupPackagesForUIDWithRunner(
	ctx context.Context,
	uid int,
	run func(context.Context, string, ...string) ([]byte, error),
) ([]string, error) {
	if uid <= 0 {
		return nil, fmt.Errorf("uid must be > 0")
	}

	commands := [][]string{
		{"cmd", "package", "list", "packages", "--uid", strconv.Itoa(uid)},
		{"pm", "list", "packages", "--uid", strconv.Itoa(uid)},
	}
	var lastErr error
	for _, commandArgs := range commands {
		if err := ctx.Err(); err != nil {
			return nil, fmt.Errorf("package lookup canceled: %w", err)
		}
		output, err := run(ctx, commandArgs[0], commandArgs[1:]...)
		if err != nil {
			lastErr = fmt.Errorf("%s: %w", strings.Join(commandArgs, " "), err)
			if ctx.Err() != nil {
				return nil, fmt.Errorf("package lookup canceled: %w", ctx.Err())
			}
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
	code, _ := sess.authFailure()
	if code != 0 {
		return code
	}
	_ = s
	return 1003
}

func (sess *session) lastAuthErrorMessage(s *Server) string {
	_, message := sess.authFailure()
	if message != "" {
		return message
	}
	_ = s
	return "challenge verification failed"
}
