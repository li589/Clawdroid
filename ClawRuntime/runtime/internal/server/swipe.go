package server

import (
	"fmt"
	"os/exec"
	"runtime"
	"strconv"

	"clawdroid/runtime/internal/ipc"
)

type injectSwipeArgs struct {
	X1         int `json:"x1"`
	Y1         int `json:"y1"`
	X2         int `json:"x2"`
	Y2         int `json:"y2"`
	DurationMS int `json:"duration_ms"`
	DisplayID  int `json:"display_id"`
}

func (s *Server) handleInjectSwipe(sess *session, req ipc.Request) ipc.Response {
	if !s.cfg.InputInjectEnabled {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInputInjectFailed,
			Message:   "input inject disabled",
			Data:      s.sessionData(sess),
		}
	}

	args, err := parseInjectSwipeArgs(req.Args)
	if err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	if err := executeSwipe(args); err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInputInjectFailed,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	data := mergeData(s.sessionData(sess), map[string]interface{}{
		"accepted":    true,
		"display_id":  args.DisplayID,
		"x1":          args.X1,
		"y1":          args.Y1,
		"x2":          args.X2,
		"y2":          args.Y2,
		"duration_ms": args.DurationMS,
	})

	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data:      data,
	}
}

func parseInjectSwipeArgs(args map[string]interface{}) (injectSwipeArgs, error) {
	swipeArgs := injectSwipeArgs{
		DisplayID:  0,
		DurationMS: 350,
	}

	required := map[string]*int{
		"x1": &swipeArgs.X1,
		"y1": &swipeArgs.Y1,
		"x2": &swipeArgs.X2,
		"y2": &swipeArgs.Y2,
	}
	for key, target := range required {
		value, ok := args[key].(float64)
		if !ok {
			return swipeArgs, fmt.Errorf("%s is required", key)
		}
		*target = int(value)
	}

	if value, ok := args["duration_ms"].(float64); ok {
		swipeArgs.DurationMS = int(value)
	}
	if value, ok := args["display_id"].(float64); ok {
		swipeArgs.DisplayID = int(value)
	}

	if swipeArgs.X1 < 0 || swipeArgs.Y1 < 0 || swipeArgs.X2 < 0 || swipeArgs.Y2 < 0 {
		return swipeArgs, fmt.Errorf("swipe coordinates must be >= 0")
	}
	if swipeArgs.DisplayID < 0 {
		return swipeArgs, fmt.Errorf("display_id must be >= 0")
	}
	if swipeArgs.DurationMS < 50 || swipeArgs.DurationMS > 10000 {
		return swipeArgs, fmt.Errorf("duration_ms must be between 50 and 10000")
	}

	return swipeArgs, nil
}

func executeSwipe(args injectSwipeArgs) error {
	if runtime.GOOS != "android" && runtime.GOOS != "linux" {
		return nil
	}

	commandArgs := []string{
		"swipe",
		strconv.Itoa(args.X1),
		strconv.Itoa(args.Y1),
		strconv.Itoa(args.X2),
		strconv.Itoa(args.Y2),
		strconv.Itoa(args.DurationMS),
	}
	if args.DisplayID > 0 {
		commandArgs = []string{
			"-d", strconv.Itoa(args.DisplayID),
			"swipe",
			strconv.Itoa(args.X1),
			strconv.Itoa(args.Y1),
			strconv.Itoa(args.X2),
			strconv.Itoa(args.Y2),
			strconv.Itoa(args.DurationMS),
		}
	}

	command := exec.Command("input", commandArgs...)
	if output, err := command.CombinedOutput(); err != nil {
		return fmt.Errorf("run input swipe failed: %w, output=%s", err, string(output))
	}

	return nil
}
