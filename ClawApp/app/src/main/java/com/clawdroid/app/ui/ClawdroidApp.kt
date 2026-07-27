package com.clawdroid.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.clawdroid.app.runtime.ClawRuntimeClient
import com.clawdroid.app.runtime.RuntimeSecretStore
import com.clawdroid.app.tools.ClawToolExecutor

@Composable
fun ClawdroidApp(debugSeedLongOverview: Boolean = false) {
    val context = LocalContext.current
    // 1MB 对 1800x2880 PNG 截图过小，readLatestCapture 会直接失败而非降级。
    // 与 DebugRuntimeBridge* 的 DEFAULT_PREVIEW_LIMIT_BYTES=8MB 保持一致。
    val previewLimitBytes = 8 * 1024 * 1024
    val runtimeClient = remember(context) {
        val appCtx = context.applicationContext
        val secret = runCatching { RuntimeSecretStore.resolve(appCtx) }.getOrDefault("")
        val digest = runCatching {
            ClawRuntimeClient.resolveSignatureDigest(context, context.packageName)
        }.getOrDefault("")
        ClawRuntimeClient(
            packageName = context.packageName,
            sharedSecret = secret,
            signatureDigest = digest
        )
    }
    val toolExecutor = remember(runtimeClient) { ClawToolExecutor(runtimeClient) }

    ClawdroidShell(
        runtimeClient = runtimeClient,
        toolExecutor = toolExecutor,
        previewLimitBytes = previewLimitBytes,
        debugSeedLongOverview = debugSeedLongOverview
    )
}
