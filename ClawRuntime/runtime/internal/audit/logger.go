package audit

import (
	"log"
	"sync"
	"time"
)

type Logger struct {
	mu          sync.RWMutex
	lastError   string
	lastErrorAt time.Time
	fileLogger  *FileLogger
}

func NewLogger() *Logger {
	return &Logger{}
}

func NewLoggerWithFileLogger(auditDir string) (*Logger, error) {
	fl, err := NewFileLogger(auditDir)
	if err != nil {
		return nil, err
	}
	return &Logger{fileLogger: fl}, nil
}

func (l *Logger) Info(message string) {
	log.Printf("INFO: %s", message)
}

// Warn logs without updating LastError (for expected client disconnect noise).
func (l *Logger) Warn(message string) {
	log.Printf("WARN: %s", message)
}

func (l *Logger) Error(message string) {
	l.mu.Lock()
	l.lastError = message
	l.lastErrorAt = time.Now()
	l.mu.Unlock()
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
