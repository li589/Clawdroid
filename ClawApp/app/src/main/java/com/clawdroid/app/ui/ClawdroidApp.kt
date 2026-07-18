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
    val previewLimitBytes = 8 * 1024 * 1024
    val runtimeClient = remember(context) {
        ClawRuntimeClient(
            packageName = context.packageName,
            sharedSecret = RuntimeSecretStore.resolve(context.applicationContext),
            signatureDigest = ClawRuntimeClient.resolveSignatureDigest(context, context.packageName)
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
