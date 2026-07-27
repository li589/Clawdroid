package audit

import (
	"log"
	"strings"
	"sync"
	"time"
)

type LogLevel int

const (
	LogLevelDebug LogLevel = iota
	LogLevelInfo
	LogLevelWarn
	LogLevelError
)

type Logger struct {
	mu          sync.RWMutex
	lastError   string
	lastErrorAt time.Time
	fileLogger  *FileLogger
	minLevel    LogLevel
}

func NewLogger() *Logger {
	return &Logger{minLevel: LogLevelInfo}
}

func NewLoggerWithFileLogger(auditDir string) (*Logger, error) {
	fl, err := NewFileLogger(auditDir)
	if err != nil {
		return nil, err
	}
	return &Logger{fileLogger: fl, minLevel: LogLevelInfo}, nil
}

func ParseLogLevel(raw string) LogLevel {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "debug", "trace":
		return LogLevelDebug
	case "warn", "warning":
		return LogLevelWarn
	case "error":
		return LogLevelError
	default:
		return LogLevelInfo
	}
}

func (l *Logger) SetMinLevel(level LogLevel) {
	l.mu.Lock()
	l.minLevel = level
	l.mu.Unlock()
}

func (l *Logger) enabled(level LogLevel) bool {
	l.mu.RLock()
	defer l.mu.RUnlock()
	return level >= l.minLevel
}

func (l *Logger) Debug(message string) {
	if !l.enabled(LogLevelDebug) {
		return
	}
	log.Printf("DEBUG: %s", message)
}

func (l *Logger) Info(message string) {
	if !l.enabled(LogLevelInfo) {
		return
	}
	log.Printf("INFO: %s", message)
}

// Warn logs without updating LastError (for expected client disconnect noise).
func (l *Logger) Warn(message string) {
	if !l.enabled(LogLevelWarn) {
		return
	}
	log.Printf("WARN: %s", message)
}

func (l *Logger) Error(message string) {
	l.mu.Lock()
	l.lastError = message
	l.lastErrorAt = time.Now()
	l.mu.Unlock()
	// Errors always emit regardless of minLevel to avoid silent failures.
	log.Printf("ERROR: %s", message)
}

func (l *Logger) LastError() (string, time.Time) {
	l.mu.RLock()
	defer l.mu.RUnlock()
	return l.lastError, l.lastErrorAt
}

func (l *Logger) Log(entry AuditLogEntry) error {
	if l.fileLogger == nil {
		return nil
	}
	return l.fileLogger.Log(entry)
}

func (l *Logger) Close() error {
	if l.fileLogger == nil {
		return nil
	}
	return l.fileLogger.Close()
}
