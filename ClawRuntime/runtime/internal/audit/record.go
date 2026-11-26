package audit

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

type AuditLevel string

const (
	AuditLevelLow    AuditLevel = "low"
	AuditLevelMedium AuditLevel = "medium"
	AuditLevelHigh   AuditLevel = "high"
)

type AuditLogEntry struct {
	RequestID       string     `json:"request_id"`
	SessionID       string     `json:"session_id"`
	CallerUID       int        `json:"caller_uid"`
	CallerPID       int        `json:"caller_pid"`
	PackageName     string     `json:"package_name,omitempty"`
	SignatureDigest string     `json:"signature_digest,omitempty"`
	Action          string     `json:"action"`
	ArgDigest       string     `json:"arg_digest,omitempty"`
	AuditLevel      AuditLevel `json:"audit_level"`
	StartedAt       int64      `json:"started_at"`
	EndedAt         int64      `json:"ended_at"`
	ResultCode      int        `json:"result_code"`
	ResultMessage   string     `json:"result_message,omitempty"`
	ErrorMessage    string     `json:"error_message,omitempty"`
	LatencyMS       int64      `json:"latency_ms"`
	DaemonVersion   string     `json:"daemon_version"`
	DaemonBuildTime string     `json:"daemon_build_time,omitempty"`
}

var versionInfo struct {
	mu        sync.RWMutex
	version   string
	buildTime string
}

func SetVersionInfo(version, buildTime string) {
	versionInfo.mu.Lock()
	defer versionInfo.mu.Unlock()
	versionInfo.version = version
	versionInfo.buildTime = buildTime
}

func GetVersionInfo() (version, buildTime string) {
	versionInfo.mu.RLock()
	defer versionInfo.mu.RUnlock()
	return versionInfo.version, versionInfo.buildTime
}

var auditLevelOfAction = map[string]AuditLevel{
	"ping":                AuditLevelLow,
	"get_capabilities":    AuditLevelLow,
	"subscribe_events":    AuditLevelLow,
	"report_xposed_focus": AuditLevelLow,
	"report_xposed_view":  AuditLevelLow,
	"capture_screen":      AuditLevelMedium,
	"read_file_limited":   AuditLevelMedium,
	"write_file_limited":  AuditLevelHigh,
	"stat_file_limited":   AuditLevelMedium,
	"list_dir_limited":    AuditLevelMedium,
	"inject_tap":          AuditLevelHigh,
	"inject_swipe":        AuditLevelHigh,
	"inject_keyevent":     AuditLevelHigh,
	"exec_shell_limited":  AuditLevelHigh,
	"task_submit":         AuditLevelHigh,
	"task_cancel":         AuditLevelHigh,
	"task_get":            AuditLevelMedium,
	"task_list":           AuditLevelMedium,
	"shell_job_list":      AuditLevelMedium,
	"shell_job_get":       AuditLevelMedium,
}

func AuditLevelForAction(action string) AuditLevel {
	if level, ok := auditLevelOfAction[action]; ok {
		return level
	}
	return AuditLevelMedium
}

func ComputeArgDigest(args map[string]interface{}) string {
	if len(args) == 0 {
		return ""
	}
	encoded, err := json.Marshal(args)
	if err != nil {
		return ""
	}
	digest := sha256.Sum256(encoded)
	return hex.EncodeToString(digest[:])
}

type FileLogger struct {
	mu          sync.RWMutex
	auditDir    string
	currentDate string
	writers     map[AuditLevel]*syncFileWriter
	lastError   string
	lastErrorAt time.Time
	maxFileSize int64
}

type syncFileWriter struct {
	file *os.File
	mu   sync.Mutex
	size int64
}

func NewFileLogger(auditDir string) (*FileLogger, error) {
	if err := os.MkdirAll(auditDir, 0755); err != nil {
		return nil, fmt.Errorf("create audit dir: %w", err)
	}
	fl := &FileLogger{
		auditDir:    auditDir,
		writers:     make(map[AuditLevel]*syncFileWriter),
		maxFileSize: 10 * 1024 * 1024,
	}
	return fl, nil
}

func (fl *FileLogger) Log(entry AuditLogEntry) error {
	dateStr := time.Now().Format("20060102")

	fl.mu.RLock()
	sw, ok := fl.writers[entry.AuditLevel]
	currentDate := fl.currentDate
	fl.mu.RUnlock()

	if !ok || currentDate != dateStr {
		if err := fl.openWriter(entry.AuditLevel, dateStr); err != nil {
			fl.setLastError("open writer: " + err.Error())
			return err
		}
	}

	payload, err := json.Marshal(entry)
	if err != nil {
		fl.setLastError("marshal: " + err.Error())
		return err
	}
	payload = append(payload, '\n')

	// Hold fl.mu.RLock during the write so rotate (which needs fl.mu.Lock)
	// cannot close sw.file while we're writing. Multiple Log calls can still
	// proceed concurrently (RLock is shared), but rotate is fully serialised.
	fl.mu.RLock()
	sw = fl.writers[entry.AuditLevel]
	if sw == nil {
		fl.mu.RUnlock()
		return fmt.Errorf("no writer for level %s", entry.AuditLevel)
	}

	sw.mu.Lock()
	n, err := sw.file.Write(payload)
	if err == nil {
		err = sw.file.Sync()
	}
	sw.size += int64(n)
	needRotate := sw.size >= fl.maxFileSize
	sw.mu.Unlock()
	fl.mu.RUnlock()

	if err != nil {
		fl.setLastError("write: " + err.Error())
		return err
	}

	// Synchronous rotate: no 'go'. Since we released RLock, rotate can acquire
	// fl.mu.Lock, but no other Log call can read a stale sw because rotate
	// fully replaces the map entry before releasing fl.mu.
	if needRotate {
		return fl.rotate(entry.AuditLevel, dateStr)
	}

	return nil
}

func (fl *FileLogger) openWriter(level AuditLevel, dateStr string) error {
	fl.mu.Lock()
	defer fl.mu.Unlock()

	if fl.currentDate == dateStr && fl.writers[level] != nil {
		return nil
	}

	if fl.writers[level] != nil && fl.currentDate != dateStr {
		fl.writers[level].mu.Lock()
		_ = fl.writers[level].file.Close()
		fl.writers[level].mu.Unlock()
		delete(fl.writers, level)
	}

	filename := fmt.Sprintf("audit-%s-%s.jsonl", level, dateStr)
	path := filepath.Join(fl.auditDir, filename)
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		return fmt.Errorf("open audit file %s: %w", path, err)
	}
	info, err := f.Stat()
	if err != nil {
		_ = f.Close()
		return fmt.Errorf("stat audit file %s: %w", path, err)
	}
	fl.writers[level] = &syncFileWriter{
		file: f,
		size: info.Size(),
	}
	fl.currentDate = dateStr
	return nil
}

func (fl *FileLogger) rotate(level AuditLevel, dateStr string) error {
	fl.mu.Lock()
	defer fl.mu.Unlock()
	sw := fl.writers[level]
	if sw == nil {
		return nil
	}
	sw.mu.Lock()
	closeErr := sw.file.Close()
	sw.mu.Unlock()
	delete(fl.writers, level)

	baseName := fmt.Sprintf("audit-%s-%s.jsonl", level, dateStr)
	path := filepath.Join(fl.auditDir, baseName)
	rotated := filepath.Join(fl.auditDir, fmt.Sprintf("audit-%s-%s-%d.jsonl", level, dateStr, time.Now().UnixNano()))
	if err := os.Rename(path, rotated); err != nil {
		fl.lastError = "rotate rename: " + err.Error()
		fl.lastErrorAt = time.Now()
		return fmt.Errorf("rotate audit file: %w", err)
	}
	if closeErr != nil {
		fl.lastError = "rotate close: " + closeErr.Error()
		fl.lastErrorAt = time.Now()
	}

	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		fl.lastError = "rotate reopen: " + err.Error()
		fl.lastErrorAt = time.Now()
		return fmt.Errorf("reopen audit file after rotation: %w", err)
	}
	fl.writers[level] = &syncFileWriter{file: f, size: 0}
	fl.currentDate = dateStr
	fl.pruneRotatedLocked(level, dateStr, 3)
	return nil
}

func (fl *FileLogger) pruneRotatedLocked(level AuditLevel, dateStr string, keep int) {
	pattern := filepath.Join(fl.auditDir, fmt.Sprintf("audit-%s-%s-*.jsonl", level, dateStr))
	matches, err := filepath.Glob(pattern)
	if err != nil || len(matches) <= keep {
		return
	}
	sort.Strings(matches)
	for _, old := range matches[:len(matches)-keep] {
		_ = os.Remove(old)
	}
}

func (fl *FileLogger) Close() error {
	fl.mu.Lock()
	defer fl.mu.Unlock()
	var lastErr error
	for _, sw := range fl.writers {
		sw.mu.Lock()
		if err := sw.file.Close(); err != nil {
			lastErr = err
		}
		sw.mu.Unlock()
	}
	fl.writers = make(map[AuditLevel]*syncFileWriter)
	return lastErr
}

func (fl *FileLogger) setLastError(msg string) {
	fl.mu.Lock()
	fl.lastError = msg
	fl.lastErrorAt = time.Now()
	fl.mu.Unlock()
}

func (fl *FileLogger) LastError() (string, time.Time) {
	fl.mu.RLock()
	defer fl.mu.RUnlock()
	return fl.lastError, fl.lastErrorAt
}
