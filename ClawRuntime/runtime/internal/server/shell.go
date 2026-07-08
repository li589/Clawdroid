package server

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os/exec"
	"sort"
	"strings"
	"time"

	"clawdroid/runtime/internal/ipc"
)

const (
	defaultShellTimeoutMS = 3000
	maxShellTimeoutMS     = 10000
	minShellTimeoutMS     = 100
	maxShellOutputBytes   = 16384
)

type shellCommandTemplate struct {
	Name        string
	CommandArgs []string
}

var allowedShellCommands = map[string]shellCommandTemplate{
	"cmd overlay list": {
		Name:        "cmd overlay list",
		CommandArgs: []string{"cmd", "overlay", "list"},
	},
	"dumpsys activity top": {
		Name:        "dumpsys activity top",
		CommandArgs: []string{"dumpsys", "activity", "top"},
	},
	"dumpsys window windows": {
		Name:        "dumpsys window windows",
		CommandArgs: []string{"dumpsys", "window", "windows"},
	},
	"getenforce": {
		Name:        "getenforce",
		CommandArgs: []string{"getenforce"},
	},
	"getprop ro.build.version.release": {
		Name:        "getprop ro.build.version.release",
		CommandArgs: []string{"getprop", "ro.build.version.release"},
	},
	"getprop ro.build.version.sdk": {
		Name:        "getprop ro.build.version.sdk",
		CommandArgs: []string{"getprop", "ro.build.version.sdk"},
	},
	"getprop ro.hardware": {
		Name:        "getprop ro.hardware",
		CommandArgs: []string{"getprop", "ro.hardware"},
	},
	"getprop ro.product.manufacturer": {
		Name:        "getprop ro.product.manufacturer",
		CommandArgs: []string{"getprop", "ro.product.manufacturer"},
	},
	"getprop ro.product.model": {
		Name:        "getprop ro.product.model",
		CommandArgs: []string{"getprop", "ro.product.model"},
	},
	"id": {
		Name:        "id",
		CommandArgs: []string{"id"},
	},
	"settings get secure accessibility_enabled": {
		Name:        "settings get secure accessibility_enabled",
		CommandArgs: []string{"settings", "get", "secure", "accessibility_enabled"},
	},
	"settings get secure enabled_accessibility_services": {
		Name:        "settings get secure enabled_accessibility_services",
		CommandArgs: []string{"settings", "get", "secure", "enabled_accessibility_services"},
	},
	"wm density": {
		Name:        "wm density",
		CommandArgs: []string{"wm", "density"},
	},
	"wm size": {
		Name:        "wm size",
		CommandArgs: []string{"wm", "size"},
	},
}

var sortedAllowedShellCommands []string

func init() {
	sortedAllowedShellCommands = allowedShellCommandList()
}

type execShellArgs struct {
	Command   string `json:"command"`
	TimeoutMS int    `json:"timeout_ms"`
}

func (s *Server) handleExecShellLimited(sess *session, req ipc.Request) ipc.Response {
	if !s.cfg.ShellEnabled {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrShellDenied,
			Message:   "shell capability disabled",
			Data:      s.sessionData(sess),
		}
	}

	rawCommand := ""
	if v, ok := req.Args["command"].(string); ok {
		rawCommand = v
	}

	args, err := parseExecShellArgs(req.Args, s.cfg.RequestTimeoutMS)
	if err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	template, ok := allowedShellCommands[args.Command]
	if !ok {
		// Audit: log rejected shell command attempts for security traceability.
		s.logger.Info(fmt.Sprintf("exec_shell_limited rejected: session=%s package=%s command=%q not in whitelist",
			sess.id, sess.packageName, rawCommand))
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrShellDenied,
			Message:   fmt.Sprintf("command not allowed: %s", rawCommand),
			Data: mergeData(s.sessionData(sess), map[string]interface{}{
				"command":          rawCommand,
				"allowed_commands": sortedAllowedShellCommands,
			}),
		}
	}

	result, execErr := executeLimitedShell(template.CommandArgs, args.TimeoutMS)
	if execErr != nil {
		code := ipc.CodeErrShellExecFailed
		if result.TimedOut {
			code = ipc.CodeErrTimeout
		}
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      code,
			Message:   execErr.Error(),
			Data: mergeData(s.sessionData(sess), map[string]interface{}{
				"command":            rawCommand,
				"template_name":      template.Name,
				"allowed_commands":   sortedAllowedShellCommands,
				"timeout_ms":         args.TimeoutMS,
				"duration_ms":        result.DurationMS,
				"exit_code":          result.ExitCode,
				"stdout":             result.Stdout,
				"stderr":             result.Stderr,
				"stdout_truncated":   result.StdoutTruncated,
				"stderr_truncated":   result.StderrTruncated,
				"timed_out":          result.TimedOut,
			}),
		}
	}

	s.logger.Info(fmt.Sprintf("exec_shell_limited success: session=%s command=%q exit=%d", sess.id, args.Command, result.ExitCode))

	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data: mergeData(s.sessionData(sess), map[string]interface{}{
			"command":          rawCommand,
			"template_name":    template.Name,
			"allowed_commands": sortedAllowedShellCommands,
			"timeout_ms":       args.TimeoutMS,
			"duration_ms":      result.DurationMS,
			"exit_code":        result.ExitCode,
			"stdout":           result.Stdout,
			"stderr":           result.Stderr,
			"stdout_truncated": result.StdoutTruncated,
			"stderr_truncated": result.StderrTruncated,
			"timed_out":        result.TimedOut,
		}),
	}
}

func parseExecShellArgs(args map[string]interface{}, fallbackTimeoutMS int) (execShellArgs, error) {
	timeoutMS := fallbackTimeoutMS
	if timeoutMS < minShellTimeoutMS || timeoutMS > maxShellTimeoutMS {
		timeoutMS = defaultShellTimeoutMS
	}

	shellArgs := execShellArgs{TimeoutMS: timeoutMS}
	if value, ok := args["command"].(string); ok {
		shellArgs.Command = normalizeShellCommand(value)
	}
	if value, ok := args["timeout_ms"].(float64); ok {
		shellArgs.TimeoutMS = int(value)
	}

	if shellArgs.Command == "" {
		return shellArgs, fmt.Errorf("command is required")
	}
	if shellArgs.TimeoutMS < minShellTimeoutMS || shellArgs.TimeoutMS > maxShellTimeoutMS {
		return shellArgs, fmt.Errorf("timeout_ms must be between %d and %d", minShellTimeoutMS, maxShellTimeoutMS)
	}

	return shellArgs, nil
}

type shellExecResult struct {
	ExitCode         int
	Stdout           string
	Stderr           string
	StdoutTruncated  bool
	StderrTruncated  bool
	TimedOut         bool
	DurationMS       int64
}

func executeLimitedShell(commandArgs []string, timeoutMS int) (shellExecResult, error) {
	startedAt := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMS)*time.Millisecond)
	defer cancel()

	command := exec.CommandContext(ctx, commandArgs[0], commandArgs[1:]...)
	var stdout bytes.Buffer
	var stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	runErr := command.Run()
	result := shellExecResult{
		ExitCode:        0,
		DurationMS:      time.Since(startedAt).Milliseconds(),
	}
	result.Stdout, result.StdoutTruncated = truncateShellOutput(stdout.String())
	result.Stderr, result.StderrTruncated = truncateShellOutput(stderr.String())

	if ctx.Err() == context.DeadlineExceeded {
		result.TimedOut = true
		result.ExitCode = -1
		return result, fmt.Errorf("shell command timed out after %dms", timeoutMS)
	}

	if runErr == nil {
		return result, nil
	}

	var exitErr *exec.ExitError
	if errors.As(runErr, &exitErr) {
		result.ExitCode = exitErr.ExitCode()
		return result, fmt.Errorf("shell command exited with code %d", result.ExitCode)
	}

	result.ExitCode = -1
	return result, fmt.Errorf("shell command failed: %w", runErr)
}

func truncateShellOutput(content string) (string, bool) {
	trimmed := strings.TrimSpace(content)
	if len(trimmed) <= maxShellOutputBytes {
		return trimmed, false
	}
	return trimmed[:maxShellOutputBytes], true
}

func normalizeShellCommand(command string) string {
	return strings.Join(strings.Fields(strings.TrimSpace(command)), " ")
}

func allowedShellCommandList() []string {
	result := make([]string, 0, len(allowedShellCommands))
	for name := range allowedShellCommands {
		result = append(result, name)
	}
	sort.Strings(result)
	return result
}
