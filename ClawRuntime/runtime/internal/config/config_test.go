package config

import (
	"os"
	"path/filepath"
	"reflect"
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

func TestValidateAcceptsNonEmptySharedSecret(t *testing.T) {
	t.Parallel()

	cfg := Default()
	cfg.AuthSharedSecret = "local-secret"

	if err := cfg.Validate(); err != nil {
		t.Fatalf("expected config to be valid, got: %v", err)
	}
}

func TestValidateRejectsTemplatePlaceholderSecret(t *testing.T) {
	t.Parallel()

	cfg := Default()
	cfg.AuthSharedSecret = "REPLACE_WITH_LOCAL_SECRET"

	if err := cfg.Validate(); err == nil {
		t.Fatal("expected placeholder secret to be rejected")
	}
}

func TestValidateRejectsInvalidAllowedSignaturePrefix(t *testing.T) {
	t.Parallel()

	cfg := Default()
	cfg.AuthSharedSecret = "local-secret"
	cfg.AllowedSignatures = []string{"md5:abc"}

	if err := cfg.Validate(); err == nil {
		t.Fatal("expected invalid signature prefix to be rejected")
	}
}
