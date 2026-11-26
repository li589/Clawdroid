package server

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"image"
	"image/jpeg"
	"image/png"
	"io"
	"os"
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

const defaultCaptureTimeoutMS = 45_000

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
	return s.handleCaptureScreenContext(context.Background(), sess, req)
}

func (s *Server) handleCaptureScreenContext(ctx context.Context, sess *session, req ipc.Request) ipc.Response {
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

	result, err := s.captureScreen(ctx, args)
	if err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return ipc.Response{
				RequestID: req.RequestID,
				OK:        false,
				Code:      executionContextCode(err),
				Message:   executionContextMessage(err),
				Data:      s.sessionData(sess),
			}
		}
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

func (s *Server) captureScreen(ctx context.Context, args captureScreenArgs) (captureScreenResult, error) {
	ctx, cancel := context.WithTimeout(ctx, time.Duration(defaultCaptureTimeoutMS)*time.Millisecond)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return captureScreenResult{}, err
	}
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
		if err := captureViaSystem(ctx, args, outputPath); err != nil {
			return captureScreenResult{}, err
		}
	} else {
		if err := createPlaceholderCapture(ctx, outputPath, args); err != nil {
			return captureScreenResult{}, err
		}
	}
	if err := ctx.Err(); err != nil {
		return captureScreenResult{}, err
	}

	width, height, err := ensureCaptureFormatAndSize(ctx, outputPath, args)
	if err != nil {
		return captureScreenResult{}, err
	}
	if err := ctx.Err(); err != nil {
		return captureScreenResult{}, err
	}

	stat, err := os.Stat(outputPath)
	if err != nil {
		return captureScreenResult{}, fmt.Errorf("stat capture file: %w", err)
	}
	sum, err := fileSHA256(ctx, outputPath)
	if err != nil {
		return captureScreenResult{}, err
	}
	if err := ctx.Err(); err != nil {
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

func captureViaSystem(ctx context.Context, args captureScreenArgs, outputPath string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	tempPNG := outputPath
	if args.Format == "jpeg" {
		tempPNG = outputPath + ".tmp.png"
	}

	cmdArgs := []string{"-p", tempPNG}
	if args.DisplayID > 0 {
		cmdArgs = []string{"-d", fmt.Sprintf("%d", args.DisplayID), "-p", tempPNG}
	}

	output, err := runCommandContext(ctx, "screencap", cmdArgs...)
	if err != nil {
		if ctx.Err() != nil {
			return fmt.Errorf("run screencap: %w", ctx.Err())
		}
		return fmt.Errorf("run screencap: %w, output=%s", err, string(output))
	}
	if err := ctx.Err(); err != nil {
		return err
	}

	if args.Format == "jpeg" {
		if err := convertPNGToJPEG(ctx, tempPNG, outputPath, args.Quality, args.MaxWidth, args.MaxHeight); err != nil {
			return err
		}
		_ = os.Remove(tempPNG)
	}

	return nil
}

func ensureCaptureFormatAndSize(ctx context.Context, path string, args captureScreenArgs) (int, int, error) {
	if err := ctx.Err(); err != nil {
		return 0, 0, err
	}
	file, err := os.Open(path)
	if err != nil {
		return 0, 0, fmt.Errorf("open capture file: %w", err)
	}
	defer file.Close()

	imageData, formatName, err := decodeCaptureImage(ctx, file)
	if err != nil {
		return 0, 0, fmt.Errorf("decode capture image: %w", err)
	}
	if err := ctx.Err(); err != nil {
		return 0, 0, err
	}

	resized, err := resizeImage(ctx, imageData, args.MaxWidth, args.MaxHeight)
	if err != nil {
		return 0, 0, err
	}
	if err := ctx.Err(); err != nil {
		return 0, 0, err
	}
	if resized.Bounds().Dx() != imageData.Bounds().Dx() || resized.Bounds().Dy() != imageData.Bounds().Dy() || formatName != args.Format {
		if err := writeImage(ctx, path, resized, args.Format, args.Quality); err != nil {
			return 0, 0, err
		}
	}

	bounds := resized.Bounds()
	return bounds.Dx(), bounds.Dy(), nil
}

func decodeCaptureImage(ctx context.Context, reader io.Reader) (image.Image, string, error) {
	if err := ctx.Err(); err != nil {
		return nil, "", err
	}
	img, format, err := image.Decode(&contextReader{ctx: ctx, reader: reader})
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return nil, "", ctxErr
		}
		return nil, "", fmt.Errorf("decode image: %w", err)
	}
	if err := ctx.Err(); err != nil {
		return nil, "", err
	}
	return img, format, nil
}

func convertPNGToJPEG(ctx context.Context, srcPath, dstPath string, quality int, maxWidth, maxHeight int) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	file, err := os.Open(srcPath)
	if err != nil {
		return fmt.Errorf("open temporary png: %w", err)
	}
	defer file.Close()

	img, err := png.Decode(&contextReader{ctx: ctx, reader: file})
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return ctxErr
		}
		return fmt.Errorf("decode temporary png: %w", err)
	}
	if err := ctx.Err(); err != nil {
		return err
	}

	resized, err := resizeImage(ctx, img, maxWidth, maxHeight)
	if err != nil {
		return err
	}
	return writeImage(ctx, dstPath, resized, "jpeg", quality)
}

func resizeImage(ctx context.Context, src image.Image, maxWidth, maxHeight int) (image.Image, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	bounds := src.Bounds()
	width := bounds.Dx()
	height := bounds.Dy()
	if width <= maxWidth && height <= maxHeight {
		return src, nil
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
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		srcY := bounds.Min.Y + (y * height / targetHeight)
		for x := 0; x < targetWidth; x++ {
			if x%256 == 0 {
				if err := ctx.Err(); err != nil {
					return nil, err
				}
			}
			srcX := bounds.Min.X + (x * width / targetWidth)
			dst.Set(x, y, src.At(srcX, srcY))
		}
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return dst, nil
}

func writeImage(ctx context.Context, path string, img image.Image, format string, quality int) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	file, err := os.Create(path)
	if err != nil {
		return fmt.Errorf("create image file: %w", err)
	}
	defer file.Close()
	if err := ctx.Err(); err != nil {
		return err
	}

	writer := &contextWriter{ctx: ctx, writer: file}
	switch format {
	case "png":
		if err := png.Encode(writer, img); err != nil {
			if ctxErr := ctx.Err(); ctxErr != nil {
				return ctxErr
			}
			return fmt.Errorf("encode png: %w", err)
		}
	case "jpeg":
		if err := jpeg.Encode(writer, img, &jpeg.Options{Quality: quality}); err != nil {
			if ctxErr := ctx.Err(); ctxErr != nil {
				return ctxErr
			}
			return fmt.Errorf("encode jpeg: %w", err)
		}
	default:
		return fmt.Errorf("unsupported image write format: %s", format)
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	return file.Close()
}

func fileSHA256(ctx context.Context, path string) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}
	file, err := os.Open(path)
	if err != nil {
		return "", fmt.Errorf("read capture file: %w", err)
	}
	defer file.Close()
	return hashCaptureReader(ctx, file)
}

func hashCaptureReader(ctx context.Context, reader io.Reader) (string, error) {
	if err := ctx.Err(); err != nil {
		return "", err
	}
	hash := sha256.New()
	buffer := make([]byte, 64*1024)
	if _, err := io.CopyBuffer(hash, &contextReader{ctx: ctx, reader: reader}, buffer); err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			return "", ctxErr
		}
		return "", fmt.Errorf("hash capture file: %w", err)
	}
	if err := ctx.Err(); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

type contextReader struct {
	ctx    context.Context
	reader io.Reader
}

func (r *contextReader) Read(p []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	n, err := r.reader.Read(p)
	if ctxErr := r.ctx.Err(); ctxErr != nil {
		return n, ctxErr
	}
	return n, err
}

type contextWriter struct {
	ctx    context.Context
	writer io.Writer
}

func (w *contextWriter) Write(p []byte) (int, error) {
	if err := w.ctx.Err(); err != nil {
		return 0, err
	}
	n, err := w.writer.Write(p)
	if ctxErr := w.ctx.Err(); ctxErr != nil {
		return n, ctxErr
	}
	return n, err
}

func createPlaceholderCapture(ctx context.Context, path string, args captureScreenArgs) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	width := args.MaxWidth
	height := args.MaxHeight
	if width > 720 {
		width = 720
	}
	if height > 1280 {
		height = 1280
	}

	img := image.NewRGBA(image.Rect(0, 0, width, height))
	return writeImage(ctx, path, img, args.Format, args.Quality)
}
