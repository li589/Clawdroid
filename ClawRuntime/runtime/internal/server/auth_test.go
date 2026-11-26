package server

import (
	"context"
	"errors"
	"reflect"
	"testing"
	"time"

	"clawdroid/runtime/internal/audit"
	"clawdroid/runtime/internal/config"
)

func TestLookupPackagesForUIDHonorsContextTimeout(t *testing.T) {
	t.Parallel()

	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Millisecond)
	defer cancel()
	var calls int
	started := time.Now()
	packages, err := lookupPackagesForUIDWithRunner(ctx, 12345, func(ctx context.Context, _ string, _ ...string) ([]byte, error) {
		calls++
		<-ctx.Done()
		return nil, ctx.Err()
	})
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("lookup error = %v, want deadline exceeded", err)
	}
	if calls != 1 {
		t.Fatalf("lookup invoked %d commands, want 1 after deadline", calls)
	}
	if packages != nil {
		t.Fatalf("timed-out lookup returned packages: %#v", packages)
	}
	if elapsed := time.Since(started); elapsed > time.Second {
		t.Fatalf("lookup ignored context timeout and took %s", elapsed)
	}
}

func TestLookupPackagesForUIDFallsBackAfterCommandFailure(t *testing.T) {
	t.Parallel()

	var commands []string
	packages, err := lookupPackagesForUIDWithRunner(context.Background(), 12345, func(_ context.Context, name string, _ ...string) ([]byte, error) {
		commands = append(commands, name)
		if name == "cmd" {
			return nil, errors.New("cmd unavailable")
		}
		return []byte("package:com.example.app uid:12345\n"), nil
	})
	if err != nil {
		t.Fatalf("fallback lookup error = %v", err)
	}
	if !reflect.DeepEqual(commands, []string{"cmd", "pm"}) {
		t.Fatalf("commands = %#v, want cmd then pm", commands)
	}
	if !reflect.DeepEqual(packages, []string{"com.example.app"}) {
		t.Fatalf("packages = %#v", packages)
	}
}

func TestWithinTimestampSkewRejectsMissingAndExtremeValues(t *testing.T) {
	t.Parallel()

	cfg := config.Default()
	srv := New(cfg, audit.NewLogger())
	t.Cleanup(srv.taskScheduler.Close)
	now := time.Now().Unix()
	for _, timestamp := range []int64{0, -1, -1 << 63, 1<<63 - 1, now - cfg.TimestampSkewSec - 1, now + cfg.TimestampSkewSec + 1} {
		if srv.withinTimestampSkew(timestamp) {
			t.Fatalf("timestamp %d was accepted", timestamp)
		}
	}
	if !srv.withinTimestampSkew(time.Now().Unix()) {
		t.Fatal("current timestamp was rejected")
	}
}

func TestParseUIDPackageListOutputDeduplicatesPackages(t *testing.T) {
	t.Parallel()

	output := "package:com.example.alpha\npackage:com.example.beta uid:10123\npackage:com.example.alpha\n"
	got := parseUIDPackageListOutput(output)
	want := []string{"com.example.alpha", "com.example.beta"}

	if !reflect.DeepEqual(got, want) {
		t.Fatalf("unexpected package list: got %#v want %#v", got, want)
	}
}

func TestIsAllowedSignatureAllowsAnyWhenWhitelistEmpty(t *testing.T) {
	t.Parallel()

	srv := New(config.Config{AllowedSignatures: []string{}}, audit.NewLogger())
	if !srv.isAllowedSignature("sha256:anything") {
		t.Fatal("expected signature to be allowed when whitelist is empty")
	}
}

func TestIsAllowedSignatureMatchesCaseInsensitiveWhitelist(t *testing.T) {
	t.Parallel()

	srv := New(config.Config{
		AllowedSignatures: []string{"sha256:ABCDEF"},
	}, audit.NewLogger())

	if !srv.isAllowedSignature("sha256:abcdef") {
		t.Fatal("expected signature whitelist match to be case insensitive")
	}
	if srv.isAllowedSignature("sha256:other") {
		t.Fatal("expected unmatched signature to be rejected")
	}
}
