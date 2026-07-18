package com.clawdroid.app.xposed

import java.io.File

/**
 * Schema v2 focus snapshots: nested extras, atomic latest + per-package ring.
 */
internal object XposedFocusSnapshotStore {
    const val SCHEMA_VERSION = 2
    const val LATEST_FILE = "focus_latest.json"
    const val LEGACY_FILE = "focus.json"
    const val RING_DIR = "focus_ring"
    private const val RING_KEEP = 8

    @Volatile
    internal var snapshotDirOverride: File? = null

    /** Writes latest + ring; returns payload JSON on success, null on failure. */
    fun write(
        packageName: String,
        processName: String,
        activityClass: String,
        adapterId: String,
        loadedAtEpochMs: Long = System.currentTimeMillis(),
        activityTitle: String = "",
        intentAction: String = "",
        intentData: String = "",
        extras: Map<String, String> = emptyMap(),
        active: Boolean = true
    ): String? {
        return runCatching {
            val dir = snapshotDir()
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val payload = buildPayload(
                packageName = packageName,
                processName = processName,
                activityClass = activityClass,
                adapterId = adapterId,
                loadedAtEpochMs = loadedAtEpochMs,
                activityTitle = activityTitle,
                intentAction = intentAction,
                intentData = intentData,
                extras = extras,
                active = active
            )
            atomicWrite(File(dir, LATEST_FILE), payload)
            // Keep legacy name as atomic copy of latest for old probes.
            atomicWrite(File(dir, LEGACY_FILE), payload)
            writeRing(dir, packageName, loadedAtEpochMs, payload)
            payload
        }.getOrNull()
    }

    fun summaryForProbe(): String = formatSummary(readRaw())

    fun formatSummary(rawInput: String?): String {
        val raw = rawInput?.trim().orEmpty()
        if (raw.isBlank()) {
            return "暂无目标 Activity 焦点快照"
        }
        val packageName = extractJsonString(raw, "package_name").orEmpty()
        val activityClass = extractJsonString(raw, "activity_class").orEmpty()
        val loadedAt = extractJsonLong(raw, "loaded_at_epoch_ms") ?: 0L
        val adapterId = extractJsonString(raw, "adapter_id").orEmpty()
        val title = extractJsonString(raw, "activity_title").orEmpty()
        val action = extractJsonString(raw, "intent_action").orEmpty()
        val data = extractJsonString(raw, "intent_data").orEmpty()
        val active = extractJsonBoolean(raw, "active")
        val fragment = extractNestedExtrasString(raw, "settings_fragment").orEmpty()
        val wechatPage = extractNestedExtrasString(raw, "wechat_page").orEmpty()
        val browserUrl = extractNestedExtrasString(raw, "browser_url").orEmpty()
        val ctrlCount = ClawJsonLite.extrasKeysPrefixed(raw, "wechat_ctrl_")
        if (packageName.isBlank() || activityClass.isBlank()) {
            return "焦点快照已存在但字段不完整"
        }
        val titleSuffix = if (title.isNotBlank()) " \"$title\"" else ""
        val intentSuffix = buildString {
            if (action.isNotBlank()) append(" action=$action")
            if (data.isNotBlank()) append(" data=${data.take(80)}")
            if (fragment.isNotBlank()) append(" fragment=$fragment")
            if (wechatPage.isNotBlank()) append(" wechat_page=$wechatPage")
            if (ctrlCount > 0) append(" ctrl=$ctrlCount")
            if (browserUrl.isNotBlank()) append(" browser_url=${browserUrl.take(64)}")
            if (active == false) append(" inactive")
        }
        val adapterSuffix = if (adapterId.isNotBlank()) " [$adapterId]" else ""
        return "$packageName/$activityClass$titleSuffix$intentSuffix @ $loadedAt$adapterSuffix"
    }

    fun readRaw(): String? {
        return runCatching {
            val dir = snapshotDir()
            val latest = File(dir, LATEST_FILE)
            when {
                latest.exists() -> latest.readText()
                File(dir, LEGACY_FILE).exists() -> File(dir, LEGACY_FILE).readText()
                else -> null
            }
        }.getOrNull()
    }

    internal fun snapshotDir(): File = snapshotDirOverride ?: File(XposedPaths.DIR)

    internal fun latestFile(): File = File(snapshotDir(), LATEST_FILE)

    internal fun buildPayload(
        packageName: String,
        processName: String,
        activityClass: String,
        adapterId: String,
        loadedAtEpochMs: Long,
        activityTitle: String,
        intentAction: String,
        intentData: String,
        extras: Map<String, String>,
        active: Boolean
    ): String {
        return buildString {
            append('{')
            append("\"schema_version\":").append(SCHEMA_VERSION)
            append(",\"adapter_id\":\"").append(escape(adapterId)).append('"')
            append(",\"package_name\":\"").append(escape(packageName)).append('"')
            append(",\"process_name\":\"").append(escape(processName)).append('"')
            append(",\"activity_class\":\"").append(escape(activityClass)).append('"')
            append(",\"loaded_at_epoch_ms\":").append(loadedAtEpochMs)
            append(",\"active\":").append(active)
            if (activityTitle.isNotBlank()) {
                append(",\"activity_title\":\"").append(escape(activityTitle)).append('"')
            }
            if (intentAction.isNotBlank()) {
                append(",\"intent_action\":\"").append(escape(intentAction)).append('"')
            }
            if (intentData.isNotBlank()) {
                append(",\"intent_data\":\"").append(escape(intentData)).append('"')
            }
            append(",\"extras\":{")
            var first = true
            for ((key, value) in extras) {
                if (key.isBlank()) continue
                if (!first) append(',')
                first = false
                append('"').append(escape(key.take(64))).append("\":\"")
                    .append(escape(value)).append('"')
            }
            append('}')
            append('}')
        }
    }

    private fun writeRing(dir: File, packageName: String, epochMs: Long, payload: String) {
        val ring = File(dir, RING_DIR)
        if (!ring.exists()) {
            ring.mkdirs()
        }
        val safePkg = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val prefix = "focus_${safePkg}_"
        // Replace prior ring entries for this package, then keep newest N across all packages.
        ring.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".json") }
            ?.forEach { runCatching { it.delete() } }
        atomicWrite(File(ring, "${prefix}${epochMs}.json"), payload)
        ring.listFiles()
            ?.filter { it.isFile && it.name.startsWith("focus_") && it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(RING_KEEP)
            ?.forEach { runCatching { it.delete() } }
    }

    private fun atomicWrite(file: File, payload: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(payload)
        if (!tmp.renameTo(file)) {
            file.writeText(payload)
            tmp.delete()
        }
    }

    fun extractJsonString(raw: String, key: String): String? = ClawJsonLite.string(raw, key)

    internal fun extractJsonLong(raw: String, key: String): Long? = ClawJsonLite.long(raw, key)

    internal fun extractJsonBoolean(raw: String, key: String): Boolean? = ClawJsonLite.boolean(raw, key)

    internal fun extractNestedExtrasString(raw: String, key: String): String? =
        ClawJsonLite.extrasString(raw, key)

    private fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
