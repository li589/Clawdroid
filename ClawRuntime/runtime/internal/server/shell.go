package server

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os/exec"
	"regexp"
	"sort"
	"strings"
	"time"

	"clawdroid/runtime/internal/ipc"
)

var androidPackageRegex = regexp.MustCompile(`^[A-Za-z]\w*(\.[A-Za-z]\w*)+$`)

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
	"ls /data/adb/modules/clawruntime": {
		Name:        "ls /data/adb/modules/clawruntime",
		CommandArgs: []string{"ls", "/data/adb/modules/clawruntime"},
	},
	"cat /data/adb/modules/clawruntime/webroot/status.json": {
		Name:        "cat /data/adb/modules/clawruntime/webroot/status.json",
		CommandArgs: []string{"cat", "/data/adb/modules/clawruntime/webroot/status.json"},
	},
	"cat /data/adb/modules/clawruntime/webroot/verify.json": {
		Name:        "cat /data/adb/modules/clawruntime/webroot/verify.json",
		CommandArgs: []string{"cat", "/data/adb/modules/clawruntime/webroot/verify.json"},
	},
	"pidof clawdroid-runtime": {
		Name:        "pidof clawdroid-runtime",
		CommandArgs: []string{"pidof", "clawdroid-runtime"},
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
	"dumpsys activity activities": {
		Name:        "dumpsys activity activities",
		CommandArgs: []string{"dumpsys", "activity", "activities"},
	},
	"dumpsys notification": {
		Name:        "dumpsys notification",
		CommandArgs: []string{"dumpsys", "notification"},
	},
	"cat /proc/uptime": {
		Name:        "cat /proc/uptime",
		CommandArgs: []string{"cat", "/proc/uptime"},
	},
	"df /data": {
		Name:        "df /data",
		CommandArgs: []string{"df", "/data"},
	},
	"pm list packages -3": {
		Name:        "pm list packages -3",
		CommandArgs: []string{"pm", "list", "packages", "-3"},
	},
	"pm path com.termux": {
		Name:        "pm path com.termux",
		CommandArgs: []string{"pm", "path", "com.termux"},
	},
	"pm list packages com.termux": {
		Name:        "pm list packages com.termux",
		CommandArgs: []string{"pm", "list", "packages", "com.termux"},
	},
	"cmd package path com.termux": {
		Name:        "cmd package path com.termux",
		CommandArgs: []string{"cmd", "package", "path", "com.termux"},
	},
	"ls /data/data/com.termux": {
		Name:        "ls /data/data/com.termux",
		CommandArgs: []string{"ls", "/data/data/com.termux"},
	},
	"ls /data/data/com.termux/files": {
		Name:        "ls /data/data/com.termux/files",
		CommandArgs: []string{"ls", "/data/data/com.termux/files"},
	},
	"ls /data/data/com.termux/files/usr/bin": {
		Name:        "ls /data/data/com.termux/files/usr/bin",
		CommandArgs: []string{"ls", "/data/data/com.termux/files/usr/bin"},
	},
	"reboot": {
		Name:        "reboot",
		CommandArgs: []string{"reboot"},
	},
	"svc power reboot": {
		Name:        "svc power reboot",
		CommandArgs: []string{"svc", "power", "reboot"},
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

	template, ok := resolveShellTemplate(args.Command)
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
				"command": rawCommand,
				"allowed_commands": append(append([]string(nil), sortedAllowedShellCommands...),
					"am force-stop <package>",
					"pm path <package>",
					"dumpsys package <package>",
					"pidof <name>",
				),
			}),
		}
	}

	// Reboot tears down the device before command.Run() can return; ack first then fire.
	if isFireAndForgetShell(template.Name) {
		job := s.shellJobs.begin(sess.id, args.Command, template.Name)
		s.logger.Info(fmt.Sprintf("exec_shell_limited fire-and-forget: session=%s command=%q job=%s", sess.id, args.Command, job.ID))
		go func() {
			select {
			case <-time.After(250 * time.Millisecond):
			case <-s.shutdownCtx.Done():
				s.shellJobs.finish(job.ID, "cancelled", 0, "shutdown before reboot", "", false, false, 0)
				return
			}
			cmd := exec.Command(template.CommandArgs[0], template.CommandArgs[1:]...)
			if startErr := cmd.Start(); startErr != nil {
				s.shellJobs.finish(job.ID, "failed", -1, fmt.Sprintf("reboot start failed: %v", startErr), "", false, false, 0)
				return
			}
			// Reap the child process to avoid zombie; reboot will kill it anyway.
			_ = cmd.Wait()
			s.shellJobs.finish(job.ID, "succeeded", 0, "reboot accepted", "", false, false, 0)
		}()
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        true,
			Code:      ipc.CodeOK,
			Message:   "reboot accepted; device will restart shortly",
			Data: mergeData(s.sessionData(sess), map[string]interface{}{
				"command":          rawCommand,
				"template_name":    template.Name,
				"allowed_commands": sortedAllowedShellCommands,
				"timeout_ms":       args.TimeoutMS,
				"duration_ms":      0,
				"exit_code":        0,
				"stdout":           "reboot accepted",
				"stderr":           "",
				"fire_and_forget":  true,
				"job_id":           job.ID,
			}),
		}
	}

	job := s.shellJobs.begin(sess.id, args.Command, template.Name)
	result, execErr := executeLimitedShell(template.CommandArgs, args.TimeoutMS)
	state := "succeeded"
	if execErr != nil {
		state = "failed"
		if result.TimedOut {
			state = "timeout"
		}
	}
	finished := s.shellJobs.finish(
		job.ID,
		state,
		result.ExitCode,
		result.Stdout,
		result.Stderr,
		result.StdoutTruncated || result.StderrTruncated,
		result.TimedOut,
		result.DurationMS,
	)
	jobID := job.ID
	if finished != nil {
		jobID = finished.ID
	}
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
				"job_id":             jobID,
			}),
		}
	}

	s.logger.Info(fmt.Sprintf("exec_shell_limited success: session=%s command=%q exit=%d job=%s", sess.id, args.Command, result.ExitCode, jobID))

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
			"job_id":           jobID,
		}),
	}
}

func (s *Server) handleShellJobList(sess *session, req ipc.Request) ipc.Response {
	if !s.cfg.ShellEnabled {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrShellDenied,
			Message:   "shell capability disabled",
			Data:      s.sessionData(sess),
		}
	}
	limit := 16
	if v, ok := req.Args["limit"].(float64); ok && v > 0 {
		limit = int(v)
	}
	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data: mergeData(s.sessionData(sess), map[string]interface{}{
			"jobs":  s.shellJobs.snapshotMaps(limit),
			"limit": limit,
		}),
	}
}

func (s *Server) handleShellJobGet(sess *session, req ipc.Request) ipc.Response {
	if !s.cfg.ShellEnabled {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrShellDenied,
			Message:   "shell capability disabled",
			Data:      s.sessionData(sess),
		}
	}
	jobID, _ := req.Args["job_id"].(string)
	if jobID == "" {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   "missing required field: job_id",
			Data:      s.sessionData(sess),
		}
	}
	job, ok := s.shellJobs.get(jobID)
	if !ok {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   "shell job not found",
			Data:      s.sessionData(sess),
		}
	}
	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data: mergeData(s.sessionData(sess), map[string]interface{}{
			"job": map[string]interface{}{
				"job_id":              job.ID,
				"session_id":          job.SessionID,
				"command":             job.Command,
				"template_name":       job.TemplateName,
				"state":               job.State,
				"exit_code":           job.ExitCode,
				"stdout_tail":         job.StdoutTail,
				"stderr_tail":         job.StderrTail,
				"truncated":           job.Truncated,
				"timed_out":           job.TimedOut,
				"duration_ms":         job.DurationMS,
				"started_at_epoch_ms": job.StartedAt,
				"ended_at_epoch_ms":   job.EndedAt,
			},
		}),
	}
}

func resolveShellTemplate(command string) (shellCommandTemplate, bool) {
	if template, ok := allowedShellCommands[command]; ok {
		return template, true
	}
	if template, ok := resolveParameterizedShell(command); ok {
		return template, true
	}
	return shellCommandTemplate{}, false
}

func resolveParameterizedShell(command string) (shellCommandTemplate, bool) {
	type prefixSpec struct {
		prefix string
		name   string
		args   func(rest string) ([]string, bool)
	}
	specs := []prefixSpec{
		{
			prefix: "am force-stop ",
			name:   "am force-stop",
			args: func(rest string) ([]string, bool) {
				pkg := strings.TrimSpace(rest)
				if !androidPackageRegex.MatchString(pkg) {
					return nil, false
				}
				return []string{"am", "force-stop", pkg}, true
			},
		},
		{
			prefix: "pm path ",
			name:   "pm path",
			args: func(rest string) ([]string, bool) {
				pkg := strings.TrimSpace(rest)
				if !androidPackageRegex.MatchString(pkg) {
					return nil, false
				}
				return []string{"pm", "path", pkg}, true
			},
		},
		{
			prefix: "cmd package path ",
			name:   "cmd package path",
			args: func(rest string) ([]string, bool) {
				pkg := strings.TrimSpace(rest)
				if !androidPackageRegex.MatchString(pkg) {
					return nil, false
				}
				return []string{"cmd", "package", "path", pkg}, true
			},
		},
		{
			prefix: "pm list packages ",
			name:   "pm list packages",
			args: func(rest string) ([]string, bool) {
				filter := strings.TrimSpace(rest)
				if filter == "" || strings.HasPrefix(filter, "-") {
					return nil, false
				}
				for _, r := range filter {
					ok := (r >= 'a' && r <= 'z') ||
						(r >= 'A' && r <= 'Z') ||
						(r >= '0' && r <= '9') ||
						r == '.' || r == '_'
					if !ok {
						return nil, false
					}
				}
				return []string{"pm", "list", "packages", filter}, true
			},
		},
		{
			prefix: "ls /data/data/",
			name:   "ls /data/data/<pkg>",
			args: func(rest string) ([]string, bool) {
				pkg := strings.TrimSpace(rest)
				if !androidPackageRegex.MatchString(pkg) || strings.Contains(pkg, "/") || strings.Contains(pkg, "..") {
					return nil, false
				}
				return []string{"ls", "/data/data/" + pkg}, true
			},
		},
		{
			prefix: "dumpsys package ",
			name:   "dumpsys package",
			args: func(rest string) ([]string, bool) {
				pkg := strings.TrimSpace(rest)
				if !androidPackageRegex.MatchString(pkg) {
					return nil, false
				}
				return []string{"dumpsys", "package", pkg}, true
			},
		},
		{
			prefix: "pidof ",
			name:   "pidof",
			args: func(rest string) ([]string, bool) {
				name := strings.TrimSpace(rest)
				// Reject empty, leading-dash (pidof flag injection like "-x"),
				// and any shell-relevant metacharacters. exec.Command does not
				// invoke a shell, so this is defense-in-depth + input hygiene
				// consistent with the androidPackageRegex checks above.
				if name == "" ||
					strings.HasPrefix(name, "-") ||
					strings.ContainsAny(name, " \t\n\r;&|<>()${}`\\") {
					return nil, false
				}
				return []string{"pidof", name}, true
			},
		},
	}
	for _, spec := range specs {
		if strings.HasPrefix(command, spec.prefix) {
			rest := strings.TrimPrefix(command, spec.prefix)
			args, ok := spec.args(rest)
			if !ok {
				return shellCommandTemplate{}, false
			}
			return shellCommandTemplate{Name: spec.name, CommandArgs: args}, true
		}
	}
	return shellCommandTemplate{}, false
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

func isFireAndForgetShell(templateName string) bool {
	switch templateName {
	case "reboot", "svc power reboot":
		return true
	default:
		return false
	}
}
