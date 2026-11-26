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

const (
	taskSchedulerShutdownTimeout = 10 * time.Second
	maxConcurrentConnections     = 64
)

type connectionQuota struct {
	slots chan struct{}
}

func newConnectionQuota(limit int) *connectionQuota {
	if limit <= 0 {
		return &connectionQuota{}
	}
	return &connectionQuota{slots: make(chan struct{}, limit)}
}

func (q *connectionQuota) acquire() bool {
	if q == nil || q.slots == nil {
		return false
	}
	select {
	case q.slots <- struct{}{}:
		return true
	default:
		return false
	}
}

func (q *connectionQuota) release() {
	if q == nil || q.slots == nil {
		return
	}
	select {
	case <-q.slots:
	default:
	}
}

type Server struct {
	cfg    config.Config
	logger *audit.Logger

	signaturePrefixMap map[string]struct{}
	connectionQuota    *connectionQuota
	rateLimiter        *principalRateLimiter
	idempotencyCache   *idempotencyCache
	taskScheduler      *task.Scheduler
	shellJobs          *shellJobTracker

	sessionsMu sync.RWMutex
	sessions   map[string]*session // sessionID -> session (only active sessions)

	startedAt            time.Time
	diagMu               sync.RWMutex
	lastRateLimitAt      time.Time
	lastRateLimitMessage string
	rateLimitHits        int

	focusMu           sync.RWMutex
	latestXposedFocus map[string]interface{}
	latestXposedView  map[string]interface{}

	// shutdownCtx is cancelled when Start() returns (listener closed / ctx done).
	// Long-lived goroutines (e.g. reboot fire-and-forget) select on it to exit.
	shutdownCtx context.Context

	// Per-subscriber wake channels. Each subscribe_events loop registers its
	// own buffered channel so a single report_xposed_* wakes every subscriber,
	// not just one of them.
	eventSubsMu sync.Mutex
	eventSubs   map[chan struct{}]struct{}
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
		connectionQuota:    newConnectionQuota(maxConcurrentConnections),
		rateLimiter:        newPrincipalRateLimiter(cfg.RateLimitPerMinute, maxTrackedRateLimitPrincipals),
		idempotencyCache:   newIdempotencyCache(),
		shellJobs:          newShellJobTracker(),
		startedAt:          time.Now(),
		eventSubs:          make(map[chan struct{}]struct{}),
	}
	s.taskScheduler = task.NewSchedulerWithOptions(s, s.onTaskStateChange, task.SchedulerOptions{
		MaxConcurrent: cfg.MaxConcurrentTasks,
		MaxInflight:   cfg.MaxInflightTasks,
	})
	return s
}

// registerEventSub atomically claims a bounded global subscription slot.
func (s *Server) registerEventSub(ch chan struct{}) bool {
	if s == nil || ch == nil {
		return false
	}
	s.eventSubsMu.Lock()
	defer s.eventSubsMu.Unlock()
	if len(s.eventSubs) >= maxConcurrentEventSubscriptions {
		return false
	}
	if s.eventSubs == nil {
		s.eventSubs = make(map[chan struct{}]struct{})
	}
	s.eventSubs[ch] = struct{}{}
	return true
}

// unregisterEventSub removes a subscriber's wake channel.
func (s *Server) unregisterEventSub(ch chan struct{}) {
	if s == nil {
		return
	}
	s.eventSubsMu.Lock()
	delete(s.eventSubs, ch)
	s.eventSubsMu.Unlock()
}

// notifyEventWake non-blockingly signals every registered subscriber. Each
// channel has capacity 1, so a subscriber that is already pending a wake
// simply keeps the earlier signal.
func (s *Server) notifyEventWake() {
	if s == nil {
		return
	}
	s.eventSubsMu.Lock()
	defer s.eventSubsMu.Unlock()
	for ch := range s.eventSubs {
		select {
		case ch <- struct{}{}:
		default:
		}
	}
}

func (s *Server) Start(ctx context.Context) error {
	defer s.shutdownTaskScheduler(taskSchedulerShutdownTimeout)

	socketAddr, cleanup, err := s.resolveSocketAddress()
	if err != nil {
		return err
	}
	defer cleanup()

	// Derive a shutdown context so goroutines spawned by handlers can exit
	// when the listener closes or the parent context is cancelled.
	shutdownCtx, shutdownCancel := context.WithCancel(ctx)
	s.shutdownCtx = shutdownCtx
	defer shutdownCancel()

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

		if !s.connectionQuota.acquire() {
			s.recordRateLimit(fmt.Sprintf("connection limit exceeded: max=%d", maxConcurrentConnections))
			_ = conn.Close()
			continue
		}

		// Concurrent connections: subscribe_events holds a long-lived socket.
		// Handling Accept synchronously would HOL-block ping/version/health.
		go func(c net.Conn) {
			defer s.connectionQuota.release()
			if err := s.handleConnection(ctx, c); err != nil {
				// Short-lived App RPC sockets often close before the daemon finishes
				// writing; treat EPIPE/reset as expected noise, not a session outage.
				if isBenignClientDisconnect(err) {
					s.logger.Warn(fmt.Sprintf("client disconnected during request: %v", err))
				} else {
					s.logger.Error(fmt.Sprintf("connection handling failed: %v", err))
				}
			}
		}(conn)
	}
}

func (s *Server) shutdownTaskScheduler(timeout time.Duration) {
	s.taskScheduler.Close()
	if s.taskScheduler.WaitForIdle(timeout) {
		return
	}
	s.logger.Warn(fmt.Sprintf(
		"task scheduler shutdown timed out after %s: inflight=%d running=%d",
		timeout,
		s.taskScheduler.InflightCount(),
		s.taskScheduler.RunningCount(),
	))
}

func isBenignClientDisconnect(err error) bool {
	if err == nil {
		return false
	}
	// 显式哨兵错误：客户端正常关连接 / 服务端 shutdown / 握手阶段半读 EOF。
	// errors.Is 可穿透 fmt.Errorf("...: %w", err) 的包装。
	if errors.Is(err, io.EOF) ||
		errors.Is(err, net.ErrClosed) ||
		errors.Is(err, context.Canceled) ||
		errors.Is(err, io.ErrUnexpectedEOF) {
		return true
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "broken pipe") ||
		strings.Contains(msg, "connection reset") ||
		strings.Contains(msg, "use of closed network connection")
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
				"session_state": string(sess.currentState()),
				"state_trace":   sess.traceSnapshot(),
			},
		})
		sess.transition(StateClosed)
		return nil
	}

	reader := bufio.NewReader(conn)
	writer := bufio.NewWriter(conn)

	_ = conn.SetDeadline(deadlineFromMS(s.cfg.RequestTimeoutMS))
	if err := s.performHandshake(ctx, sess, reader, writer); err != nil {
		return err
	}

	// Register session after successful handshake so task steps can use it.
	s.registerSession(sess)
	defer s.unregisterSession(sess.id)
	return s.handleRequestLoop(ctx, sess, conn, reader, writer)
}

func (s *Server) handleRequestLoop(ctx context.Context, sess *session, conn net.Conn, reader *bufio.Reader, writer *bufio.Writer) error {
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
					"session_state": string(sess.currentState()),
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
					"session_state": string(sess.currentState()),
					"state_trace":   sess.traceSnapshot(),
				},
			})
			sess.transition(StateClosed)
			return nil
		}
		if req.RequestID == "" || req.Action == "" {
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
		// ping / get_capabilities / get_runtime_status / task_list may legitimately send {}.
		if req.Args == nil {
			req.Args = map[string]interface{}{}
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
					"session_state": string(sess.currentState()),
					"state_trace":   sess.traceSnapshot(),
				},
			})
			return nil
		}

		if !s.withinTimestampSkew(req.Timestamp) {
			response := s.invalidTimestampResponse(sess, req)
			s.logAuditRequest(sess, req, loopStartedAt, response)
			_ = writeResponseFrame(writer, response)
			continue
		}
		if !s.allowPrincipalRequest(sess) || !sess.allowRequest(s.cfg.RateLimitPerMinute) {
			principal, _ := principalForSession(sess)
			message := fmt.Sprintf(
				"rate limit exceeded: package=%s uid=%d session=%s action=%s limit=%d/min scope=principal",
				principal.PackageName,
				principal.UID,
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

		principal, ok := principalForSession(sess)
		if !ok {
			response := ipc.Response{
				RequestID: req.RequestID,
				OK:        false,
				Code:      ipc.CodeErrPeerVerifyFailed,
				Message:   "request has no authenticated principal",
				Data:      s.sessionData(sess),
			}
			s.logAuditRequest(sess, req, loopStartedAt, response)
			_ = writeResponseFrame(writer, response)
			continue
		}
		digest, err := canonicalArgsDigest(req.Args)
		if err != nil {
			response := ipc.Response{
				RequestID: req.RequestID,
				OK:        false,
				Code:      ipc.CodeErrInvalidRequest,
				Message:   "request arguments cannot be normalized for replay protection",
				Data:      mergeData(s.sessionData(sess), map[string]interface{}{"action": req.Action}),
			}
			s.logAuditRequest(sess, req, loopStartedAt, response)
			_ = writeResponseFrame(writer, response)
			continue
		}

		// Long-running sync actions: clear deadline so RequestTimeoutMS does not kill shell/capture.
		longAction := req.Action == "exec_shell_limited" || req.Action == "capture_screen"
		if longAction {
			_ = conn.SetDeadline(time.Time{})
		}
		var subscription *eventSubscription
		var response ipc.Response
		var status replayStatus
		if req.Action == ipc.ActionSubscribeEvents {
			response, subscription = s.prepareEventSubscription(sess, req)
			status = replayExecuted
		} else {
			response, status = s.idempotencyCache.execute(ctx, principal.OwnerKey(), req.Action, req.RequestID, digest, func() ipc.Response {
				return s.cacheableResponse(sess, s.handleRequestWithContext(ctx, sess, req))
			})
		}
		replayStatus := status
		if longAction {
			_ = conn.SetDeadline(deadlineFromMS(s.cfg.RequestTimeoutMS))
		}
		if replayStatus != replayExecuted && replayStatus != replayHit {
			response = s.replayErrorResponse(ctx, sess, req, replayStatus)
		}
		mergedResp := response
		// Cached bodies omit connection-local metadata, so a replay receives the
		// current session identity without losing action-specific response fields.
		mergedResp.Data = mergeData(s.sessionData(sess), response.Data)
		s.logAuditRequest(sess, req, loopStartedAt, mergedResp)
		if err := writeResponseFrame(writer, mergedResp); err != nil {
			if subscription != nil {
				s.unregisterEventSub(subscription.wake)
			}
			sess.transition(StateClosed)
			return err
		}
		if subscription != nil {
			err := s.streamEventSubscription(ctx, sess, subscription, conn, writer)
			sess.transition(StateClosed)
			if errors.Is(err, errSessionExpired) {
				return nil
			}
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

func (s *Server) cacheableResponse(sess *session, response ipc.Response) ipc.Response {
	data := copyMap(response.Data)
	for key := range s.sessionData(sess) {
		// package_name is also an action response field (for example the focused
		// window package). The replay principal already fixes the auth package.
		if key != "package_name" {
			delete(data, key)
		}
	}
	response.Data = data
	return response
}

func (s *Server) replayErrorResponse(ctx context.Context, sess *session, req ipc.Request, status replayStatus) ipc.Response {
	code := ipc.CodeErrInvalidRequest
	message := "request_id was already used with different arguments"
	switch status {
	case replayCacheFull:
		code = ipc.CodeErrRateLimited
		message = "replay cache is full; retry after existing requests complete"
	case replayContextCanceled:
		err := ctx.Err()
		if err == nil {
			err = context.Canceled
		}
		code = executionContextCode(err)
		message = executionContextMessage(err)
	case replayAborted:
		code = ipc.CodeErrUnknown
		message = "request execution aborted before a response was recorded"
	}
	return ipc.Response{
		RequestID: req.RequestID,
		OK:        false,
		Code:      code,
		Message:   message,
		Data:      mergeData(s.sessionData(sess), map[string]interface{}{"action": req.Action}),
	}
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
		maxPayloadBytes = 2 * 1024 * 1024
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
	snapshot := sess.snapshot()
	entry := audit.AuditLogEntry{
		RequestID:       req.RequestID,
		SessionID:       snapshot.id,
		CallerUID:       snapshot.peerCreds.UID,
		CallerPID:       snapshot.peerCreds.PID,
		PackageName:     snapshot.packageName,
		SignatureDigest: snapshot.signatureDigest,
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
	if err := s.logger.Log(entry); err != nil {
		s.logger.Warn(fmt.Sprintf("audit write failed: request_id=%s action=%s error=%v", req.RequestID, req.Action, err))
	}
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

// ExecuteStep implements task.StepExecutor using the task's immutable principal
// snapshot. Connection sessions are never consulted for step identity, so closing
// or reconnecting the submitting socket cannot affect execution.
func (s *Server) ExecuteStep(ctx context.Context, _ string, taskID string, action string, args map[string]interface{}, timeoutMS int) (code int, message string, data map[string]interface{}, latencyMS int64) {
	started := time.Now()
	if timeoutMS > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, time.Duration(timeoutMS)*time.Millisecond)
		defer cancel()
	}
	if err := ctx.Err(); err != nil {
		return executionContextCode(err), executionContextMessage(err), nil, time.Since(started).Milliseconds()
	}

	t, ok := s.taskScheduler.Registry().SnapshotByID(taskID)
	if !ok {
		return ipc.CodeErrTaskNotFound, ipc.ErrorMessage(ipc.CodeErrTaskNotFound), nil, time.Since(started).Milliseconds()
	}
	principal := t.Owner
	if principal.PackageName == "" || principal.UID <= 0 || principal.SignatureDigest == "" {
		return ipc.CodeErrPeerVerifyFailed, "task has no authenticated principal snapshot", nil, time.Since(started).Milliseconds()
	}

	req := ipc.Request{
		RequestID: fmt.Sprintf("task-step-%d", time.Now().UnixNano()),
		Timestamp: time.Now().Unix(),
		Action:    action,
		Args:      args,
		Version:   s.cfg.ProtocolVersion,
	}
	sess := s.taskExecutionSession(t)
	if !s.allowPrincipalRequest(sess) {
		resp := ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrRateLimited,
			Message:   ipc.ErrorMessage(ipc.CodeErrRateLimited),
			Data:      s.sessionData(sess),
		}
		s.logAuditRequest(sess, req, started, resp)
		return resp.Code, resp.Message, resp.Data, time.Since(started).Milliseconds()
	}
	resp := s.handleRequestWithContext(ctx, sess, req)
	if err := ctx.Err(); err != nil {
		resp = ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      executionContextCode(err),
			Message:   executionContextMessage(err),
			Data:      resp.Data,
		}
	}
	s.logAuditRequest(sess, req, started, resp)
	return resp.Code, resp.Message, resp.Data, time.Since(started).Milliseconds()
}

func (s *Server) taskExecutionSession(t task.Task) *session {
	principal := t.Owner
	sessionID := t.SessionID
	if sessionID == "" {
		sessionID = "task-" + t.ID
	}
	return &session{
		id:                     sessionID,
		state:                  StateReady,
		trace:                  []string{string(StateReady)},
		packageName:            principal.PackageName,
		signatureDigest:        principal.SignatureDigest,
		peerUID:                principal.UID,
		peerVerified:           true,
		peerPackageBound:       true,
		peerKnownPackages:      []string{principal.PackageName},
		peerVerificationMethod: "task-principal-snapshot",
		peerCreds: peerCredentials{
			UID:    principal.UID,
			Method: "task-principal-snapshot",
		},
		capabilities: s.capabilityList(),
	}
}

func executionContextCode(err error) int {
	if errors.Is(err, context.DeadlineExceeded) {
		return ipc.CodeErrTimeout
	}
	return ipc.CodeErrCancelled
}

func executionContextMessage(err error) string {
	if errors.Is(err, context.DeadlineExceeded) {
		return "task step timed out"
	}
	return "task step cancelled"
}

// onTaskStateChange is called by the scheduler whenever a task transitions state.
func (s *Server) onTaskStateChange(taskID string, oldState, newState task.TaskState, snapshot map[string]interface{}) {
	s.logger.Info(fmt.Sprintf("task state transition: task=%s %s -> %s", taskID, oldState, newState))
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
