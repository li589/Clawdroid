package com.clawdroid.app.runtime

/**
 * Structured App ↔ Magisk Runtime compatibility check.
 */
data class RuntimeCompatSnapshot(
    val daemonVersion: String = "",
    val protocolVersion: Int = 0,
    val reportedActions: Set<String> = emptySet(),
    val missingActions: List<String> = emptyList(),
    val status: Status = Status.Unknown,
    val checkedAtEpochMs: Long = 0L
) {
    enum class Status {
        Unknown,
        Ok,
        ProtocolMismatch,
        ModuleStale
    }

    val isOk: Boolean get() = status == Status.Ok

    fun bannerText(appVersionName: String): String {
        return when (status) {
            Status.Unknown -> ""
            Status.Ok ->
                "对齐正常 · App $appVersionName · Runtime ${daemonVersion.ifBlank { "?" }} · protocol $protocolVersion"
            Status.ProtocolMismatch ->
                "协议不匹配 · App 期望 protocol=${RuntimeActionCatalog.EXPECTED_PROTOCOL_VERSION}，" +
                    "Runtime 报告 $protocolVersion（daemon ${daemonVersion.ifBlank { "?" }}）。请重装配对的 Magisk ZIP 与 APK。"
            Status.ModuleStale ->
                "模块可能过旧 · 缺失 ${missingActions.size} 个动作：" +
                    missingActions.take(6).joinToString(", ") +
                    (if (missingActions.size > 6) "…" else "") +
                    "。请用同仓库构建的 ClawRuntime-magisk.zip 重刷模块。"
        }
    }

    companion object {
        fun evaluate(
            daemonVersion: String,
            protocolVersion: Int,
            actions: Collection<String>,
            checkedAtEpochMs: Long = System.currentTimeMillis()
        ): RuntimeCompatSnapshot {
            val reported = actions.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val expected = RuntimeActionCatalog.expectedActions
            val missing = if (reported.isEmpty()) {
                emptyList()
            } else {
                (expected - reported).sorted()
            }
            val status = when {
                protocolVersion > 0 &&
                    protocolVersion != RuntimeActionCatalog.EXPECTED_PROTOCOL_VERSION ->
                    Status.ProtocolMismatch
                reported.isNotEmpty() && missing.isNotEmpty() -> Status.ModuleStale
                protocolVersion == RuntimeActionCatalog.EXPECTED_PROTOCOL_VERSION ||
                    reported.isNotEmpty() -> Status.Ok
                else -> Status.Unknown
            }
            return RuntimeCompatSnapshot(
                daemonVersion = daemonVersion,
                protocolVersion = protocolVersion,
                reportedActions = reported,
                missingActions = missing,
                status = status,
                checkedAtEpochMs = checkedAtEpochMs
            )
        }
    }
}
