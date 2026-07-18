package server

import (
	"testing"
)

func TestParseXposedViewArgsRequiresPackageAndActivity(t *testing.T) {
	t.Parallel()

	if _, err := parseXposedViewArgs(map[string]interface{}{
		"view_json": `{"package_name":"com.android.settings"}`,
	}); err == nil {
		t.Fatal("expected missing activity_class to fail")
	}

	state, err := parseXposedViewArgs(map[string]interface{}{
		"view_json": `{
			"schema_version":1,
			"package_name":"com.android.settings",
			"activity_class":"com.android.settings.Settings",
			"node_count":3,
			"compose_surface":false
		}`,
	})
	if err != nil {
		t.Fatalf("parse view args: %v", err)
	}
	if state["package_name"] != "com.android.settings" {
		t.Fatalf("unexpected package_name: %#v", state["package_name"])
	}
	if state["source"] != "report" {
		t.Fatalf("unexpected source: %#v", state["source"])
	}
}
