package server

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"image"
	"image/jpeg"
	"image/png"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"time"

	"clawdroid/runtime/internal/ipc"
)

type captureScreenArgs struct {
	DisplayID int    `json:"display_id"`
	Format    string `json:"format"`
	Quality   int    `json:"quality"`
	MaxWidth  int    `json:"max_width"`
	MaxHeight int    `json:"max_height"`
}

type captureScreenResult struct {
	DisplayID  int
	Format     string
	Width      int
	Height     int
	ImagePath  string
	FileSize   int64
	SHA256     string
	CapturedAt int64
	Transport  string
}

func (s *Server) handleCaptureScreen(sess *session, req ipc.Request) ipc.Response {
	if !s.cfg.ScreenshotEnabled {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrScreenCaptureUnavailable,
			Message:   ipc.ErrorMessage(ipc.CodeErrScreenCaptureUnavailable),
			Data:      s.sessionData(sess),
		}
	}

	args, err := parseCaptureArgs(req.Args)
	if err != nil {
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrInvalidRequest,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	result, err := s.captureScreen(args)
	if err != nil {
		s.logger.Error(fmt.Sprintf("capture_screen failed: %v", err))
		return ipc.Response{
			RequestID: req.RequestID,
			OK:        false,
			Code:      ipc.CodeErrScreenCaptureUnavailable,
			Message:   err.Error(),
			Data:      s.sessionData(sess),
		}
	}

	data := s.sessionData(sess)
	data["display_id"] = result.DisplayID
	data["format"] = result.Format
	data["width"] = result.Width
	data["height"] = result.Height
	data["image_path"] = result.ImagePath
	data["file_size"] = result.FileSize
	data["sha256"] = result.SHA256
	data["captured_at"] = result.CapturedAt
	data["transport"] = result.Transport

	return ipc.Response{
		RequestID: req.RequestID,
		OK:        true,
		Code:      ipc.CodeOK,
		Message:   ipc.ErrorMessage(ipc.CodeOK),
		Data:      data,
	}
}

func parseCaptureArgs(args map[string]interface{}) (captureScreenArgs, error) {
	captureArgs := captureScreenArgs{
		DisplayID: 0,
		Format:    "png",
		Quality:   90,
		MaxWidth:  1440,
		MaxHeight: 3200,
	}

	if value, ok := args["display_id"].(float64); ok {
		captureArgs.DisplayID = int(value)
	}
	if value, ok := args["format"].(string); ok && value != "" {
		captureArgs.Format = value
	}
	if value, ok := args["quality"].(float64); ok {
		captureArgs.Quality = int(value)
	}
	if value, ok := args["max_width"].(float64); ok {
		captureArgs.MaxWidth = int(value)
	}
	if value, ok := args["max_height"].(float64); ok {
		captureArgs.MaxHeight = int(value)
	}

	switch captureArgs.Format {
	case "png", "jpeg":
	default:
		return captureArgs, fmt.Errorf("unsupported capture format: %s", captureArgs.Format)
	}
	if captureArgs.DisplayID < 0 {
		return captureArgs, fmt.Errorf("display_id must be >= 0")
	}
	if captureArgs.Quality < 1 || captureArgs.Quality > 100 {
		return captureArgs, fmt.Errorf("quality must be between 1 and 100")
	}
	if captureArgs.MaxWidth < 1 || captureArgs.MaxWidth > 4096 {
		return captureArgs, fmt.Errorf("max_width must be between 1 and 4096")
	}
	if captureArgs.MaxHeight < 1 || captureArgs.MaxHeight > 4096 {
		return captureArgs, fmt.Errorf("max_height must be between 1 and 4096")
	}

	return captureArgs, nil
}

func (s *Server) captureScreen(args captureScreenArgs) (captureScreenResult, error) {
	captureDir := filepath.Join(filepath.Dir(s.cfg.AuditDir), "captures")
	if err := os.MkdirAll(captureDir, 0o700); err != nil {
		return captureScreenResult{}, fmt.Errorf("create capture dir: %w", err)
	}

	fileExt := ".png"
	if args.Format == "jpeg" {
		fileExt = ".jpg"
	}
	fileName := fmt.Sprintf("capture-%d-%d%s", args.DisplayID, time.Now().UnixMilli(), fileExt)
	outputPath := filepath.Join(captureDir, fileName)

	if runtime.GOOS == "android" || runtime.GOOS == "linux" {
		if err := captureViaSystem(args, outputPath); err != nil {
			return captureScreenResult{}, err
		}
	} else {
		if err := createPlaceholderCapture(outputPath, args); err != nil {
			return captureScreenResult{}, err
		}
	}

	width, height, err := ensureCaptureFormatAndSize(outputPath, args)
	if err != nil {
		return captureScreenResult{}, err
	}

	stat, err := os.Stat(outputPath)
	if err != nil {
		return captureScreenResult{}, fmt.Errorf("stat capture file: %w", err)
	}
	sum, err := fileSHA256(outputPath)
	if err != nil {
		return captureScreenResult{}, err
	}

	return captureScreenResult{
		DisplayID:  args.DisplayID,
		Format:     args.Format,
		Width:      width,
		Height:     height,
		ImagePath:  outputPath,
		FileSize:   stat.Size(),
		SHA256:     sum,
		CapturedAt: time.Now().Unix(),
		Transport:  "file_path",
	}, nil
}

func captureViaSystem(args captureScreenArgs, outputPath string) error {
	tempPNG := outputPath
	if args.Format == "jpeg" {
		tempPNG = outputPath + ".tmp.png"
	}

	cmdArgs := []string{"-p", tempPNG}
	if args.DisplayID > 0 {
		cmdArgs = []string{"-d", fmt.Sprintf("%d", args.DisplayID), "-p", tempPNG}
	}

	command := exec.Command("screencap", cmdArgs...)
	if output, err := command.CombinedOutput(); err != nil {
		return fmt.Errorf("run screencap: %w, output=%s", err, string(output))
	}

	if args.Format == "jpeg" {
		if err := convertPNGToJPEG(tempPNG, outputPath, args.Quality, args.MaxWidth, args.MaxHeight); err != nil {
			return err
		}
		_ = os.Remove(tempPNG)
	}

	return nil
}

func ensureCaptureFormatAndSize(path string, args captureScreenArgs) (int, int, error) {
	file, err := os.Open(path)
	if err != nil {
		return 0, 0, fmt.Errorf("open capture file: %w", err)
	}
	defer file.Close()

	imageData, formatName, err := image.Decode(file)
	if err != nil {
		return 0, 0, fmt.Errorf("decode capture image: %w", err)
	}

	resized := resizeImage(imageData, args.MaxWidth, args.MaxHeight)
	if resized.Bounds().Dx() != imageData.Bounds().Dx() || resized.Bounds().Dy() != imageData.Bounds().Dy() || formatName != args.Format {
		if err := writeImage(path, resized, args.Format, args.Quality); err != nil {
			return 0, 0, err
		}
	}

	bounds := resized.Bounds()
	return bounds.Dx(), bounds.Dy(), nil
}

func convertPNGToJPEG(srcPath, dstPath string, quality int, maxWidth, maxHeight int) error {
	file, err := os.Open(srcPath)
	if err != nil {
		return fmt.Errorf("open temporary png: %w", err)
	}
	defer file.Close()

	img, err := png.Decode(file)
	if err != nil {
		return fmt.Errorf("decode temporary png: %w", err)
	}

	resized := resizeImage(img, maxWidth, maxHeight)
	return writeImage(dstPath, resized, "jpeg", quality)
}

func resizeImage(src image.Image, maxWidth, maxHeight int) image.Image {
	bounds := src.Bounds()
	width := bounds.Dx()
	height := bounds.Dy()
	if width <= maxWidth && height <= maxHeight {
		return src
	}

	scaleX := float64(maxWidth) / float64(width)
	scaleY := float64(maxHeight) / float64(height)
	scale := scaleX
	if scaleY < scale {
		scale = scaleY
	}
	targetWidth := int(float64(width) * scale)
	targetHeight := int(float64(height) * scale)
	if targetWidth < 1 {
		targetWidth = 1
	}
	if targetHeight < 1 {
		targetHeight = 1
	}

	dst := image.NewRGBA(image.Rect(0, 0, targetWidth, targetHeight))
	for y := 0; y < targetHeight; y++ {
		srcY := bounds.Min.Y + (y * height / targetHeight)
		for x := 0; x < targetWidth; x++ {
			srcX := bounds.Min.X + (x * width / targetWidth)
			dst.Set(x, y, src.At(srcX, srcY))
		}
	}
	return dst
}

func writeImage(path string, img image.Image, format string, quality int) error {
	file, err := os.Create(path)
	if err != nil {
		return fmt.Errorf("create image file: %w", err)
	}
	defer file.Close()

	switch format {
	case "png":
		if err := png.Encode(file, img); err != nil {
			return fmt.Errorf("encode png: %w", err)
		}
	case "jpeg":
		if err := jpeg.Encode(file, img, &jpeg.Options{Quality: quality}); err != nil {
			return fmt.Errorf("encode jpeg: %w", err)
		}
	default:
		return fmt.Errorf("unsupported image write format: %s", format)
	}

	return nil
}

func fileSHA256(path string) (string, error) {
	bytes, err := os.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("read capture file: %w", err)
	}
	sum := sha256.Sum256(bytes)
	return hex.EncodeToString(sum[:]), nil
}

func createPlaceholderCapture(path string, args captureScreenArgs) error {
	width := args.MaxWidth
	height := args.MaxHeight
	if width > 720 {
		width = 720
	}
	if height > 1280 {
		height = 1280
	}

	img := image.NewRGBA(image.Rect(0, 0, width, height))
	return writeImage(path, img, args.Format, args.Quality)
}
