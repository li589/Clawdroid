package server

import (
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"clawdroid/runtime/internal/ipc"
)

type readFileArgs struct {
	Path     string `json:"path"`
	Offset   int64  `json:"offset"`
	MaxBytes int64  `json:"max_bytes"`
}

func (s *Server) handleReadFileLimited(sess *session, req ipc.Request) ipc.Response {
	if !s.cfg.FileBridgeEnabled {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrFileOutOfScope,
			Message:   "file bridge disabled",
			Data:      s.sessionData(sess),
		}
	}

	args, err := parseReadFileArgs(req.Args)
	if err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	resolvedPath, err := s.resolveAllowedReadPath(args.Path)
	if err != nil {
		s.reportServerError("read_file_limited denied: %v", err)
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrFileOutOfScope,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	file, err := os.Open(resolvedPath)
	if err != nil {
		s.reportServerError("read_file_limited open failed: %v", err)
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrFileReadFailed,
			Message:   fmt.Sprintf("open file failed: %v", err),
			Data:      s.sessionData(sess),
		}
	}
	defer file.Close()

	buffer := make([]byte, args.MaxBytes)
	if _, err := file.Seek(args.Offset, 0); err != nil {
		s.reportServerError("read_file_limited seek failed: %v", err)
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrFileReadFailed,
			Message:   fmt.Sprintf("seek file failed: %v", err),
			Data:      s.sessionData(sess),
		}
	}

	readBytes, err := file.Read(buffer)
	if err != nil && !errors.Is(err, io.EOF) {
		s.reportServerError("read_file_limited read failed: %v", err)
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrFileReadFailed,
			Message:   fmt.Sprintf("read file failed: %v", err),
			Data:      s.sessionData(sess),
		}
	}

	stat, statErr := file.Stat()
	totalSize := int64(0)
	if statErr == nil {
		totalSize = stat.Size()
	}

	data := mergeData(s.sessionData(sess), map[string]interface{}{
		"path":           resolvedPath,
		"offset":         args.Offset,
		"read_bytes":     readBytes,
		"max_bytes":      args.MaxBytes,
		"total_size":     totalSize,
		"eof":            args.Offset+int64(readBytes) >= totalSize && totalSize > 0,
		"content_base64": base64.StdEncoding.EncodeToString(buffer[:readBytes]),
	})

	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data:      data,
	}
}

type writeFileArgs struct {
	Path    string `json:"path"`
	Content string `json:"content"`
	Append  bool   `json:"append"`
}

type statFileArgs struct {
	Path        string `json:"path"`
	ComputeHash bool   `json:"compute_hash"`
}

func (s *Server) handleWriteFileLimited(sess *session, req ipc.Request) ipc.Response {
	if !s.cfg.FileBridgeEnabled {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrFileOutOfScope,
			Message:   "file bridge disabled",
			Data:      s.sessionData(sess),
		}
	}

	args, err := parseWriteFileArgs(req.Args)
	if err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	resolvedPath, err := s.resolveAllowedWritePath(args.Path)
	if err != nil {
		s.reportServerError("write_file_limited denied: %v", err)
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrFileOutOfScope,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	content := []byte(args.Content)
	if len(content) > 1048576 {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   "content exceeds 1MiB limit",
			Data:      s.sessionData(sess),
		}
	}

	flag := os.O_CREATE | os.O_WRONLY
	if args.Append {
		flag |= os.O_APPEND
	} else {
		flag |= os.O_TRUNC
	}
	file, err := os.OpenFile(resolvedPath, flag, 0o600)
	if err != nil {
		s.reportServerError("write_file_limited open failed: %v", err)
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrFileWriteFailed,
			Message:   fmt.Sprintf("open file for write failed: %v", err),
			Data:      s.sessionData(sess),
		}
	}
	defer file.Close()

	written, err := file.Write(content)
	if err != nil {
		s.reportServerError("write_file_limited write failed: %v", err)
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrFileWriteFailed,
			Message:   fmt.Sprintf("write file failed: %v", err),
			Data:      s.sessionData(sess),
		}
	}

	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data: mergeData(s.sessionData(sess), map[string]interface{}{
			"path":          resolvedPath,
			"written_bytes": written,
			"append":        args.Append,
		}),
	}
}

func (s *Server) handleStatFileLimited(sess *session, req ipc.Request) ipc.Response {
	if !s.cfg.FileBridgeEnabled {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrFileOutOfScope,
			Message:   "file bridge disabled",
			Data:      s.sessionData(sess),
		}
	}

	args, err := parseStatFileArgs(req.Args)
	if err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	resolvedPath, err := s.resolveAllowedReadPath(args.Path)
	if err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrFileOutOfScope,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	info, err := os.Stat(resolvedPath)
	if err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrFileReadFailed,
			Message:   fmt.Sprintf("stat failed: %v", err),
			Data:      s.sessionData(sess),
		}
	}

	sha := ""
	if args.ComputeHash && !info.IsDir() {
		// Reject oversized files up front to avoid loading them fully into memory.
		if info.Size() > 8*1024*1024 {
			return ipc.Response{
				RequestID: req.RequestID,
				OK:        false,
				Code:      ipc.CodeErrInvalidRequest,
				Message:   "file too large to hash (>8MiB)",
				Data:      s.sessionData(sess),
			}
		}
		raw, readErr := os.ReadFile(resolvedPath)
		if readErr != nil {
			return ipc.Response{
				RequestID: req.RequestID,
				OK:        false,
				Code:      ipc.CodeErrFileReadFailed,
				Message:   fmt.Sprintf("hash read failed: %v", readErr),
				Data:      s.sessionData(sess),
			}
		}
		sum := sha256.Sum256(raw)
		sha = fmt.Sprintf("%x", sum[:])
	}

	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data: mergeData(s.sessionData(sess), map[string]interface{}{
			"path":     resolvedPath,
			"size":     info.Size(),
			"mtime_ms": info.ModTime().UnixMilli(),
			"is_dir":   info.IsDir(),
			"sha256":   sha,
		}),
	}
}

func parseWriteFileArgs(args map[string]interface{}) (writeFileArgs, error) {
	writeArgs := writeFileArgs{}
	if value, ok := args["path"].(string); ok {
		writeArgs.Path = strings.TrimSpace(value)
	}
	if value, ok := args["content"].(string); ok {
		writeArgs.Content = value
	}
	if value, ok := args["append"].(bool); ok {
		writeArgs.Append = value
	}
	if writeArgs.Path == "" {
		return writeArgs, fmt.Errorf("path is required")
	}
	if !filepath.IsAbs(writeArgs.Path) {
		return writeArgs, fmt.Errorf("path must be absolute")
	}
	return writeArgs, nil
}

func parseStatFileArgs(args map[string]interface{}) (statFileArgs, error) {
	statArgs := statFileArgs{ComputeHash: true}
	if value, ok := args["path"].(string); ok {
		statArgs.Path = strings.TrimSpace(value)
	}
	if value, ok := args["compute_hash"].(bool); ok {
		statArgs.ComputeHash = value
	}
	if statArgs.Path == "" {
		return statArgs, fmt.Errorf("path is required")
	}
	if !filepath.IsAbs(statArgs.Path) {
		return statArgs, fmt.Errorf("path must be absolute")
	}
	return statArgs, nil
}

func parseReadFileArgs(args map[string]interface{}) (readFileArgs, error) {
	readArgs := readFileArgs{
		Offset:   0,
		MaxBytes: 65536,
	}

	if value, ok := args["path"].(string); ok {
		readArgs.Path = strings.TrimSpace(value)
	}
	if value, ok := args["offset"].(float64); ok {
		readArgs.Offset = int64(value)
	}
	if value, ok := args["max_bytes"].(float64); ok {
		readArgs.MaxBytes = int64(value)
	}

	if readArgs.Path == "" {
		return readArgs, fmt.Errorf("path is required")
	}
	if !filepath.IsAbs(readArgs.Path) {
		return readArgs, fmt.Errorf("path must be absolute")
	}
	if readArgs.Offset < 0 {
		return readArgs, fmt.Errorf("offset must be >= 0")
	}
	if readArgs.MaxBytes < 1 || readArgs.MaxBytes > 1048576 {
		return readArgs, fmt.Errorf("max_bytes must be between 1 and 1048576")
	}

	return readArgs, nil
}

func (s *Server) resolveAllowedReadPath(requestPath string) (string, error) {
	return s.resolveAllowedPath(requestPath, s.allowedReadRoots())
}

// resolveAllowedWritePath resolves a path against the write root set. This is
// intentionally separate from the read roots: ReadonlyWhitelist, /sdcard/Download,
// the magisk module tree, the audit directory, and any other baseDir subtree
// must stay read-only over IPC even when the file bridge is enabled for writes.
// Only the captures and xposed subdirectories are writable, because the runtime
// itself produces files there (screenshots, xposed state). The audit directory
// is also excluded explicitly as defense-in-depth in case AuditDir is ever
// configured outside baseDir.
func (s *Server) resolveAllowedWritePath(requestPath string) (string, error) {
	resolved, err := s.resolveAllowedPath(requestPath, s.allowedWriteRoots())
	if err != nil {
		return "", err
	}
	// Defense-in-depth: even if a future change widens allowedWriteRoots, the
	// audit directory and ReadonlyWhitelist entries must remain tamper-proof.
	if auditDir := strings.TrimSpace(s.cfg.AuditDir); auditDir != "" {
		if resolvedAudit, rootErr := resolveAllowedRoot(auditDir); rootErr == nil {
			if isPathWithinRoot(resolved, resolvedAudit) {
				return "", fmt.Errorf("audit directory is read-only")
			}
		}
	}
	for _, ro := range s.cfg.ReadonlyWhitelist {
		ro = strings.TrimSpace(ro)
		if ro == "" {
			continue
		}
		resolvedRO, rootErr := resolveAllowedRoot(ro)
		if rootErr != nil {
			continue
		}
		if isPathWithinRoot(resolved, resolvedRO) {
			return "", fmt.Errorf("read-only whitelist path is not writable")
		}
	}
	return resolved, nil
}

func (s *Server) resolveAllowedPath(requestPath string, allowedRoots []string) (string, error) {
	cleaned := filepath.Clean(requestPath)
	if !filepath.IsAbs(cleaned) {
		return "", fmt.Errorf("relative paths are not allowed")
	}

	resolvedCandidate, err := filepath.Abs(cleaned)
	if err != nil {
		return "", fmt.Errorf("resolve path failed: %w", err)
	}
	resolvedCandidate, err = resolveNonSymlinkPath(resolvedCandidate)
	if err != nil {
		return "", err
	}

	for _, root := range allowedRoots {
		resolvedRoot, rootErr := resolveAllowedRoot(root)
		if rootErr != nil {
			continue
		}
		if isPathWithinRoot(resolvedCandidate, resolvedRoot) {
			return resolvedCandidate, nil
		}
	}

	return "", fmt.Errorf("path not in allowed roots")
}

func (s *Server) allowedReadRoots() []string {
	baseDir := filepath.Dir(s.cfg.AuditDir)
	roots := []string{
		baseDir,
		filepath.Join(baseDir, "captures"),
		filepath.Join(baseDir, "xposed"),
		s.cfg.AuditDir,
		"/data/adb/modules/clawruntime",
		"/data/adb/modules/clawruntime/webroot",
	}
	roots = append(roots, s.cfg.ReadonlyWhitelist...)

	if runtime.GOOS == "android" || runtime.GOOS == "linux" {
		roots = append(roots, "/sdcard/Download")
	}

	return uniqueNonEmptyPaths(roots)
}

// allowedWriteRoots returns the restrictive set of roots that write_file_limited
// may write to. Only the captures and xposed subdirectories of baseDir are
// writable: the runtime itself produces files there (screenshots via
// capture_screen, xposed state via report_xposed_*). The broad baseDir is
// intentionally omitted so that ReadonlyWhitelist entries, the audit directory,
// and any other baseDir subtree stay read-only over IPC. ReadonlyWhitelist and
// AuditDir are also excluded explicitly in resolveAllowedWritePath as
// defense-in-depth in case this list is widened later.
func (s *Server) allowedWriteRoots() []string {
	baseDir := filepath.Dir(s.cfg.AuditDir)
	roots := []string{
		filepath.Join(baseDir, "captures"),
		filepath.Join(baseDir, "xposed"),
	}
	return uniqueNonEmptyPaths(roots)
}

func resolveAllowedRoot(root string) (string, error) {
	resolvedRoot, err := filepath.Abs(filepath.Clean(root))
	if err != nil {
		return "", err
	}
	if evaluated, evalErr := filepath.EvalSymlinks(resolvedRoot); evalErr == nil {
		return evaluated, nil
	}
	return resolvedRoot, nil
}

func resolveNonSymlinkPath(path string) (string, error) {
	info, err := os.Lstat(path)
	switch {
	case err == nil && info.Mode()&os.ModeSymlink != 0:
		return "", fmt.Errorf("symlink targets are not allowed")
	case err == nil:
		resolvedPath, evalErr := filepath.EvalSymlinks(path)
		if evalErr != nil {
			return "", fmt.Errorf("resolve path symlink failed: %w", evalErr)
		}
		return resolvedPath, nil
	case os.IsNotExist(err):
		parent := filepath.Dir(path)
		resolvedParent, parentErr := filepath.EvalSymlinks(parent)
		if parentErr != nil {
			return "", fmt.Errorf("resolve parent path failed: %w", parentErr)
		}
		return filepath.Join(resolvedParent, filepath.Base(path)), nil
	default:
		return "", fmt.Errorf("inspect path failed: %w", err)
	}
}

func uniqueNonEmptyPaths(paths []string) []string {
	seen := make(map[string]struct{}, len(paths))
	result := make([]string, 0, len(paths))
	for _, path := range paths {
		trimmed := strings.TrimSpace(path)
		if trimmed == "" {
			continue
		}
		if _, exists := seen[trimmed]; exists {
			continue
		}
		seen[trimmed] = struct{}{}
		result = append(result, trimmed)
	}
	return result
}

func isPathWithinRoot(candidate string, root string) bool {
	relative, err := filepath.Rel(root, candidate)
	if err != nil {
		return false
	}
	if relative == "." {
		return true
	}
	if strings.HasPrefix(relative, "..") {
		return false
	}
	return !filepath.IsAbs(relative)
}
