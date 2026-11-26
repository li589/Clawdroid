package server

import (
	"bytes"
	"context"
	"errors"
	"image"
	"image/color"
	"image/png"
	"io"
	"os"
	"path/filepath"
	"testing"
)

func captureTestImage() image.Image {
	img := image.NewRGBA(image.Rect(0, 0, 64, 64))
	for y := 0; y < 64; y++ {
		for x := 0; x < 64; x++ {
			img.Set(x, y, color.RGBA{R: uint8(x * 4), G: uint8(y * 4), B: 0x80, A: 0xff})
		}
	}
	return img
}

func captureTestPNG(t *testing.T) []byte {
	t.Helper()
	var data bytes.Buffer
	if err := png.Encode(&data, captureTestImage()); err != nil {
		t.Fatalf("encode fixture png: %v", err)
	}
	return data.Bytes()
}

func cancelledCaptureContext(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	return ctx
}

func TestCaptureStagesHonorCancelledContext(t *testing.T) {
	dir := t.TempDir()
	pngPath := filepath.Join(dir, "source.png")
	if err := os.WriteFile(pngPath, captureTestPNG(t), 0o600); err != nil {
		t.Fatalf("write fixture png: %v", err)
	}
	ctx := cancelledCaptureContext(t)
	args := captureScreenArgs{Format: "png", Quality: 90, MaxWidth: 32, MaxHeight: 32}

	t.Run("decode resize write", func(t *testing.T) {
		if _, _, err := ensureCaptureFormatAndSize(ctx, pngPath, args); !errors.Is(err, context.Canceled) {
			t.Fatalf("ensureCaptureFormatAndSize error=%v, want context.Canceled", err)
		}
	})
	t.Run("jpeg conversion", func(t *testing.T) {
		if err := convertPNGToJPEG(ctx, pngPath, filepath.Join(dir, "cancelled.jpg"), 90, 32, 32); !errors.Is(err, context.Canceled) {
			t.Fatalf("convertPNGToJPEG error=%v, want context.Canceled", err)
		}
	})
	t.Run("resize", func(t *testing.T) {
		if _, err := resizeImage(ctx, captureTestImage(), 32, 32); !errors.Is(err, context.Canceled) {
			t.Fatalf("resizeImage error=%v, want context.Canceled", err)
		}
	})
	t.Run("write", func(t *testing.T) {
		path := filepath.Join(dir, "cancelled-write.png")
		if err := writeImage(ctx, path, captureTestImage(), "png", 90); !errors.Is(err, context.Canceled) {
			t.Fatalf("writeImage error=%v, want context.Canceled", err)
		}
		if _, err := os.Stat(path); !os.IsNotExist(err) {
			t.Fatalf("cancelled write created or touched output file: %v", err)
		}
	})
	t.Run("hash", func(t *testing.T) {
		if _, err := fileSHA256(ctx, pngPath); !errors.Is(err, context.Canceled) {
			t.Fatalf("fileSHA256 error=%v, want context.Canceled", err)
		}
	})
	t.Run("placeholder", func(t *testing.T) {
		path := filepath.Join(dir, "cancelled-placeholder.png")
		if err := createPlaceholderCapture(ctx, path, args); !errors.Is(err, context.Canceled) {
			t.Fatalf("createPlaceholderCapture error=%v, want context.Canceled", err)
		}
		if _, err := os.Stat(path); !os.IsNotExist(err) {
			t.Fatalf("cancelled placeholder created output file: %v", err)
		}
	})
}

type cancelAfterChecksContext struct {
	context.Context
	cancel    context.CancelFunc
	remaining int
}

func newCancelAfterChecksContext(t *testing.T, checks int) *cancelAfterChecksContext {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	return &cancelAfterChecksContext{Context: ctx, cancel: cancel, remaining: checks}
}

func (c *cancelAfterChecksContext) Err() error {
	c.remaining--
	if c.remaining == 0 {
		c.cancel()
	}
	return c.Context.Err()
}

type countingReader struct {
	r     io.Reader
	reads int
}

func (r *countingReader) Read(p []byte) (int, error) {
	r.reads++
	return r.r.Read(p)
}

func TestCaptureDecodeChecksContextDuringReads(t *testing.T) {
	data := captureTestPNG(t)
	reader := &countingReader{r: bytes.NewReader(data)}
	ctx := newCancelAfterChecksContext(t, 2)
	if _, _, err := decodeCaptureImage(ctx, reader); !errors.Is(err, context.Canceled) {
		t.Fatalf("decode error=%v, want context.Canceled", err)
	}
	if reader.reads >= 2 {
		t.Fatalf("decode continued reading after cancellation: reads=%d", reader.reads)
	}
}

func TestCaptureResizeChecksContextDuringPixels(t *testing.T) {
	ctx := newCancelAfterChecksContext(t, 4)
	if _, err := resizeImage(ctx, captureTestImage(), 32, 32); !errors.Is(err, context.Canceled) {
		t.Fatalf("resize error=%v, want context.Canceled", err)
	}
}

type cancellingImage struct {
	image.Image
	cancel context.CancelFunc
	calls  int
}

func (img *cancellingImage) At(x, y int) color.Color {
	img.calls++
	if img.calls == 16 {
		img.cancel()
	}
	return img.Image.At(x, y)
}

func TestCaptureWriteChecksContextDuringEncoding(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	path := filepath.Join(t.TempDir(), "cancelled-during-encode.png")
	img := &cancellingImage{Image: captureTestImage(), cancel: cancel}
	if err := writeImage(ctx, path, img, "png", 90); !errors.Is(err, context.Canceled) {
		t.Fatalf("write error=%v, want context.Canceled", err)
	}
	file, err := os.Open(path)
	if err != nil {
		t.Fatalf("open partial capture: %v", err)
	}
	defer file.Close()
	if _, _, err := image.Decode(file); err == nil {
		t.Fatal("cancelled encoding left a complete valid image")
	}
}

func TestCaptureHashChecksContextDuringReads(t *testing.T) {
	data := bytes.Repeat([]byte("capture-hash-"), 1<<15)
	reader := &countingReader{r: bytes.NewReader(data)}
	ctx := newCancelAfterChecksContext(t, 2)
	if _, err := hashCaptureReader(ctx, reader); !errors.Is(err, context.Canceled) {
		t.Fatalf("hash error=%v, want context.Canceled", err)
	}
	if reader.reads >= 2 {
		t.Fatalf("hash continued reading after cancellation: reads=%d", reader.reads)
	}
}
