package server

import "testing"

func TestAuthDigestMatchesAppContract(t *testing.T) {
	got := authDigest(
		"test-shared-secret-0123456789abcdef",
		"nonce-xyz",
		"com.clawdroid.app.debug",
		"sha256:AaBbCcDd",
		1_700_000_000,
	)
	want := "cd8e1b7de9070c14572d68d5d438cbc9acd1ddd4de2b302b2f019fcc1dd7ad5c"
	if got != want {
		t.Fatalf("authDigest mismatch\n got: %s\nwant: %s", got, want)
	}
}

func TestAuthDigestNormalizesSignatureCase(t *testing.T) {
	upper := authDigest("s", "n", "pkg", "sha256:AABB", 1)
	lower := authDigest("s", "n", "pkg", "sha256:aabb", 1)
	if upper != lower {
		t.Fatalf("signature case should not change digest: %s vs %s", upper, lower)
	}
}
