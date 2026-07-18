package com.clawdroid.app.runtime

/**
 * Mirrors ClawRuntime `ipc.ActionCapability` catalog.
 * Keep this list aligned with `ClawRuntime/runtime/internal/ipc/actions.go`.
 */
object RuntimeActionCatalog {
    const val PING = "ping"
    const val GET_CAPABILITIES = "get_capabilities"
    const val GET_RUNTIME_STATUS = "get_runtime_status"
    const val CAPTURE_SCREEN = "capture_screen"
    const val INJECT_TAP = "inject_tap"
    const val INJECT_SWIPE = "inject_swipe"
    const val INJECT_KEYEVENT = "inject_keyevent"
    const val READ_FILE_LIMITED = "read_file_limited"
    const val WRITE_FILE_LIMITED = "write_file_limited"
    const val STAT_FILE_LIMITED = "stat_file_limited"
    const val EXEC_SHELL_LIMITED = "exec_shell_limited"
    const val SUBSCRIBE_EVENTS = "subscribe_events"
    const val REPORT_XPOSED_FOCUS = "report_xposed_focus"
    const val REPORT_XPOSED_VIEW = "report_xposed_view"
    const val TASK_SUBMIT = "task_submit"
    const val TASK_GET = "task_get"
    const val TASK_LIST = "task_list"
    const val TASK_CANCEL = "task_cancel"

    const val CAPABILITY_SYSTEM_PING = "system.ping"
    const val CAPABILITY_SYSTEM_INSPECT = "system.inspect"
    const val CAPABILITY_SCREEN_CAPTURE = "screen.capture"
    const val CAPABILITY_INPUT_INJECT = "input.inject"
    const val CAPABILITY_FILE_READ_LIMITED = "file.read.limited"
    const val CAPABILITY_FILE_WRITE_LIMITED = "file.write.limited"
    const val CAPABILITY_SHELL_EXEC_LIMITED = "shell.exec.limited"
    const val CAPABILITY_EVENT_SUBSCRIBE = "event.subscribe"
    const val CAPABILITY_EVENT_REPORT = "event.report"
    const val CAPABILITY_TASK_MANAGE = "task.manage"

    val actionToCapability: Map<String, String> = mapOf(
        PING to CAPABILITY_SYSTEM_PING,
        GET_CAPABILITIES to CAPABILITY_SYSTEM_INSPECT,
        GET_RUNTIME_STATUS to CAPABILITY_SYSTEM_INSPECT,
        CAPTURE_SCREEN to CAPABILITY_SCREEN_CAPTURE,
        INJECT_TAP to CAPABILITY_INPUT_INJECT,
        INJECT_SWIPE to CAPABILITY_INPUT_INJECT,
        INJECT_KEYEVENT to CAPABILITY_INPUT_INJECT,
        READ_FILE_LIMITED to CAPABILITY_FILE_READ_LIMITED,
        WRITE_FILE_LIMITED to CAPABILITY_FILE_WRITE_LIMITED,
        STAT_FILE_LIMITED to CAPABILITY_FILE_READ_LIMITED,
        EXEC_SHELL_LIMITED to CAPABILITY_SHELL_EXEC_LIMITED,
        SUBSCRIBE_EVENTS to CAPABILITY_EVENT_SUBSCRIBE,
        REPORT_XPOSED_FOCUS to CAPABILITY_EVENT_REPORT,
        REPORT_XPOSED_VIEW to CAPABILITY_EVENT_REPORT,
        TASK_SUBMIT to CAPABILITY_TASK_MANAGE,
        TASK_GET to CAPABILITY_TASK_MANAGE,
        TASK_LIST to CAPABILITY_TASK_MANAGE,
        TASK_CANCEL to CAPABILITY_TASK_MANAGE
    )

    fun capabilityFor(action: String): String? = actionToCapability[action]
}
