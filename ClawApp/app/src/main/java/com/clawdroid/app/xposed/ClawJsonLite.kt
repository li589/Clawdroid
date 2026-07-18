package com.clawdroid.app.xposed

import org.json.JSONObject

/**
 * Shared JSON field readers for focus/view live paths (org.json, not hand-rolled regex).
 */
internal object ClawJsonLite {
    fun parseObject(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || !trimmed.startsWith("{")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    fun string(raw: String, key: String): String? {
        val obj = parseObject(raw) ?: return null
        return string(obj, key)
    }

    fun string(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return runCatching { obj.getString(key) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun long(raw: String, key: String): Long? {
        val obj = parseObject(raw) ?: return null
        if (!obj.has(key) || obj.isNull(key)) return null
        return runCatching { obj.getLong(key) }.getOrNull()
    }

    fun boolean(raw: String, key: String): Boolean? {
        val obj = parseObject(raw) ?: return null
        if (!obj.has(key) || obj.isNull(key)) return null
        return runCatching { obj.getBoolean(key) }.getOrNull()
    }

    fun extrasString(raw: String, key: String): String? {
        val obj = parseObject(raw) ?: return null
        val extras = obj.optJSONObject("extras") ?: return null
        return string(extras, key)
    }

    fun extrasKeysPrefixed(raw: String, prefix: String): Int {
        val obj = parseObject(raw) ?: return 0
        val extras = obj.optJSONObject("extras") ?: return 0
        var count = 0
        val names = extras.keys()
        while (names.hasNext()) {
            if (names.next().startsWith(prefix)) count++
        }
        return count
    }

    fun hasRequiredIdentity(raw: String): Boolean {
        val obj = parseObject(raw) ?: return false
        val packageName = string(obj, "package_name").orEmpty()
        val activityClass = string(obj, "activity_class").orEmpty()
        return packageName.isNotBlank() && activityClass.isNotBlank()
    }
}
