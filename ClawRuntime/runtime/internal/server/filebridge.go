package server

import (
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

	allowedRoots := s.allowedReadRoots()

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
		s.cfg.AuditDir,
	}
	roots = append(roots, s.cfg.ReadonlyWhitelist...)

	if runtime.GOOS == "android" || runtime.GOOS == "linux" {
		roots = append(roots, "/sdcard/Download")
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
