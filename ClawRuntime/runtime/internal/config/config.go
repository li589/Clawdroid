package config

import (
	"fmt"
	"os"
	"strings"

	"gopkg.in/yaml.v3"

	"clawdroid/runtime/internal/paths"
)

type Config struct {
	SocketName         string
	AuditDir           string
	LogLevel           string
	ProtocolVersion    int
	RequestTimeoutMS   int
	MaxPayloadBytes    int
	RateLimitPerMinute int
	MaxConcurrentTasks int
	MaxInflightTasks   int
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
		AuditDir:           paths.AuditDir,
		LogLevel:           "info",
		ProtocolVersion:    1,
		RequestTimeoutMS:   8000,
		// Must fit base64(file chunk) + JSON wrapper for read_file_limited responses.
		MaxPayloadBytes:    2 * 1024 * 1024,
		RateLimitPerMinute: 120,
		MaxConcurrentTasks: 8,
		MaxInflightTasks:   32,
		AuthSharedSecret:   "",
		AllowedPackages:    []string{"com.clawdroid.app", "com.clawdroid.app.debug"},
		AllowedSignatures:  []string{},
		ReadonlyWhitelist:  []string{"/sdcard", "/storage/emulated/0", "/sdcard/Pictures", "/sdcard/Download"},
		TimestampSkewSec:   120,
		ChallengeTTLSec:    30,
		SessionTTLSec:      300,
		InputInjectEnabled: false,
		ScreenshotEnabled:  false,
		ShellEnabled:       false,
		FileBridgeEnabled:  false,
	}
}

// stringOrList accepts both comma-separated scalar strings and YAML block
// sequences, unifying them into []string. This preserves backward
// compatibility with production runtime.yaml (comma strings) and config_test
// (block lists).
type stringOrList []string

func (s *stringOrList) UnmarshalYAML(value *yaml.Node) error {
	if value.Kind == yaml.ScalarNode {
		raw := strings.TrimSpace(value.Value)
		if raw == "" {
			*s = []string{}
			return nil
		}
		parts := strings.Split(raw, ",")
		result := make([]string, 0, len(parts))
		for _, p := range parts {
			if item := strings.TrimSpace(p); item != "" {
				result = append(result, item)
			}
		}
		*s = result
		return nil
	}
	if value.Kind == yaml.SequenceNode {
		var items []string
		if err := value.Decode(&items); err != nil {
			return err
		}
		*s = items
		return nil
	}
	*s = []string{}
	return nil
}

// yamlConfig is the intermediate structure for yaml.Unmarshal. Pointer types
// distinguish "not set in YAML" (nil → keep default) from "explicitly set to
// false/0" (non-nil → override default).
type yamlConfig struct {
	Runtime    *yamlRuntime    `yaml:"runtime"`
	Auth       *yamlAuth       `yaml:"auth"`
	Security   *yamlSecurity   `yaml:"security"`
	Paths      *yamlPaths      `yaml:"paths"`
	Capability *yamlCapability `yaml:"capability"`
}

type yamlRuntime struct {
	SocketName         *string `yaml:"socket_name"`
	ProtocolVersion    *int    `yaml:"protocol_version"`
	RequestTimeoutMS   *int    `yaml:"request_timeout_ms"`
	MaxPayloadBytes    *int    `yaml:"max_payload_bytes"`
	RateLimitPerMinute *int    `yaml:"rate_limit_per_minute"`
	MaxConcurrentTasks *int    `yaml:"max_concurrent_tasks"`
	MaxInflightTasks   *int    `yaml:"max_inflight_tasks"`
	LogLevel           *string `yaml:"log_level"`
	AuditDir           *string `yaml:"audit_dir"`
}

type yamlAuth struct {
	SharedSecret      *string      `yaml:"shared_secret"`
	AllowedPackages   stringOrList `yaml:"allowed_packages"`
	AllowedSignatures stringOrList `yaml:"allowed_signatures"`
	TimestampSkewSec  *int64       `yaml:"timestamp_skew_seconds"`
	ChallengeTTLSec   *int64       `yaml:"challenge_ttl_seconds"`
	SessionTTLSec     *int64       `yaml:"session_ttl_seconds"`
}

type yamlSecurity struct {
	HandshakeTTLMS     *int `yaml:"handshake_ttl_ms"`
	SessionTTLMS       *int `yaml:"session_ttl_ms"`
	RateLimitPerMinute *int `yaml:"rate_limit_per_minute"`
}

type yamlPaths struct {
	ReadonlyWhitelist stringOrList `yaml:"readonly_whitelist"`
}

type yamlCapability struct {
	InputInjectEnabled *bool `yaml:"input_inject_enabled"`
	ScreenshotEnabled  *bool `yaml:"screenshot_enabled"`
	ShellEnabled       *bool `yaml:"shell_enabled"`
	FileBridgeEnabled  *bool `yaml:"file_bridge_enabled"`
}

func Load(path string) (Config, error) {
	cfg := Default()

	content, err := os.ReadFile(path)
	if err != nil {
		return cfg, err
	}

	var yc yamlConfig
	if err := yaml.Unmarshal(content, &yc); err != nil {
		return cfg, err
	}

	// Runtime section
	if yc.Runtime != nil {
		if yc.Runtime.SocketName != nil {
			cfg.SocketName = *yc.Runtime.SocketName
		}
		if yc.Runtime.ProtocolVersion != nil {
			cfg.ProtocolVersion = *yc.Runtime.ProtocolVersion
		}
		if yc.Runtime.RequestTimeoutMS != nil {
			cfg.RequestTimeoutMS = *yc.Runtime.RequestTimeoutMS
		}
		if yc.Runtime.MaxPayloadBytes != nil {
			cfg.MaxPayloadBytes = *yc.Runtime.MaxPayloadBytes
		}
		if yc.Runtime.RateLimitPerMinute != nil {
			cfg.RateLimitPerMinute = *yc.Runtime.RateLimitPerMinute
		}
		if yc.Runtime.MaxConcurrentTasks != nil {
			cfg.MaxConcurrentTasks = *yc.Runtime.MaxConcurrentTasks
		}
		if yc.Runtime.MaxInflightTasks != nil {
			cfg.MaxInflightTasks = *yc.Runtime.MaxInflightTasks
		}
		if yc.Runtime.LogLevel != nil {
			cfg.LogLevel = *yc.Runtime.LogLevel
		}
		if yc.Runtime.AuditDir != nil {
			cfg.AuditDir = *yc.Runtime.AuditDir
		}
	}

	// Auth section
	if yc.Auth != nil {
		if yc.Auth.SharedSecret != nil {
			cfg.AuthSharedSecret = *yc.Auth.SharedSecret
		}
		if len(yc.Auth.AllowedPackages) > 0 {
			cfg.AllowedPackages = yc.Auth.AllowedPackages
		}
		if len(yc.Auth.AllowedSignatures) > 0 {
			cfg.AllowedSignatures = yc.Auth.AllowedSignatures
		}
		if yc.Auth.TimestampSkewSec != nil {
			cfg.TimestampSkewSec = *yc.Auth.TimestampSkewSec
		}
		if yc.Auth.ChallengeTTLSec != nil {
			cfg.ChallengeTTLSec = *yc.Auth.ChallengeTTLSec
		}
		if yc.Auth.SessionTTLSec != nil {
			cfg.SessionTTLSec = *yc.Auth.SessionTTLSec
		}
	}

	// Security section overrides Auth section (matches original switch order:
	// security.handshake_ttl_ms overwrites auth.challenge_ttl_seconds, etc.)
	if yc.Security != nil {
		if yc.Security.HandshakeTTLMS != nil {
			cfg.ChallengeTTLSec = int64((*yc.Security.HandshakeTTLMS + 999) / 1000)
		}
		if yc.Security.SessionTTLMS != nil {
			cfg.SessionTTLSec = int64((*yc.Security.SessionTTLMS + 999) / 1000)
		}
		if yc.Security.RateLimitPerMinute != nil {
			cfg.RateLimitPerMinute = *yc.Security.RateLimitPerMinute
		}
	}

	// Paths section
	if yc.Paths != nil {
		if len(yc.Paths.ReadonlyWhitelist) > 0 {
			cfg.ReadonlyWhitelist = yc.Paths.ReadonlyWhitelist
		}
	}

	// Capability section
	if yc.Capability != nil {
		if yc.Capability.InputInjectEnabled != nil {
			cfg.InputInjectEnabled = *yc.Capability.InputInjectEnabled
		}
		if yc.Capability.ScreenshotEnabled != nil {
			cfg.ScreenshotEnabled = *yc.Capability.ScreenshotEnabled
		}
		if yc.Capability.ShellEnabled != nil {
			cfg.ShellEnabled = *yc.Capability.ShellEnabled
		}
		if yc.Capability.FileBridgeEnabled != nil {
			cfg.FileBridgeEnabled = *yc.Capability.FileBridgeEnabled
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
