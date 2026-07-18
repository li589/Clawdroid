package server

import "testing"

func TestPickNewerXposedStatePrefersNewerFile(t *testing.T) {
	t.Parallel()

	memory := map[string]interface{}{
		"package_name":          "com.old",
		"activity_class":        "Old",
		"reported_at_epoch_ms":  int64(100),
		"source":                "report",
	}
	file := map[string]interface{}{
		"package_name":         "com.new",
		"activity_class":       "New",
		"loaded_at_epoch_ms":   int64(200),
		"source":               "file",
	}
	picked := pickNewerXposedState(memory, file)
	if picked["package_name"] != "com.new" {
		t.Fatalf("expected file to win, got %#v", picked["package_name"])
	}
}

func TestPickNewerXposedStatePrefersNewerMemory(t *testing.T) {
	t.Parallel()

	memory := map[string]interface{}{
		"package_name":         "com.report",
		"activity_class":       "Reported",
		"reported_at_epoch_ms": int64(300),
		"source":               "report",
	}
	file := map[string]interface{}{
		"package_name":       "com.file",
		"activity_class":     "File",
		"loaded_at_epoch_ms": int64(200),
		"source":             "file",
	}
	picked := pickNewerXposedState(memory, file)
	if picked["package_name"] != "com.report" {
		t.Fatalf("expected memory to win, got %#v", picked["package_name"])
	}
}

func TestPickNewerXposedStateEmptySides(t *testing.T) {
	t.Parallel()

	file := map[string]interface{}{"package_name": "com.file", "loaded_at_epoch_ms": int64(1)}
	if pickNewerXposedState(nil, file)["package_name"] != "com.file" {
		t.Fatal("empty memory should yield file")
	}
	memory := map[string]interface{}{"package_name": "com.mem", "reported_at_epoch_ms": int64(1)}
	if pickNewerXposedState(memory, nil)["package_name"] != "com.mem" {
		t.Fatal("empty file should yield memory")
	}
}
