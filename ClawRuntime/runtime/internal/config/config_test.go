package config

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestLoadSupportsBlockListsAndSecurityDurations(t *testing.T) {
	t.Parallel()

	testCases := []struct {
		name     string
		fileName string
		rootKey  string
	}{
		{name: "runtime root", fileName: "runtime.yaml", rootKey: "runtime"},
	}

	for _, tc := range testCases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			configPath := filepath.Join(t.TempDir(), tc.fileName)
			content := tc.rootKey + `:
  socket_name: "test_socket"
auth:
  allowed_packages:
    - "com.example.alpha"
    - "com.example.beta"
  allowed_signatures:
    - "sha256:abc"
security:
  handshake_ttl_ms: 1500
  session_ttl_ms: 61000
paths:
  readonly_whitelist:
    - "/tmp/a"
    - "/tmp/b"
capability:
  file_bridge_enabled: true
`
			if err := os.WriteFile(configPath, []byte(content), 0o600); err != nil {
				t.Fatalf("write config: %v", err)
			}

			cfg, err := Load(configPath)
			if err != nil {
				t.Fatalf("load config: %v", err)
			}

			if cfg.SocketName != "test_socket" {
				t.Fatalf("unexpected socket name: %q", cfg.SocketName)
			}
			if !reflect.DeepEqual(cfg.AllowedPackages, []string{"com.example.alpha", "com.example.beta"}) {
				t.Fatalf("unexpected allowed packages: %#v", cfg.AllowedPackages)
			}
			if !reflect.DeepEqual(cfg.AllowedSignatures, []string{"sha256:abc"}) {
				t.Fatalf("unexpected allowed signatures: %#v", cfg.AllowedSignatures)
			}
			if !reflect.DeepEqual(cfg.ReadonlyWhitelist, []string{"/tmp/a", "/tmp/b"}) {
				t.Fatalf("unexpected readonly whitelist: %#v", cfg.ReadonlyWhitelist)
			}
			if cfg.ChallengeTTLSec != 2 {
				t.Fatalf("unexpected challenge ttl: %d", cfg.ChallengeTTLSec)
			}
			if cfg.SessionTTLSec != 61 {
				t.Fatalf("unexpected session ttl: %d", cfg.SessionTTLSec)
			}
			if !cfg.FileBridgeEnabled {
				t.Fatal("expected file bridge to be enabled")
			}
		})
	}
}

func TestValidateRejectsEmptySharedSecret(t *testing.T) {
	t.Parallel()

	cfg := Default()
	cfg.AuthSharedSecret = ""

	if err := cfg.Validate(); err == nil {
		t.Fatal("expected empty shared secret to be rejected")
	}
}

func TestValidateAcceptsHexSharedSecret(t *testing.T) {
	t.Parallel()

	cfg := Default()
	cfg.AuthSharedSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

	if err := cfg.Validate(); err != nil {
		t.Fatalf("expected config to be valid, got: %v", err)
	}
}

func TestValidateRejectsInvalidSharedSecrets(t *testing.T) {
	t.Parallel()

	testCases := []struct {
		name   string
		secret string
	}{
		{name: "whitespace", secret: "   "},
		{name: "template placeholder", secret: "REPLACE_WITH_LOCAL_SECRET"},
		{name: "other replace placeholder", secret: "replace_with_generated_secret"},
		{name: "example placeholder", secret: "<64-char-random-secret>"},
		{name: "short hex", secret: "0123456789abcdef0123456789abcde"},
		{name: "non hex", secret: "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"},
	}

	for _, tc := range testCases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			cfg := Default()
			cfg.AuthSharedSecret = tc.secret
			if err := cfg.Validate(); err == nil {
				t.Fatalf("expected shared secret %q to be rejected", tc.secret)
			}
		})
	}
}

func TestValidateRejectsInvalidAllowedSignatures(t *testing.T) {
	t.Parallel()

	validSecret := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	testCases := []struct {
		name       string
		signatures []string
	}{
		{name: "invalid prefix", signatures: []string{"md5:abc"}},
		{name: "missing digest", signatures: []string{"sha256:"}},
		{name: "short digest", signatures: []string{"sha256:" + strings.Repeat("a", 63)}},
		{name: "long digest", signatures: []string{"sha256:" + strings.Repeat("a", 65)}},
		{name: "non hex digest", signatures: []string{"sha256:" + strings.Repeat("z", 64)}},
	}

	for _, tc := range testCases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			cfg := Default()
			cfg.AuthSharedSecret = validSecret
			cfg.AllowedSignatures = tc.signatures
			if err := cfg.Validate(); err == nil {
				t.Fatalf("expected signatures %#v to be rejected", tc.signatures)
			}
		})
	}
}

func TestValidateAcceptsCompleteAllowedSignature(t *testing.T) {
	t.Parallel()

	cfg := Default()
	cfg.AuthSharedSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	cfg.AllowedSignatures = []string{"", "sha256:ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789"}

	if err := cfg.Validate(); err != nil {
		t.Fatalf("expected config to be valid, got: %v", err)
	}
}

func TestValidateRejectsSurroundingWhitespace(t *testing.T) {
	t.Parallel()

	validSecret := "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	validSignature := "sha256:" + strings.Repeat("a", 64)
	testCases := []struct {
		name   string
		secret string
		sig    string
	}{
		{name: "leading shared secret whitespace", secret: " " + validSecret, sig: validSignature},
		{name: "trailing shared secret whitespace", secret: validSecret + "\t", sig: validSignature},
		{name: "leading signature whitespace", secret: validSecret, sig: " " + validSignature},
		{name: "trailing signature whitespace", secret: validSecret, sig: validSignature + " "},
	}

	for _, tc := range testCases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			cfg := Default()
			cfg.AuthSharedSecret = tc.secret
			cfg.AllowedSignatures = []string{tc.sig}
			if err := cfg.Validate(); err == nil {
				t.Fatal("expected surrounding whitespace to be rejected instead of normalized only for validation")
			}
		})
	}
}
