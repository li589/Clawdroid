package server

import (
	"fmt"
	"os/exec"
	"runtime"
	"strconv"

	"clawdroid/runtime/internal/ipc"
)

type injectTapArgs struct {
	X         int `json:"x"`
	Y         int `json:"y"`
	DisplayID int `json:"display_id"`
}

func (s *Server) handleInjectTap(sess *session, req ipc.Request) ipc.Response {
	if !s.cfg.InputInjectEnabled {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInputInjectFailed,
			Message:   "input inject disabled",
			Data:      s.sessionData(sess),
		}
	}

	args, err := parseInjectTapArgs(req.Args)
	if err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	if err := executeTap(args); err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInputInjectFailed,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	data := mergeData(s.sessionData(sess), map[string]interface{}{
		"accepted":   true,
		"display_id": args.DisplayID,
		"x":          args.X,
		"y":          args.Y,
	})

	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data:      data,
	}
}

func parseInjectTapArgs(args map[string]interface{}) (injectTapArgs, error) {
	tapArgs := injectTapArgs{DisplayID: 0}

	x, xOK := args["x"].(float64)
	y, yOK := args["y"].(float64)
	if !xOK || !yOK {
		return tapArgs, fmt.Errorf("x and y are required")
	}
	tapArgs.X = int(x)
	tapArgs.Y = int(y)
	if value, ok := args["display_id"].(float64); ok {
		tapArgs.DisplayID = int(value)
	}

	if tapArgs.X < 0 || tapArgs.Y < 0 {
		return tapArgs, fmt.Errorf("x and y must be >= 0")
	}
	if tapArgs.DisplayID < 0 {
		return tapArgs, fmt.Errorf("display_id must be >= 0")
	}

	return tapArgs, nil
}

func executeTap(args injectTapArgs) error {
	if runtime.GOOS != "android" && runtime.GOOS != "linux" {
		return nil
	}

	commandArgs := []string{"tap", strconv.Itoa(args.X), strconv.Itoa(args.Y)}
	if args.DisplayID > 0 {
		commandArgs = []string{"-d", strconv.Itoa(args.DisplayID), "tap", strconv.Itoa(args.X), strconv.Itoa(args.Y)}
	}

	command := exec.Command("input", commandArgs...)
	if output, err := command.CombinedOutput(); err != nil {
		return fmt.Errorf("run input tap failed: %w, output=%s", err, string(output))
	}

	return nil
}
