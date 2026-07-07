package server

import (
	"reflect"
	"testing"

	"clawdroid/runtime/internal/config"
)

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

	srv := &Server{cfg: config.Config{AllowedSignatures: []string{}}}
	if !srv.isAllowedSignature("sha256:anything") {
		t.Fatal("expected signature to be allowed when whitelist is empty")
	}
}

func TestIsAllowedSignatureMatchesCaseInsensitiveWhitelist(t *testing.T) {
	t.Parallel()

	srv := &Server{
		cfg: config.Config{
			AllowedSignatures: []string{"sha256:ABCDEF"},
		},
	}

	if !srv.isAllowedSignature("sha256:abcdef") {
		t.Fatal("expected signature whitelist match to be case insensitive")
	}
	if srv.isAllowedSignature("sha256:other") {
		t.Fatal("expected unmatched signature to be rejected")
	}
}
