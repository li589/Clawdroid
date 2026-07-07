package config

import (
	"fmt"
	"math"
	"os"
	"strconv"
	"strings"
)

type Config struct {
	SocketName         string
	AuditDir           string
	LogLevel           string
	ProtocolVersion    int
	RequestTimeoutMS   int
	MaxPayloadBytes    int
	RateLimitPerMinute int
	AuthSharedSecret   string
	AllowedPackages    []string
	AllowedSignatures  []string
	ReadonlyWhitelist  []string
	TimestampSkewSec   int64
	ChallengeTTLSec    int64
	SessionTTLSec      int64
	InputInjectEnabled bool
	ScreenshotEnabled  bool
	ShellEnabled       bool
	FileBridgeEnabled  bool
}

func Default() Config {
	return Config{
		SocketName:         "clawdroid_secure_ipc",
		AuditDir:           "/data/local/tmp/clawdroid/audit",
		LogLevel:           "info",
		ProtocolVersion:    1,
		RequestTimeoutMS:   8000,
		MaxPayloadBytes:    262144,
		RateLimitPerMinute: 120,
		AuthSharedSecret:   "",
		AllowedPackages:    []string{"com.clawdroid.app", "com.clawdroid.app.debug"},
		AllowedSignatures:  []string{},
		ReadonlyWhitelist:  []string{"/sdcard/Pictures", "/sdcard/Download"},
		TimestampSkewSec:   120,
		ChallengeTTLSec:    30,
		SessionTTLSec:      300,
		InputInjectEnabled: false,
		ScreenshotEnabled:  false,
		ShellEnabled:       false,
		FileBridgeEnabled:  false,
	}
}

func Load(path string) (Config, error) {
	cfg := Default()

	content, err := os.ReadFile(path)
	if err != nil {
		return cfg, err
	}

	lines := strings.Split(string(content), "\n")
	var section string

	for index := 0; index < len(lines); index++ {
		rawLine := lines[index]
		line := strings.TrimSpace(rawLine)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}

		if !strings.HasPrefix(rawLine, " ") && strings.HasSuffix(line, ":") && !strings.Contains(line, "\"") {
			section = normalizeSectionName(strings.TrimSuffix(line, ":"))
			continue
		}

		if strings.HasPrefix(rawLine, "  ") && strings.HasSuffix(line, ":") && !strings.Contains(line, "\"") {
			key := strings.TrimSuffix(line, ":")
			listKey := section + "." + key
			values, nextIndex := readIndentedList(lines, index+1)
			applyListValue(&cfg, listKey, values)
			index = nextIndex - 1
			continue
		}

		parts := strings.SplitN(line, ":", 2)
		if len(parts) != 2 {
			continue
		}

		key := strings.TrimSpace(parts[0])
		value := normalizeValue(parts[1])

		switch normalizeConfigKey(section + "." + key) {
		case "runtime.socket_name":
			cfg.SocketName = value
		case "runtime.protocol_version":
			cfg.ProtocolVersion = parseInt(value, cfg.ProtocolVersion)
		case "runtime.request_timeout_ms":
			cfg.RequestTimeoutMS = parseInt(value, cfg.RequestTimeoutMS)
		case "runtime.max_payload_bytes":
			cfg.MaxPayloadBytes = parseInt(value, cfg.MaxPayloadBytes)
		case "runtime.rate_limit_per_minute":
			cfg.RateLimitPerMinute = parseInt(value, cfg.RateLimitPerMinute)
		case "runtime.log_level":
			cfg.LogLevel = value
		case "runtime.audit_dir":
			cfg.AuditDir = value
		case "auth.shared_secret":
			cfg.AuthSharedSecret = value
		case "auth.allowed_packages":
			cfg.AllowedPackages = parseList(value, cfg.AllowedPackages)
		case "auth.allowed_signatures":
			cfg.AllowedSignatures = parseList(value, cfg.AllowedSignatures)
		case "auth.timestamp_skew_seconds":
			cfg.TimestampSkewSec = int64(parseInt(value, int(cfg.TimestampSkewSec)))
		case "auth.challenge_ttl_seconds":
			cfg.ChallengeTTLSec = int64(parseInt(value, int(cfg.ChallengeTTLSec)))
		case "auth.session_ttl_seconds":
			cfg.SessionTTLSec = int64(parseInt(value, int(cfg.SessionTTLSec)))
		case "security.handshake_ttl_ms":
			cfg.ChallengeTTLSec = parseMillisecondsAsSeconds(value, cfg.ChallengeTTLSec)
		case "security.session_ttl_ms":
			cfg.SessionTTLSec = parseMillisecondsAsSeconds(value, cfg.SessionTTLSec)
		case "capability.input_inject_enabled":
			cfg.InputInjectEnabled = parseBool(value, cfg.InputInjectEnabled)
		case "capability.screenshot_enabled":
			cfg.ScreenshotEnabled = parseBool(value, cfg.ScreenshotEnabled)
		case "capability.shell_enabled":
			cfg.ShellEnabled = parseBool(value, cfg.ShellEnabled)
		case "capability.file_bridge_enabled":
			cfg.FileBridgeEnabled = parseBool(value, cfg.FileBridgeEnabled)
		case "paths.readonly_whitelist":
			cfg.ReadonlyWhitelist = parseList(value, cfg.ReadonlyWhitelist)
		case "security.rate_limit_per_minute":
			cfg.RateLimitPerMinute = parseInt(value, cfg.RateLimitPerMinute)
		}
	}

	return cfg, nil
}

func (c Config) Validate() error {
	if strings.TrimSpace(c.AuthSharedSecret) == "" {
		return fmt.Errorf("auth.shared_secret must not be empty")
	}
	if strings.EqualFold(strings.TrimSpace(c.AuthSharedSecret), "REPLACE_WITH_LOCAL_SECRET") {
		return fmt.Errorf("auth.shared_secret must be replaced before runtime startup")
	}
	for _, signature := range c.AllowedSignatures {
		normalized := strings.ToLower(strings.TrimSpace(signature))
		if normalized == "" {
			continue
		}
		if !strings.HasPrefix(normalized, "sha256:") {
			return fmt.Errorf("auth.allowed_signatures must use sha256: prefix")
		}
	}
	return nil
}

func readIndentedList(lines []string, start int) ([]string, int) {
	items := make([]string, 0, 4)
	index := start
	for ; index < len(lines); index++ {
		line := lines[index]
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue
		}
		if !strings.HasPrefix(line, "    ") || !strings.HasPrefix(trimmed, "- ") {
			break
		}
		item := normalizeValue(strings.TrimSpace(strings.TrimPrefix(trimmed, "- ")))
		if item != "" {
			items = append(items, item)
		}
	}
	return items, index
}

func applyListValue(cfg *Config, key string, values []string) {
	if len(values) == 0 {
		return
	}
	switch key {
	case "auth.allowed_packages":
		cfg.AllowedPackages = values
	case "auth.allowed_signatures":
		cfg.AllowedSignatures = values
	case "paths.readonly_whitelist":
		cfg.ReadonlyWhitelist = values
	}
}

func normalizeValue(raw string) string {
	value := strings.TrimSpace(raw)
	value = strings.Trim(value, "\"")
	return value
}

func normalizeSectionName(section string) string {
	return strings.TrimSpace(section)
}

func normalizeConfigKey(key string) string {
	return strings.TrimSpace(key)
}

func parseInt(value string, fallback int) int {
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func parseBool(value string, fallback bool) bool {
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func parseMillisecondsAsSeconds(value string, fallback int64) int64 {
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed <= 0 {
		return fallback
	}
	return int64(math.Ceil(float64(parsed) / 1000.0))
}

func parseList(value string, fallback []string) []string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}

	parts := strings.Split(value, ",")
	result := make([]string, 0, len(parts))
	for _, part := range parts {
		item := strings.TrimSpace(strings.Trim(part, "\""))
		if item != "" {
			result = append(result, item)
		}
	}

	if len(result) == 0 {
		return fallback
	}
	return result
}
