package server

import (
	"bufio"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"clawdroid/runtime/internal/audit"
	"clawdroid/runtime/internal/config"
	"clawdroid/runtime/internal/ipc"
	"clawdroid/runtime/internal/task"
)

var errPayloadTooLarge = errors.New("payload too large")

type Server struct {
	cfg    config.Config
	logger *audit.Logger

	signaturePrefixMap map[string]struct{}
	idempotencyCache   *idempotencyCache
	taskScheduler      *task.Scheduler

	sessionsMu sync.RWMutex
	sessions   map[string]*session // sessionID -> session (only active sessions)

	startedAt            time.Time
	diagMu               sync.RWMutex
	lastRateLimitAt      time.Time
	lastRateLimitMessage string
	rateLimitHits        int
}

func New(cfg config.Config, logger *audit.Logger) *Server {
	sigMap := make(map[string]struct{})
	for _, sig := range cfg.AllowedSignatures {
		normalized := strings.ToLower(strings.TrimSpace(sig))
		if normalized != "" {
			sigMap[normalized] = struct{}{}
		}
	}
	s := &Server{
		cfg:                cfg,
		logger:             logger,
		signaturePrefixMap: sigMap,
		idempotencyCache:   newIdempotencyCache(),
		startedAt:          time.Now(),
	}
	s.taskScheduler = task.NewScheduler(s, s.onTaskStateChange)
	return s
}

func (s *Server) Start(ctx context.Context) error {
	socketAddr, cleanup, err := s.resolveSocketAddress()
	if err != nil {
		return err
	}
	defer cleanup()

	listener, err := net.Listen("unix", socketAddr)
	if err != nil {
		return fmt.Errorf("listen runtime socket: %w", err)
	}
	defer listener.Close()

	s.logger.Info(fmt.Sprintf("runtime socket listening on %q", s.cfg.SocketName))

	go func() {
		<-ctx.Done()
		_ = listener.Close()
	}()

	for {
		conn, acceptErr := listener.Accept()
		if acceptErr != nil {
			if errors.Is(acceptErr, net.ErrClosed) || ctx.Err() != nil {
				s.logger.Info("runtime shutdown requested")
				return nil
			}
			return fmt.Errorf("accept runtime connection: %w", acceptErr)
		}

		if err := s.handleConnection(ctx, conn); err != nil {
			s.logger.Error(fmt.Sprintf("connection handling failed: %v", err))
		}
	}
}

func (s *Server) handleConnection(ctx context.Context, conn net.Conn) error {
	defer conn.Close()

	sess := newSession()
	if !s.prepareSession(sess, conn) {
		writer := bufio.NewWriter(conn)
		_ = writeResponseFrame(writer, ipc.Response{
			RequestID: "",
			OK:        false,
			Code:      ipc.CodeErrPeerVerifyFailed,
			Message:   ipc.ErrorMessage(ipc.CodeErrPeerVerifyFailed),
			Data: map[string]interface{}{
				"session_id":    sess.id,
				"session_state": string(sess.state),
				"state_trace":   sess.traceSnapshot(),
			},
		})
		sess.transition(StateClosed)
		return nil
	}

	reader := bufio.NewReader(conn)
	writer := bufio.NewWriter(conn)

	_ = conn.SetDeadline(deadlineFromMS(s.cfg.RequestTimeoutMS))
	if err := s.performHandshake(sess, reader, writer); err != nil {
		return err
	}

	// Register session after successful handshake so task steps can use it.
	s.registerSession(sess)
	defer s.unregisterSession(sess.id)

	for {
		_ = conn.SetDeadline(deadlineFromMS(s.cfg.RequestTimeoutMS))

		req, err := decodeRequestFrame(reader, s.cfg.MaxPayloadBytes)
		loopStartedAt := time.Now()
		if err != nil {
			if errors.Is(err, io.EOF) {
				sess.transition(StateClosed)
				return nil
			}
			code := ipc.CodeErrInvalidRequest
			if errors.Is(err, errPayloadTooLarge) {
				code = ipc.CodeErrPayloadTooLarge
			}
			s.logAudit(sess, req, loopStartedAt, code, ipc.ErrorMessage(code), "", audit.AuditLevelMedium)
			return writeResponseFrame(writer, ipc.Response{
				RequestID: "",
				OK:        false,
				Code:      code,
				Message:   ipc.ErrorMessage(code),
				Data: map[string]interface{}{
					"session_id":    sess.id,
					"session_state": string(sess.state),
					"state_trace":   sess.traceSnapshot(),
				},
			})
		}

		if req.Version != s.cfg.ProtocolVersion {
			s.logAudit(sess, req, loopStartedAt, ipc.CodeErrUnsupportedVer, ipc.ErrorMessage(ipc.CodeErrUnsupportedVer), "", audit.AuditLevelMedium)
			_ = writeResponseFrame(writer, ipc.Response{
				RequestID: req.RequestID,
				OK:        false,
				Code:      ipc.CodeErrUnsupportedVer,
				Message:   ipc.ErrorMessage(ipc.CodeErrUnsupportedVer),
				Data: map[string]interface{}{
					"session_id":    sess.id,
					"session_state": string(sess.state),
					"state_trace":   sess.traceSnapshot(),
				},
			})
			sess.transition(StateClosed)
			return nil
		}
		if req.RequestID == "" || req.Action == "" || req.Args == nil || len(req.Args) == 0 {
			s.logAudit(sess, req, loopStartedAt, ipc.CodeErrInvalidRequest, ipc.ErrorMessage(ipc.CodeErrInvalidRequest), "", audit.AuditLevelLow)
			_ = writeResponseFrame(writer, ipc.Response{
				RequestID: req.RequestID,
				OK:        false,
				Code:      ipc.CodeErrInvalidRequest,
				Message:   ipc.ErrorMessage(ipc.CodeErrInvalidRequest),
				Data:      s.sessionData(sess),
			})
			continue
		}
		if sess.sessionExpired(s.cfg.SessionTTLSec) {
			sess.setAuthFailure(1004, "session expired")
			sess.transition(StateClosed)
			s.logAudit(sess, req, loopStartedAt, ipc.CodeErrSessionExpired, ipc.ErrorMessage(ipc.CodeErrSessionExpired), "", audit.AuditLevelMedium)
			_ = writeResponseFrame(writer, ipc.Response{
				RequestID: req.RequestID,
				OK:        false,
				Code:      ipc.CodeErrSessionExpired,
				Message:   ipc.ErrorMessage(ipc.CodeErrSessionExpired),
				Data: map[string]interface{}{
					"session_id":    sess.id,
					"session_state": string(sess.state),
					"state_trace":   sess.traceSnapshot(),
				},
			})
			return nil
		}

		if !sess.allowRequest(s.cfg.RateLimitPerMinute) {
			message := fmt.Sprintf(
				"rate limit exceeded: package=%s session=%s action=%s limit=%d/min",
				sess.packageName,
				sess.id,
				req.Action,
				s.cfg.RateLimitPerMinute,
			)
			s.recordRateLimit(message)
			s.logAudit(sess, req, loopStartedAt, ipc.CodeErrRateLimited, ipc.ErrorMessage(ipc.CodeErrRateLimited), "", audit.AuditLevelMedium)
			_ = writeResponseFrame(writer, ipc.Response{
				RequestID: req.RequestID,
				OK:        false,
				Code:      ipc.CodeErrRateLimited,
				Message:   ipc.ErrorMessage(ipc.CodeErrRateLimited),
				Data: mergeData(s.sessionData(sess), map[string]interface{}{
					"rate_limit_per_minute": s.cfg.RateLimitPerMinute,
					"last_rate_limit":       message,
				}),
			})
			continue
		}

		if cachedResp, ok := s.idempotencyCache.get(req.Action, req.RequestID); ok {
			responseCopy := cachedResp
			responseCopy.Data = mergeData(s.sessionData(sess), cachedResp.Data)
			s.logAuditRequest(sess, req, loopStartedAt, responseCopy)
			if err := writeResponseFrame(writer, responseCopy); err != nil {
				sess.transition(StateClosed)
				return err
			}
			continue
		}

		if req.Action == "subscribe_events" {
			if err := s.handleSubscribeEvents(ctx, sess, req, conn, writer); err != nil {
				sess.transition(StateClosed)
				return err
			}
			continue
		}

		response := s.handleRequest(sess, req)
		s.idempotencyCache.put(req.Action, req.RequestID, response)
		mergedResp := response
		mergedResp.Data = mergeData(s.sessionData(sess), response.Data)
		s.logAuditRequest(sess, req, loopStartedAt, mergedResp)
		if err := writeResponseFrame(writer, mergedResp); err != nil {
			sess.transition(StateClosed)
			return err
		}
	}
}

func decodeRequestFrame(reader *bufio.Reader, maxPayloadBytes int) (ipc.Request, error) {
	var req ipc.Request
	payload, err := readJSONFrame(reader, maxPayloadBytes)
	if err != nil {
		return req, err
	}
	err = json.Unmarshal(payload, &req)
	return req, err
}

func writeResponseFrame(writer *bufio.Writer, resp ipc.Response) error {
	payload, err := json.Marshal(resp)
	if err != nil {
		return err
	}
	return writeJSONFrame(writer, payload)
}

func deadlineFromMS(timeoutMS int) time.Time {
	return time.Now().Add(time.Duration(timeoutMS) * time.Millisecond)
}

func (s *Server) resolveSocketAddress() (string, func(), error) {
	if runtime.GOOS == "linux" || runtime.GOOS == "android" {
		return "\x00" + s.cfg.SocketName, func() {}, nil
	}

	tempDir := os.TempDir()
	socketPath := filepath.Join(tempDir, s.cfg.SocketName+".sock")
	_ = os.Remove(socketPath)
	cleanup := func() {
		_ = os.Remove(socketPath)
	}
	return socketPath, cleanup, nil
}

func readJSONFrame(reader *bufio.Reader, maxPayloadBytes int) ([]byte, error) {
	if maxPayloadBytes <= 0 {
		maxPayloadBytes = 262144
	}

	header := make([]byte, 4)
	if _, err := io.ReadFull(reader, header); err != nil {
		return nil, err
	}

	payloadSize := int(binary.BigEndian.Uint32(header))
	if payloadSize <= 0 {
		return nil, fmt.Errorf("empty frame payload")
	}
	if payloadSize > maxPayloadBytes {
		return nil, errPayloadTooLarge
	}

	payload := make([]byte, payloadSize)
	if _, err := io.ReadFull(reader, payload); err != nil {
		return nil, err
	}
	return payload, nil
}

func writeJSONFrame(writer *bufio.Writer, payload []byte) error {
	header := make([]byte, 4)
	binary.BigEndian.PutUint32(header, uint32(len(payload)))
	if _, err := writer.Write(header); err != nil {
		return err
	}
	if _, err := writer.Write(payload); err != nil {
		return err
	}
	return writer.Flush()
}

func writeJSONFrameWithTimeout(conn net.Conn, writer *bufio.Writer, payload []byte) error {
	if err := conn.SetWriteDeadline(time.Now().Add(eventWriteTimeout)); err != nil {
		return err
	}
	return writeJSONFrame(writer, payload)
}

func (s *Server) logAudit(sess *session, req ipc.Request, startedAt time.Time, code int, msg string, errMsg string, level audit.AuditLevel) {
	entry := audit.AuditLogEntry{
		RequestID:       req.RequestID,
		SessionID:       sess.id,
		CallerUID:       sess.peerCreds.UID,
		CallerPID:       sess.peerCreds.PID,
		PackageName:     sess.packageName,
		SignatureDigest: sess.signatureDigest,
		Action:          req.Action,
		AuditLevel:      level,
		StartedAt:       startedAt.Unix(),
		EndedAt:         time.Now().Unix(),
		ResultCode:      code,
		ResultMessage:   msg,
		ErrorMessage:    errMsg,
		LatencyMS:       time.Since(startedAt).Milliseconds(),
	}
	if req.Args != nil && level == audit.AuditLevelHigh {
		entry.ArgDigest = audit.ComputeArgDigest(req.Args)
	}
	v, bt := audit.GetVersionInfo()
	entry.DaemonVersion = v
	entry.DaemonBuildTime = bt
	_ = s.logger.Log(entry)
}

func (s *Server) logAuditRequest(sess *session, req ipc.Request, startedAt time.Time, resp ipc.Response) {
	level := audit.AuditLevelForAction(req.Action)
	code := resp.Code
	msg := resp.Message
	errMsg := ""
	if !resp.OK && code != ipc.CodeOK {
		errMsg = msg
	}
	s.logAudit(sess, req, startedAt, code, msg, errMsg, level)
}

// ExecuteStep implements task.StepExecutor. It routes the action through the
// existing handler infrastructure using the first active session.
func (s *Server) ExecuteStep(ctx context.Context, taskID string, action string, args map[string]interface{}, timeoutMS int) (code int, message string, data map[string]interface{}, latencyMS int64) {
	started := time.Now()

	req := ipc.Request{
		RequestID: fmt.Sprintf("task-step-%d", time.Now().UnixNano()),
		Action:    action,
		Args:      args,
		Version:   s.cfg.ProtocolVersion,
	}

	sess := s.anyActiveSession()
	if sess == nil {
		return ipc.CodeErrSessionExpired, ipc.ErrorMessage(ipc.CodeErrSessionExpired), nil, time.Since(started).Milliseconds()
	}

	resp := s.handleRequest(sess, req)
	return resp.Code, resp.Message, resp.Data, time.Since(started).Milliseconds()
}

// onTaskStateChange is called by the scheduler whenever a task transitions state.
func (s *Server) onTaskStateChange(taskID string, oldState, newState task.TaskState, snapshot map[string]interface{}) {
	s.logger.Info(fmt.Sprintf("task state transition: task=%s %s -> %s", taskID, oldState, newState))
}

// anyActiveSession returns the first active authenticated session.
func (s *Server) anyActiveSession() *session {
	s.sessionsMu.RLock()
	for _, sess := range s.sessions {
		s.sessionsMu.RUnlock()
		return sess
	}
	s.sessionsMu.RUnlock()
	return nil
}

func (s *Server) registerSession(sess *session) {
	s.sessionsMu.Lock()
	if s.sessions == nil {
		s.sessions = make(map[string]*session)
	}
	s.sessions[sess.id] = sess
	s.sessionsMu.Unlock()
}

func (s *Server) unregisterSession(sessionID string) {
	s.sessionsMu.Lock()
	delete(s.sessions, sessionID)
	s.sessionsMu.Unlock()
}

func (s *Server) getSession(sessionID string) *session {
	s.sessionsMu.RLock()
	sess := s.sessions[sessionID]
	s.sessionsMu.RUnlock()
	return sess
}
