package server

import (
	"testing"
)

func TestParseXposedFocusArgsRequiresPackageAndActivity(t *testing.T) {
	t.Parallel()

	if _, err := parseXposedFocusArgs(map[string]interface{}{
		"focus_json": `{"package_name":"com.android.settings"}`,
	}); err == nil {
		t.Fatal("expected missing activity_class to fail")
	}

	state, err := parseXposedFocusArgs(map[string]interface{}{
		"focus_json": `{
			"schema_version":2,
			"package_name":"com.android.settings",
			"activity_class":"com.android.settings.Settings",
			"adapter_id":"settings_detail",
			"active":true
		}`,
	})
	if err != nil {
		t.Fatalf("parse focus args: %v", err)
	}
	if state["package_name"] != "com.android.settings" {
		t.Fatalf("unexpected package_name: %#v", state["package_name"])
	}
	if state["source"] != "report" {
		t.Fatalf("unexpected source: %#v", state["source"])
	}
	if _, ok := state["focus_json"].(string); !ok {
		t.Fatal("expected focus_json string in state")
	}
}
