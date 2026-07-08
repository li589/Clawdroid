package audit

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
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
	RequestID       string    `json:"request_id"`
	SessionID       string    `json:"session_id"`
	CallerUID       int       `json:"caller_uid"`
	CallerPID       int       `json:"caller_pid"`
	PackageName     string    `json:"package_name,omitempty"`
	SignatureDigest string    `json:"signature_digest,omitempty"`
	Action          string    `json:"action"`
	ArgDigest       string    `json:"arg_digest,omitempty"`
	AuditLevel      AuditLevel `json:"audit_level"`
	StartedAt       int64     `json:"started_at"`
	EndedAt         int64     `json:"ended_at"`
	ResultCode      int       `json:"result_code"`
	ResultMessage   string    `json:"result_message,omitempty"`
	ErrorMessage    string    `json:"error_message,omitempty"`
	LatencyMS       int64     `json:"latency_ms"`
	DaemonVersion   string    `json:"daemon_version"`
	DaemonBuildTime string    `json:"daemon_build_time,omitempty"`
}

var versionInfo struct {
	mu       sync.RWMutex
	version  string
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
	"subscribe_events":     AuditLevelLow,
	"capture_screen":       AuditLevelMedium,
	"read_file_limited":   AuditLevelMedium,
	"inject_tap":          AuditLevelHigh,
	"inject_swipe":        AuditLevelHigh,
	"exec_shell_limited":  AuditLevelHigh,
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
	keys := make([]string, 0, len(args))
	for k := range args {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	var sb strings.Builder
	for _, k := range keys {
		sb.WriteString(k)
		sb.WriteString("=")
		switch v := args[k]; v.(type) {
		case string:
			sb.WriteString(v.(string))
		case float64:
			fmt.Fprintf(&sb, "%v", v)
		default:
			sb.WriteString("?")
		}
		sb.WriteString(";")
	}

	h := sha256.New()
	h.Write([]byte(sb.String()))
	return hex.EncodeToString(h.Sum(nil))[:16]
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

	fl.mu.RLock()
	sw = fl.writers[entry.AuditLevel]
	fl.mu.RUnlock()
	if sw == nil {
		return fmt.Errorf("no writer for level %s", entry.AuditLevel)
	}

	payload, err := json.Marshal(entry)
	if err != nil {
		fl.setLastError("marshal: " + err.Error())
		return err
	}
	payload = append(payload, '\n')

	sw.mu.Lock()
	n, err := sw.file.Write(payload)
	if err == nil {
		err = sw.file.Sync()
	}
	sw.size += int64(n)
	sw.mu.Unlock()

	if err != nil {
		fl.setLastError("write: " + err.Error())
		return err
	}

	if sw.size >= fl.maxFileSize {
		go fl.rotate(entry.AuditLevel, dateStr)
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
	info, _ := f.Stat()
	fl.writers[level] = &syncFileWriter{
		file: f,
		size: info.Size(),
	}
	fl.currentDate = dateStr
	return nil
}

func (fl *FileLogger) rotate(level AuditLevel, dateStr string) {
	fl.mu.Lock()
	defer fl.mu.Unlock()
	if fl.currentDate == dateStr && fl.writers[level] != nil {
		return
	}
	if fl.writers[level] != nil {
		fl.writers[level].mu.Lock()
		_ = fl.writers[level].file.Close()
		fl.writers[level].mu.Unlock()
		delete(fl.writers, level)
	}
	filename := fmt.Sprintf("audit-%s-%s.jsonl", level, dateStr)
	path := filepath.Join(fl.auditDir, filename)
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		fl.setLastError("rotate: " + err.Error())
		return
	}
	fl.writers[level] = &syncFileWriter{file: f}
	fl.currentDate = dateStr
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
