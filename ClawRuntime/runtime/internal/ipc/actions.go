package ipc

// Action catalog is the single source of truth for Runtime IPC actions.
// App-side RuntimeActionCatalog should stay aligned with this list.

const (
	ActionPing              = "ping"
	ActionGetCapabilities   = "get_capabilities"
	ActionGetRuntimeStatus  = "get_runtime_status"
	ActionCaptureScreen     = "capture_screen"
	ActionInjectTap         = "inject_tap"
	ActionInjectSwipe       = "inject_swipe"
	ActionInjectKeyevent    = "inject_keyevent"
	ActionReadFileLimited   = "read_file_limited"
	ActionWriteFileLimited  = "write_file_limited"
	ActionStatFileLimited   = "stat_file_limited"
	ActionExecShellLimited  = "exec_shell_limited"
	ActionSubscribeEvents   = "subscribe_events"
	ActionReportXposedFocus = "report_xposed_focus"
	ActionReportXposedView  = "report_xposed_view"
	ActionTaskSubmit        = "task_submit"
	ActionTaskGet           = "task_get"
	ActionTaskList          = "task_list"
	ActionTaskCancel        = "task_cancel"
)

const (
	CapabilitySystemPing       = "system.ping"
	CapabilitySystemInspect    = "system.inspect"
	CapabilityScreenCapture    = "screen.capture"
	CapabilityInputInject      = "input.inject"
	CapabilityFileReadLimited  = "file.read.limited"
	CapabilityFileWriteLimited = "file.write.limited"
	CapabilityShellExecLimited = "shell.exec.limited"
	CapabilityEventSubscribe   = "event.subscribe"
	CapabilityEventReport      = "event.report"
	CapabilityTaskManage       = "task.manage"
)

// ActionCapability maps every allowed action to its capability token.
var ActionCapability = map[string]string{
	ActionPing:             CapabilitySystemPing,
	ActionGetCapabilities:  CapabilitySystemInspect,
	ActionGetRuntimeStatus: CapabilitySystemInspect,
	ActionCaptureScreen:    CapabilityScreenCapture,
	ActionInjectTap:        CapabilityInputInject,
	ActionInjectSwipe:      CapabilityInputInject,
	ActionInjectKeyevent:   CapabilityInputInject,
	ActionReadFileLimited:  CapabilityFileReadLimited,
	ActionWriteFileLimited: CapabilityFileWriteLimited,
	ActionStatFileLimited:  CapabilityFileReadLimited,
	ActionExecShellLimited: CapabilityShellExecLimited,
	ActionSubscribeEvents:  CapabilityEventSubscribe,
	ActionReportXposedFocus: CapabilityEventReport,
	ActionReportXposedView:  CapabilityEventReport,
	ActionTaskSubmit:       CapabilityTaskManage,
	ActionTaskGet:          CapabilityTaskManage,
	ActionTaskList:         CapabilityTaskManage,
	ActionTaskCancel:       CapabilityTaskManage,
}

func ExpectedCapability(action string) (string, bool) {
	capability, ok := ActionCapability[action]
	return capability, ok
}

func KnownActions() []string {
	actions := make([]string, 0, len(ActionCapability))
	for action := range ActionCapability {
		actions = append(actions, action)
	}
	return actions
}
