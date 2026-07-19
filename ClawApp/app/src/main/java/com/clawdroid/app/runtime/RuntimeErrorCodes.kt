package com.clawdroid.app.runtime

/**
 * Mirrors ClawRuntime `ipc` error codes in `errors.go`.
 * Keep aligned via `scripts/check-runtime-catalog.ps1`.
 */
object RuntimeErrorCodes {
    const val OK = 0
    const val ERR_UNKNOWN = 1
    const val ERR_INVALID_REQUEST = 2
    const val ERR_UNSUPPORTED_VERSION = 3
    const val ERR_TIMEOUT = 4
    const val ERR_CANCELLED = 5
    const val ERR_PAYLOAD_TOO_LARGE = 6
    const val ERR_ACTION_NOT_ALLOWED = 7
    const val ERR_RATE_LIMITED = 8

    const val ERR_PEER_VERIFY_FAILED = 1001
    const val ERR_SIGNATURE_MISMATCH = 1002
    const val ERR_CHALLENGE_FAILED = 1003
    const val ERR_SESSION_EXPIRED = 1004
    const val ERR_CAPABILITY_TOKEN_INVALID = 1005

    const val ERR_ROOT_UNAVAILABLE = 2001
    const val ERR_ACCESSIBILITY_UNAVAILABLE = 2002
    const val ERR_SCREEN_CAPTURE_UNAVAILABLE = 2003
    const val ERR_CAPABILITY_NOT_GRANTED = 2004

    const val ERR_INPUT_INJECT_FAILED = 3001
    const val ERR_SHELL_DENIED = 3002
    const val ERR_SHELL_EXEC_FAILED = 3003
    const val ERR_FILE_OUT_OF_SCOPE = 3004
    const val ERR_FILE_READ_FAILED = 3005
    const val ERR_FILE_WRITE_FAILED = 3006

    const val ERR_ADAPTER_NOT_AVAILABLE = 4001
    const val ERR_TARGET_VERSION_UNSUPPORTED = 4002
    const val ERR_TARGET_UI_CHANGED = 4003

    const val ERR_SELINUX_DENIED = 5001
    const val ERR_DAEMON_UNHEALTHY = 5002
    const val ERR_ROM_UNSUPPORTED = 5003

    const val ERR_TASK_NOT_FOUND = 7001
    const val ERR_TASK_STATE_INVALID = 7002
    const val ERR_TASK_SUBMIT_FAILED = 7003
    const val ERR_TASK_CANCEL_FAILED = 7004
    const val ERR_TASK_QUEUE_FULL = 7005

    val allCodes: Set<Int> = setOf(
        OK,
        ERR_UNKNOWN,
        ERR_INVALID_REQUEST,
        ERR_UNSUPPORTED_VERSION,
        ERR_TIMEOUT,
        ERR_CANCELLED,
        ERR_PAYLOAD_TOO_LARGE,
        ERR_ACTION_NOT_ALLOWED,
        ERR_RATE_LIMITED,
        ERR_PEER_VERIFY_FAILED,
        ERR_SIGNATURE_MISMATCH,
        ERR_CHALLENGE_FAILED,
        ERR_SESSION_EXPIRED,
        ERR_CAPABILITY_TOKEN_INVALID,
        ERR_ROOT_UNAVAILABLE,
        ERR_ACCESSIBILITY_UNAVAILABLE,
        ERR_SCREEN_CAPTURE_UNAVAILABLE,
        ERR_CAPABILITY_NOT_GRANTED,
        ERR_INPUT_INJECT_FAILED,
        ERR_SHELL_DENIED,
        ERR_SHELL_EXEC_FAILED,
        ERR_FILE_OUT_OF_SCOPE,
        ERR_FILE_READ_FAILED,
        ERR_FILE_WRITE_FAILED,
        ERR_ADAPTER_NOT_AVAILABLE,
        ERR_TARGET_VERSION_UNSUPPORTED,
        ERR_TARGET_UI_CHANGED,
        ERR_SELINUX_DENIED,
        ERR_DAEMON_UNHEALTHY,
        ERR_ROM_UNSUPPORTED,
        ERR_TASK_NOT_FOUND,
        ERR_TASK_STATE_INVALID,
        ERR_TASK_SUBMIT_FAILED,
        ERR_TASK_CANCEL_FAILED,
        ERR_TASK_QUEUE_FULL
    )

    fun message(code: Int): String = when (code) {
        OK -> "success"
        ERR_INVALID_REQUEST -> "invalid request"
        ERR_UNSUPPORTED_VERSION -> "unsupported protocol version"
        ERR_TIMEOUT -> "request timeout"
        ERR_CANCELLED -> "request cancelled"
        ERR_PAYLOAD_TOO_LARGE -> "payload too large"
        ERR_ACTION_NOT_ALLOWED -> "action not allowed"
        ERR_RATE_LIMITED -> "rate limited"
        ERR_PEER_VERIFY_FAILED -> "peer verification failed"
        ERR_SIGNATURE_MISMATCH -> "signature mismatch"
        ERR_CHALLENGE_FAILED -> "challenge verification failed"
        ERR_SESSION_EXPIRED -> "session expired"
        ERR_CAPABILITY_TOKEN_INVALID -> "capability token invalid"
        ERR_ROOT_UNAVAILABLE -> "root unavailable"
        ERR_ACCESSIBILITY_UNAVAILABLE -> "accessibility unavailable"
        ERR_SCREEN_CAPTURE_UNAVAILABLE -> "screen capture unavailable"
        ERR_CAPABILITY_NOT_GRANTED -> "capability not granted"
        ERR_INPUT_INJECT_FAILED -> "input inject failed"
        ERR_SHELL_DENIED -> "shell denied"
        ERR_SHELL_EXEC_FAILED -> "shell exec failed"
        ERR_FILE_OUT_OF_SCOPE -> "file out of scope"
        ERR_FILE_READ_FAILED -> "file read failed"
        ERR_FILE_WRITE_FAILED -> "file write failed"
        ERR_ADAPTER_NOT_AVAILABLE -> "adapter not available"
        ERR_TARGET_VERSION_UNSUPPORTED -> "target version unsupported"
        ERR_TARGET_UI_CHANGED -> "target ui changed"
        ERR_SELINUX_DENIED -> "selinux denied"
        ERR_DAEMON_UNHEALTHY -> "daemon unhealthy"
        ERR_ROM_UNSUPPORTED -> "rom unsupported"
        ERR_TASK_NOT_FOUND -> "task not found"
        ERR_TASK_STATE_INVALID -> "invalid task state transition"
        ERR_TASK_SUBMIT_FAILED -> "task submission failed"
        ERR_TASK_CANCEL_FAILED -> "task cancellation failed"
        ERR_TASK_QUEUE_FULL -> "task queue is full"
        else -> "unknown error"
    }
}
