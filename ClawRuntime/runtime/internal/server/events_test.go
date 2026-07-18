package server

import (
	"encoding/json"
	"reflect"
	"testing"
)

func TestParseSubscribeEventsArgsDeduplicatesAndPreservesOrder(t *testing.T) {
	t.Parallel()

	args, err := parseSubscribeEventsArgs(map[string]interface{}{
		"events": []interface{}{
			subscribeEventCapabilityChanged,
			subscribeEventDaemonStatus,
			subscribeEventCapabilityChanged,
		},
	})
	if err != nil {
		t.Fatalf("parse subscribe events args: %v", err)
	}

	want := []string{
		subscribeEventCapabilityChanged,
		subscribeEventDaemonStatus,
	}
	if !reflect.DeepEqual(args.Events, want) {
		t.Fatalf("unexpected events: got %#v want %#v", args.Events, want)
	}
}

func TestParseSubscribeEventsArgsRejectsUnsupportedEvent(t *testing.T) {
	t.Parallel()

	if _, err := parseSubscribeEventsArgs(map[string]interface{}{
		"events": []interface{}{"unsupported_event"},
	}); err == nil {
		t.Fatal("expected unsupported event to be rejected")
	}
}

func TestDiffSnapshotEventFramesIgnoresStringSliceOrdering(t *testing.T) {
	t.Parallel()

	previous := eventSnapshot{
		capabilityState: map[string]interface{}{
			"capabilities": []string{"screen.capture", "input.inject"},
		},
	}
	current := eventSnapshot{
		capabilityState: map[string]interface{}{
			"capabilities": []string{"input.inject", "screen.capture"},
		},
	}

	frames := diffSnapshotEventFrames(previous, current, []string{subscribeEventCapabilityChanged})
	if len(frames) != 0 {
		t.Fatalf("expected no diff frames for reordered capabilities, got %#v", frames)
	}
}

func TestMarshalFocusedWindowIncludesParsedFields(t *testing.T) {
	t.Parallel()

	raw := "mCurrentFocus=Window{123 u0 com.example/.MainActivity}"
	marshaled := marshalFocusedWindow(raw)

	var payload map[string]string
	if err := json.Unmarshal([]byte(marshaled), &payload); err != nil {
		t.Fatalf("unmarshal focused window payload: %v", err)
	}

	if payload["source"] != "mCurrentFocus" {
		t.Fatalf("unexpected source: got %q", payload["source"])
	}
	if payload["package"] != "com.example" {
		t.Fatalf("unexpected package: got %q", payload["package"])
	}
	if payload["activity"] != ".MainActivity" {
		t.Fatalf("unexpected activity: got %q", payload["activity"])
	}
	if payload["summary"] != "com.example/.MainActivity" {
		t.Fatalf("unexpected summary: got %q", payload["summary"])
	}
}

func TestExtractFocusedComponentReturnsEmptyForNoise(t *testing.T) {
	t.Parallel()

	if component := extractFocusedComponent("random line without activity"); component != "" {
		t.Fatalf("expected empty focused component, got %q", component)
	}
}

func TestDiffSnapshotEventFramesDetectsXposedFocusChange(t *testing.T) {
	t.Parallel()

	previous := eventSnapshot{
		xposedFocusState: map[string]interface{}{
			"package_name":   "com.android.settings",
			"activity_class": "com.android.settings.Settings",
		},
	}
	current := eventSnapshot{
		xposedFocusState: map[string]interface{}{
			"package_name":   "com.android.settings",
			"activity_class": "com.android.settings.wifi.WifiSettings",
			"source":         "report",
		},
	}

	frames := diffSnapshotEventFrames(previous, current, []string{subscribeEventXposedFocusChanged})
	if len(frames) != 1 {
		t.Fatalf("expected 1 xposed_focus_changed frame, got %#v", frames)
	}
	if frames[0].Event != subscribeEventXposedFocusChanged {
		t.Fatalf("unexpected event name: %q", frames[0].Event)
	}
	if frames[0].Data["activity_class"] != "com.android.settings.wifi.WifiSettings" {
		t.Fatalf("unexpected activity_class: %#v", frames[0].Data["activity_class"])
	}
}

func TestParseSubscribeEventsArgsAllowsXposedFocusChanged(t *testing.T) {
	t.Parallel()

	args, err := parseSubscribeEventsArgs(map[string]interface{}{
		"events": []interface{}{subscribeEventXposedFocusChanged},
	})
	if err != nil {
		t.Fatalf("parse subscribe events args: %v", err)
	}
	if len(args.Events) != 1 || args.Events[0] != subscribeEventXposedFocusChanged {
		t.Fatalf("unexpected events: %#v", args.Events)
	}
}

func TestParseSubscribeEventsArgsAllowsXposedViewChanged(t *testing.T) {
	t.Parallel()

	args, err := parseSubscribeEventsArgs(map[string]interface{}{
		"events": []interface{}{subscribeEventXposedViewChanged},
	})
	if err != nil {
		t.Fatalf("parse subscribe events args: %v", err)
	}
	if len(args.Events) != 1 || args.Events[0] != subscribeEventXposedViewChanged {
		t.Fatalf("unexpected events: %#v", args.Events)
	}
}

func TestDiffSnapshotEventFramesDetectsXposedViewChange(t *testing.T) {
	t.Parallel()

	previous := eventSnapshot{
		xposedViewState: map[string]interface{}{
			"package_name": "com.android.settings",
			"node_count":   float64(4),
		},
	}
	current := eventSnapshot{
		xposedViewState: map[string]interface{}{
			"package_name":    "com.android.settings",
			"node_count":      float64(8),
			"compose_surface": true,
			"source":          "report",
		},
	}

	frames := diffSnapshotEventFrames(previous, current, []string{subscribeEventXposedViewChanged})
	if len(frames) != 1 {
		t.Fatalf("expected 1 xposed_view_changed frame, got %#v", frames)
	}
	if frames[0].Data["compose_surface"] != true {
		t.Fatalf("unexpected compose_surface: %#v", frames[0].Data["compose_surface"])
	}
}
