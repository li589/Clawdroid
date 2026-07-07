package com.clawdroid.app.ipc

sealed class IpcError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data class ConnectionFailed(override val message: String, override val cause: Throwable? = null) : IpcError(message, cause)
    data class SocketTimeout(override val message: String, override val cause: Throwable? = null) : IpcError(message, cause)
    data class ProtocolMismatch(val expected: Int, val actual: Int, override val cause: Throwable? = null) : IpcError("protocol version mismatch: expected=$expected actual=$actual", cause)
    data class AuthenticationFailed(override val message: String, override val cause: Throwable? = null) : IpcError(message, cause)
    data class RequestFailed(val action: String, val code: Int, override val message: String, override val cause: Throwable? = null) : IpcError("request '$action' failed: [$code] $message", cause)
    data class HmacVerificationFailed(override val message: String, override val cause: Throwable? = null) : IpcError(message, cause)
    data class UnexpectedResponse(override val message: String, override val cause: Throwable? = null) : IpcError(message, cause)
}
