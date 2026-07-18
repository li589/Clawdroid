package server

import (
	"fmt"
	"os/exec"
	"runtime"
	"sort"
	"strconv"
	"strings"

	"clawdroid/runtime/internal/ipc"
)

// Narrow keyevent allowlist — navigation / editing only.
var allowedKeyevents = map[string]int{
	"BACK":       4,
	"HOME":       3,
	"APP_SWITCH": 187,
	"ENTER":      66,
	"DEL":        67,
	"TAB":        61,
	"SPACE":      62,
	"DPAD_UP":    19,
	"DPAD_DOWN":  20,
	"DPAD_LEFT":  21,
	"DPAD_RIGHT": 22,
	"KEYCODE_BACK":       4,
	"KEYCODE_HOME":       3,
	"KEYCODE_APP_SWITCH": 187,
	"KEYCODE_ENTER":      66,
	"KEYCODE_DEL":        67,
	"KEYCODE_TAB":        61,
	"KEYCODE_SPACE":      62,
	"KEYCODE_DPAD_UP":    19,
	"KEYCODE_DPAD_DOWN":  20,
	"KEYCODE_DPAD_LEFT":  21,
	"KEYCODE_DPAD_RIGHT": 22,
}

var allowedKeyeventCodes = map[int]string{
	3:   "HOME",
	4:   "BACK",
	19:  "DPAD_UP",
	20:  "DPAD_DOWN",
	21:  "DPAD_LEFT",
	22:  "DPAD_RIGHT",
	61:  "TAB",
	62:  "SPACE",
	66:  "ENTER",
	67:  "DEL",
	187: "APP_SWITCH",
}

type injectKeyeventArgs struct {
	KeyCode   int    `json:"keycode"`
	KeyName   string `json:"key"`
	DisplayID int    `json:"display_id"`
}

func (s *Server) handleInjectKeyevent(sess *session, req ipc.Request) ipc.Response {
	if !s.cfg.InputInjectEnabled {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInputInjectFailed,
			Message:   "input inject disabled",
			Data:      s.sessionData(sess),
		}
	}

	args, err := parseInjectKeyeventArgs(req.Args)
	if err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	if err := executeKeyevent(args); err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInputInjectFailed,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data: mergeData(s.sessionData(sess), map[string]interface{}{
			"accepted":   true,
			"display_id": args.DisplayID,
			"keycode":    args.KeyCode,
			"key":        args.KeyName,
		}),
	}
}

func parseInjectKeyeventArgs(args map[string]interface{}) (injectKeyeventArgs, error) {
	result := injectKeyeventArgs{DisplayID: 0}

	if value, ok := args["display_id"].(float64); ok {
		result.DisplayID = int(value)
	}
	if result.DisplayID < 0 {
		return result, fmt.Errorf("display_id must be >= 0")
	}

	if raw, ok := args["key"].(string); ok && strings.TrimSpace(raw) != "" {
		name := strings.ToUpper(strings.TrimSpace(raw))
		code, ok := allowedKeyevents[name]
		if !ok {
			return result, fmt.Errorf("key not allowed: %s", raw)
		}
		result.KeyCode = code
		result.KeyName = allowedKeyeventCodes[code]
		return result, nil
	}

	if raw, ok := args["keycode"].(float64); ok {
		code := int(raw)
		name, ok := allowedKeyeventCodes[code]
		if !ok {
			return result, fmt.Errorf("keycode not allowed: %d", code)
		}
		result.KeyCode = code
		result.KeyName = name
		return result, nil
	}

	return result, fmt.Errorf("key or keycode is required")
}

func executeKeyevent(args injectKeyeventArgs) error {
	if runtime.GOOS != "android" && runtime.GOOS != "linux" {
		return nil
	}

	commandArgs := []string{"keyevent", strconv.Itoa(args.KeyCode)}
	if args.DisplayID > 0 {
		commandArgs = []string{"-d", strconv.Itoa(args.DisplayID), "keyevent", strconv.Itoa(args.KeyCode)}
	}
	command := exec.Command("input", commandArgs...)
	if output, err := command.CombinedOutput(); err != nil {
		return fmt.Errorf("run input keyevent failed: %w, output=%s", err, string(output))
	}
	return nil
}

func allowedKeyeventList() []string {
	names := make([]string, 0, len(allowedKeyeventCodes))
	for _, name := range allowedKeyeventCodes {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}
