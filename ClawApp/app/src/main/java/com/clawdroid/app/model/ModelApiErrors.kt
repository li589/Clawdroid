package com.clawdroid.app.model

sealed class ModelApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    data class ApiKeyMissing(val provider: String) : ModelApiError("API key is required for provider: $provider")
    data class NetworkError(override val message: String, override val cause: Throwable? = null) : ModelApiError(message, cause)
    data class HttpError(val code: Int, val body: String?, override val cause: Throwable? = null) : ModelApiError("HTTP $code: ${body ?: "no body"}", cause)
    data class InvalidResponse(override val message: String, override val cause: Throwable? = null) : ModelApiError(message, cause)
    data class RateLimited(val retryAfterSeconds: Int? = null) : ModelApiError("Rate limited${retryAfterSeconds?.let { ", retry after ${it}s" } ?: ""}")
    data class AuthenticationError(override val message: String, override val cause: Throwable? = null) : ModelApiError(message, cause)
}
