package server

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"clawdroid/runtime/internal/config"
)

func TestResolveAllowedReadPathAllowsWhitelistedFile(t *testing.T) {
	t.Parallel()

	root := filepath.Join(t.TempDir(), "allowed")
	if err := os.MkdirAll(root, 0o700); err != nil {
		t.Fatalf("mkdir root: %v", err)
	}
	targetPath := filepath.Join(root, "note.txt")
	if err := os.WriteFile(targetPath, []byte("ok"), 0o600); err != nil {
		t.Fatalf("write target file: %v", err)
	}

	srv := &Server{
		cfg: config.Config{
			AuditDir:          filepath.Join(root, "audit"),
			ReadonlyWhitelist: []string{root},
		},
	}

	resolvedPath, err := srv.resolveAllowedReadPath(targetPath)
	if err != nil {
		t.Fatalf("resolve allowed path: %v", err)
	}
	if resolvedPath != targetPath {
		t.Fatalf("unexpected resolved path: got %q want %q", resolvedPath, targetPath)
	}
}

func TestResolveAllowedReadPathRejectsRelativePath(t *testing.T) {
	t.Parallel()

	srv := &Server{cfg: config.Default()}
	if _, err := srv.resolveAllowedReadPath("relative/file.txt"); err == nil {
		t.Fatal("expected relative path to be rejected")
	}
}

func TestResolveAllowedReadPathRejectsSymlinkEscape(t *testing.T) {
	t.Parallel()

	baseDir := t.TempDir()
	allowedRoot := filepath.Join(baseDir, "allowed")
	outsideRoot := filepath.Join(baseDir, "outside")
	if err := os.MkdirAll(allowedRoot, 0o700); err != nil {
		t.Fatalf("mkdir allowed root: %v", err)
	}
	if err := os.MkdirAll(outsideRoot, 0o700); err != nil {
		t.Fatalf("mkdir outside root: %v", err)
	}

	outsideFile := filepath.Join(outsideRoot, "secret.txt")
	if err := os.WriteFile(outsideFile, []byte("secret"), 0o600); err != nil {
		t.Fatalf("write outside file: %v", err)
	}

	linkPath := filepath.Join(allowedRoot, "secret-link.txt")
	if err := os.Symlink(outsideFile, linkPath); err != nil {
		lowerError := strings.ToLower(err.Error())
		if strings.Contains(lowerError, "privilege") || strings.Contains(lowerError, "not permitted") || strings.Contains(lowerError, "already exists") {
			t.Skipf("symlink not available on this environment: %v", err)
		}
		t.Skipf("unable to create symlink in test environment: %v", err)
	}

	srv := &Server{
		cfg: config.Config{
			AuditDir:          filepath.Join(baseDir, "audit"),
			ReadonlyWhitelist: []string{allowedRoot},
		},
	}

	if _, err := srv.resolveAllowedReadPath(linkPath); err == nil {
		t.Fatal("expected symlink escape to be rejected")
	}
}
