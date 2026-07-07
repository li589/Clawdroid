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
}

func NewLogger() *Logger {
	return &Logger{}
}

func (l *Logger) Info(message string) {
	log.Printf("INFO: %s", message)
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
