//go:build !unix && !windows

package server

import (
	"os"
	"path/filepath"
)

func openNoFollow(path string, flags int, perm os.FileMode) (*os.File, error) {
	return os.OpenFile(path, flags, perm)
}

func openDirectoryNoFollow(path string) (*os.File, error) {
	return os.Open(path)
}

func verifyOpenedPathWithinRoot(file *os.File, root string) bool {
	resolvedRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		return false
	}
	resolvedOpened, err := filepath.EvalSymlinks(file.Name())
	if err != nil {
		return false
	}
	return isPathWithinRoot(resolvedOpened, resolvedRoot)
}
