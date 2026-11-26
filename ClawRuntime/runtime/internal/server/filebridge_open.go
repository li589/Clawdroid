package server

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"clawdroid/runtime/internal/paths"
)

func (s *Server) resolveAllowedPathForOpen(requestPath string, roots []string, read bool) (string, string, error) {
	cleaned := filepath.Clean(requestPath)
	if !filepath.IsAbs(cleaned) {
		return "", "", fmt.Errorf("relative paths are not allowed")
	}
	resolvedCandidate, err := resolveNonSymlinkPath(cleaned)
	if err != nil {
		return "", "", err
	}
	for _, root := range roots {
		resolvedRoot, rootErr := resolveAllowedRoot(root)
		if rootErr != nil {
			continue
		}
		if isPathWithinRoot(resolvedCandidate, resolvedRoot) {
			if read && s.isSensitiveReadPath(resolvedCandidate) {
				return "", "", fmt.Errorf("path is protected runtime data")
			}
			return resolvedCandidate, resolvedRoot, nil
		}
	}
	return "", "", fmt.Errorf("path not in allowed roots")
}

func (s *Server) isSensitiveReadPath(candidate string) bool {
	if auditDir := strings.TrimSpace(s.cfg.AuditDir); auditDir != "" {
		if resolvedAudit, err := resolveAllowedRoot(auditDir); err == nil && isPathWithinRoot(candidate, resolvedAudit) {
			return true
		}
	}
	moduleConfig := filepath.Join(paths.MagiskModulePath, "config")
	if resolvedConfig, err := resolveAllowedRoot(moduleConfig); err == nil && isPathWithinRoot(candidate, resolvedConfig) {
		return true
	}
	return false
}

func (s *Server) isSensitiveWritePath(candidate string) bool {
	if auditDir := strings.TrimSpace(s.cfg.AuditDir); auditDir != "" {
		if resolvedAudit, err := resolveAllowedRoot(auditDir); err == nil && isPathWithinRoot(candidate, resolvedAudit) {
			return true
		}
	}
	for _, readonly := range s.cfg.ReadonlyWhitelist {
		if strings.TrimSpace(readonly) == "" {
			continue
		}
		if resolvedReadonly, err := resolveAllowedRoot(readonly); err == nil && isPathWithinRoot(candidate, resolvedReadonly) {
			return true
		}
	}
	return false
}

func (s *Server) openAllowedReadFile(requestPath string) (*os.File, string, error) {
	resolved, root, err := s.resolveAllowedPathForOpen(requestPath, s.allowedReadRoots(), true)
	if err != nil {
		return nil, "", err
	}
	file, err := openVerifiedFile(resolved, root, os.O_RDONLY, 0)
	if err != nil {
		return nil, "", err
	}
	return file, resolved, nil
}

func (s *Server) openAllowedWriteFile(requestPath string, appendMode bool) (*os.File, string, error) {
	resolved, root, err := s.resolveAllowedPathForOpen(requestPath, s.allowedWriteRoots(), false)
	if err != nil {
		return nil, "", err
	}
	if s.isSensitiveWritePath(resolved) {
		return nil, "", fmt.Errorf("path is read-only runtime data")
	}
	flags := os.O_WRONLY | os.O_CREATE
	if appendMode {
		flags |= os.O_APPEND
	}
	file, err := openVerifiedFile(resolved, root, flags, 0o600)
	if err != nil {
		return nil, "", err
	}
	if !appendMode {
		if err := file.Truncate(0); err != nil {
			_ = file.Close()
			return nil, "", fmt.Errorf("truncate file failed: %w", err)
		}
	}
	return file, resolved, nil
}

func (s *Server) openAllowedDir(requestPath string) (*os.File, string, error) {
	resolved, root, err := s.resolveAllowedPathForOpen(requestPath, s.allowedReadRoots(), true)
	if err != nil {
		return nil, "", err
	}
	file, err := openDirectoryNoFollow(resolved)
	if err != nil {
		return nil, "", err
	}
	if !verifyOpenedPathWithinRoot(file, root) {
		_ = file.Close()
		return nil, "", fmt.Errorf("opened directory escaped allowed root")
	}
	return file, resolved, nil
}

func openVerifiedFile(path, root string, flags int, perm os.FileMode) (*os.File, error) {
	file, err := openNoFollow(path, flags, perm)
	if err != nil {
		return nil, err
	}
	if !verifyOpenedPathWithinRoot(file, root) {
		_ = file.Close()
		return nil, fmt.Errorf("opened file escaped allowed root")
	}
	return file, nil
}
