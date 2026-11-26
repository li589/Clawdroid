package server

import (
	"io"
	"os"
	"path/filepath"
	"testing"

	"clawdroid/runtime/internal/config"
)

func TestOpenAllowedReadFileRejectsAuditDir(t *testing.T) {
	t.Parallel()

	dir := t.TempDir()
	auditDir := filepath.Join(dir, "audit")
	if err := os.MkdirAll(auditDir, 0o700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(auditDir, "secret.log")
	if err := os.WriteFile(path, []byte("secret"), 0o600); err != nil {
		t.Fatal(err)
	}
	srv := &Server{cfg: config.Config{AuditDir: auditDir}}
	if file, _, err := srv.openAllowedReadFile(path); err == nil {
		_ = file.Close()
		t.Fatal("audit file was opened for IPC read")
	}
}

func TestOpenAllowedReadFileAllowsInRootSymlink(t *testing.T) {
	t.Parallel()

	root := t.TempDir()
	realPath := filepath.Join(root, "note.txt")
	if err := os.WriteFile(realPath, []byte("ok"), 0o600); err != nil {
		t.Fatal(err)
	}
	linkPath := filepath.Join(root, "note-link.txt")
	if err := os.Symlink(realPath, linkPath); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	srv := &Server{cfg: config.Config{ReadonlyWhitelist: []string{root}}}
	file, resolved, err := srv.openAllowedReadFile(linkPath)
	if err != nil {
		t.Fatalf("open in-root symlink: %v", err)
	}
	defer file.Close()
	data, err := io.ReadAll(file)
	if err != nil || string(data) != "ok" {
		t.Fatalf("read = %q, err = %v", data, err)
	}
	if resolved != realPath {
		t.Fatalf("resolved = %q, want %q", resolved, realPath)
	}
}

func TestOpenAllowedWriteFileRejectsEscapeWithoutTruncatingTarget(t *testing.T) {
	t.Parallel()

	base := t.TempDir()
	allowed := filepath.Join(base, "allowed")
	outside := t.TempDir()
	if err := os.MkdirAll(allowed, 0o700); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(outside, "keep.txt")
	if err := os.WriteFile(target, []byte("keep"), 0o600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(allowed, "escape.txt")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	srv := &Server{cfg: config.Config{ReadonlyWhitelist: []string{allowed}}}
	if file, _, err := srv.openAllowedWriteFile(link, false); err == nil {
		_ = file.Close()
		t.Fatal("write escape was accepted")
	}
	data, err := os.ReadFile(target)
	if err != nil || string(data) != "keep" {
		t.Fatalf("outside target changed: %q, err = %v", data, err)
	}
}

func TestOpenAllowedWriteFileRejectsReadonlyAndAuditPaths(t *testing.T) {
	t.Parallel()

	base := t.TempDir()
	auditDir := filepath.Join(base, "audit")
	xposedDir := filepath.Join(base, "xposed")
	if err := os.MkdirAll(auditDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(xposedDir, 0o700); err != nil {
		t.Fatal(err)
	}
	srv := &Server{cfg: config.Config{AuditDir: auditDir, ReadonlyWhitelist: []string{xposedDir}}}
	for _, path := range []string{filepath.Join(auditDir, "audit.log"), filepath.Join(xposedDir, "focus.json")} {
		if file, _, err := srv.openAllowedWriteFile(path, false); err == nil {
			_ = file.Close()
			t.Fatalf("protected path was opened for write: %s", path)
		}
	}
}

func TestVerifyOpenedPathWithinRoot(t *testing.T) {
	t.Parallel()

	root := t.TempDir()
	outside := t.TempDir()
	insidePath := filepath.Join(root, "inside.txt")
	outsidePath := filepath.Join(outside, "outside.txt")
	if err := os.WriteFile(insidePath, []byte("inside"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(outsidePath, []byte("outside"), 0o600); err != nil {
		t.Fatal(err)
	}
	inside, err := os.Open(insidePath)
	if err != nil {
		t.Fatal(err)
	}
	defer inside.Close()
	outsideFile, err := os.Open(outsidePath)
	if err != nil {
		t.Fatal(err)
	}
	defer outsideFile.Close()
	if !verifyOpenedPathWithinRoot(inside, root) {
		t.Fatal("inside file was rejected")
	}
	if verifyOpenedPathWithinRoot(outsideFile, root) {
		t.Fatal("outside file was accepted")
	}

}
