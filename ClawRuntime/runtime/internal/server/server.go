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
	"sync"
	"time"

	"clawdroid/runtime/internal/audit"
	"clawdroid/runtime/internal/config"
	"clawdroid/runtime/internal/ipc"
)

var errPayloadTooLarge = errors.New("payload too large")

type Server struct {
	cfg    config.Config
	logger *audit.Logger

	startedAt            time.Time
	diagMu               sync.RWMutex
	lastRateLimitAt      time.Time
	lastRateLimitMessage string
	rateLimitHits        int
}

func New(cfg config.Config, logger *audit.Logger) *Server {
	return &Server{
		cfg:       cfg,
		logger:    logger,
		startedAt: time.Now(),
	}
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

		if err := s.handleConnection(conn); err != nil {
			s.logger.Error(fmt.Sprintf("connection handling failed: %v", err))
		}
	}
}

func (s *Server) handleConnection(conn net.Conn) error {
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

	for {
		_ = conn.SetDeadline(deadlineFromMS(s.cfg.RequestTimeoutMS))

		req, err := decodeRequestFrame(reader, s.cfg.MaxPayloadBytes)
		if err != nil {
			if errors.Is(err, io.EOF) {
				sess.transition(StateClosed)
				return nil
			}
			code := ipc.CodeErrInvalidRequest
			if errors.Is(err, errPayloadTooLarge) {
				code = ipc.CodeErrPayloadTooLarge
			}
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
		if req.RequestID == "" || req.Action == "" || req.Args == nil {
			_ = writeResponseFrame(writer, ipc.Response{
				RequestID: req.RequestID,
				OK:        false,
				Code:      ipc.CodeErrInvalidRequest,
				Message:   ipc.ErrorMessage(ipc.CodeErrInvalidRequest),
				Data:      s.sessionData(sess),
			})
			continue
		}
		expectedCapability, actionAllowed := expectedCapabilityForAction(req.Action)
		if !actionAllowed {
			_ = writeResponseFrame(writer, ipc.Response{
				RequestID: req.RequestID,
				OK:        false,
				Code:      ipc.CodeErrActionNotAllowed,
				Message:   ipc.ErrorMessage(ipc.CodeErrActionNotAllowed),
				Data:      s.sessionData(sess),
			})
			continue
		}
		if req.Capability != expectedCapability {
			_ = writeResponseFrame(writer, ipc.Response{
				RequestID: req.RequestID,
				OK:        false,
				Code:      ipc.CodeErrCapabilityNotGranted,
				Message:   ipc.ErrorMessage(ipc.CodeErrCapabilityNotGranted),
				Data: mergeData(s.sessionData(sess), map[string]interface{}{
					"expected_capability": expectedCapability,
					"received_capability": req.Capability,
				}),
			})
			continue
		}

		if sess.sessionExpired(s.cfg.SessionTTLSec) {
			sess.setAuthFailure(1004, "session expired")
			sess.transition(StateClosed)
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

		if req.Action == "subscribe_events" {
			if err := s.handleSubscribeEvents(sess, req, conn, writer); err != nil {
				sess.transition(StateClosed)
				return err
			}
			continue
		}

		response := s.handleRequest(sess, req)
		if err := writeResponseFrame(writer, response); err != nil {
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
