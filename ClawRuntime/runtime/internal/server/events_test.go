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
