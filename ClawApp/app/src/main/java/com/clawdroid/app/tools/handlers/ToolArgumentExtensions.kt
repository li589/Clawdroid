package com.clawdroid.app.tools.handlers

internal fun Map<String, Any?>.string(vararg keys: String): String {
    for (key in keys) {
        val value = this[key] ?: continue
        val text = value.toString().trim()
        if (text.isNotEmpty() && text != "null") {
            return text
        }
    }
    return ""
}

internal fun Map<String, Any?>.int(key: String, default: Int): Int {
    return optionalInt(key) ?: default
}

internal fun Map<String, Any?>.optionalInt(key: String): Int? {
    val value = this[key] ?: return null
    return when (value) {
        is Number -> value.toInt()
        else -> value.toString().trim().toIntOrNull()
    }
}

internal fun Map<String, Any?>.long(key: String, default: Long): Long {
    val value = this[key] ?: return default
    return when (value) {
        is Number -> value.toLong()
        else -> value.toString().trim().toLongOrNull() ?: default
    }
}

internal fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean {
    val value = this[key] ?: return default
    return when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> value.toString().trim().equals("true", ignoreCase = true)
    }
}
