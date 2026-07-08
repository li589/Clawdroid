package server

import (
	"fmt"
	"os"
	"time"
)

const (
	DaemonVersion   = "0.2.0"
	DaemonBuildTime = ""
)

func (s *Server) recordRateLimit(message string) {
	s.diagMu.Lock()
	s.lastRateLimitAt = time.Now()
	s.lastRateLimitMessage = message
	s.rateLimitHits++
	s.diagMu.Unlock()
	s.logger.Error(message)
}

func (s *Server) diagnosticsState() map[string]interface{} {
	lastError, lastErrorAt := s.logger.LastError()

	s.diagMu.RLock()
	lastRateLimitAt := s.lastRateLimitAt
	lastRateLimitMessage := s.lastRateLimitMessage
	rateLimitHits := s.rateLimitHits
	s.diagMu.RUnlock()

	return map[string]interface{}{
		"last_error":              lastError,
		"last_error_at":           unixOrZero(lastErrorAt),
		"last_rate_limit":         lastRateLimitMessage,
		"last_rate_limit_at":      unixOrZero(lastRateLimitAt),
		"rate_limit_hits":         rateLimitHits,
		"rate_limit_per_minute":   s.cfg.RateLimitPerMinute,
		"readonly_whitelist":      s.allowedReadRoots(),
		"uptime_seconds":          int64(time.Since(s.startedAt).Seconds()),
		"started_at":              s.startedAt.Unix(),
	}
}

func (s *Server) versionState() map[string]interface{} {
	return map[string]interface{}{
		"daemon_status":     "ok",
		"version": DaemonVersion,
		"protocol_version":  s.cfg.ProtocolVersion,
		"socket_name":       s.cfg.SocketName,
		"log_level":         s.cfg.LogLevel,
	}
}

func (s *Server) healthState(sess *session) map[string]interface{} {
	rootAvailable := detectRootAvailable()
	accessibilityEnabled := detectAccessibilityEnabled(sess.packageName)
	lsposedAvailable := detectLSPosedAvailable()
	lsposedRuntimeMarker := detectLSPosedRuntimeMarker(sess.packageName)
	runtimePID, runtimeRSSKB := readCurrentProcessMetrics()

	return mergeData(s.versionState(), mergeData(mergeData(s.diagnosticsState(), map[string]interface{}{
		"root":                      rootAvailable,
		"accessibility":             accessibilityEnabled,
		"lsposed":                   lsposedAvailable,
		"lsposed_runtime_loaded":    lsposedRuntimeMarker.XposedInjected,
		"lsposed_runtime_process":   lsposedRuntimeMarker.ProcessName,
		"lsposed_runtime_loaded_at": lsposedRuntimeMarker.LoadedAtEpochMS,
		"audit_dir":                 s.cfg.AuditDir,
		"request_timeout_ms":        s.cfg.RequestTimeoutMS,
		"session_ttl_seconds":       s.cfg.SessionTTLSec,
		"challenge_ttl_seconds":     s.cfg.ChallengeTTLSec,
		"input_inject_enabled":      s.cfg.InputInjectEnabled,
		"screenshot_enabled":        s.cfg.ScreenshotEnabled,
		"shell_enabled":             s.cfg.ShellEnabled,
		"file_bridge_enabled":       s.cfg.FileBridgeEnabled,
		"capabilities":              s.capabilityList(),
		"server_time":               time.Now().Unix(),
	}), processMetricsState(runtimePID, runtimeRSSKB)))
}

func processMetricsState(pid int, rssKB int64) map[string]interface{} {
	return map[string]interface{}{
		"runtime_pid":    pid,
		"runtime_rss_kb": rssKB,
	}
}

func unixOrZero(value time.Time) int64 {
	if value.IsZero() {
		return 0
	}
	return value.Unix()
}

func (s *Server) reportServerError(format string, args ...interface{}) {
	s.logger.Error(fmt.Sprintf(format, args...))
}

func (s *Server) fileInfoOrZero(path string) map[string]interface{} {
	info, err := os.Stat(path)
	if err != nil {
		return map[string]interface{}{}
	}
	return map[string]interface{}{
		"exists":           true,
		"size":             info.Size(),
		"modified_at_unix": info.ModTime().Unix(),
	}
}
