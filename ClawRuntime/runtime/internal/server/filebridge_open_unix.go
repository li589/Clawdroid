//go:build unix

package server

import (
	"fmt"
	"os"
	"path/filepath"
	"syscall"
)

func openNoFollow(path string, flags int, perm os.FileMode) (*os.File, error) {
	fd, err := syscall.Open(path, flags|syscall.O_NOFOLLOW, int(perm.Perm()))
	if err != nil {
		return nil, err
	}
	return os.NewFile(uintptr(fd), path), nil
}

func openDirectoryNoFollow(path string) (*os.File, error) {
	fd, err := syscall.Open(path, syscall.O_RDONLY|syscall.O_DIRECTORY|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return nil, err
	}
	return os.NewFile(uintptr(fd), path), nil
}

func verifyOpenedPathWithinRoot(file *os.File, root string) bool {
	fdPath := fmt.Sprintf("/proc/self/fd/%d", file.Fd())
	openedPath, err := os.Readlink(fdPath)
	if err != nil {
		return false
	}
	resolvedRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		return false
	}
	resolvedOpened, err := filepath.EvalSymlinks(openedPath)
	if err != nil {
		return false
	}
	return isPathWithinRoot(resolvedOpened, resolvedRoot)
}
