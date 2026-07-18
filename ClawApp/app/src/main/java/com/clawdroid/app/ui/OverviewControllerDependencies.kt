package com.clawdroid.app.ui

import android.content.Context
import com.clawdroid.app.env.AppPermissionManager
import com.clawdroid.app.env.LocalEnvironmentProbe
import com.clawdroid.app.env.LocalEnvironmentStatus
import com.clawdroid.app.env.RootActionResult
import com.clawdroid.app.env.StartupPermissionState
import com.clawdroid.app.env.StartupPermissionStore

internal interface OverviewEnvironmentGateway {
    fun loadStartupPermissionState(): StartupPermissionState
    fun markRootPromptResult(granted: Boolean)
    fun rememberNotificationGrant()
    fun rememberWriteSettingsGrant()
    fun rememberAllFilesGrant()
    fun rememberAccessibilityGrant()
    fun rememberAutomationGrant()
    fun rememberGrantedPermissions(status: LocalEnvironmentStatus)
    suspend fun probeLocalEnvironment(includeRootCheck: Boolean): LocalEnvironmentStatus
    suspend fun requestRootAccess(): RootActionResult
    suspend fun grantNotificationViaRoot(): RootActionResult
    suspend fun grantWriteSettingsViaRoot(): RootActionResult
    suspend fun grantAllFilesAccessViaRoot(): RootActionResult
    suspend fun enableAccessibilityViaRoot(): RootActionResult
    suspend fun grantAutomationPermissionsViaRoot(): RootActionResult
    suspend fun chmodPathViaRoot(path: String, mode: String): RootActionResult
    suspend fun chownPathViaRoot(path: String, ownerSpec: String): RootActionResult
}

internal class DefaultOverviewEnvironmentGateway(
    private val appContext: Context
) : OverviewEnvironmentGateway {
    override fun loadStartupPermissionState(): StartupPermissionState {
        return StartupPermissionStore.load(appContext)
    }

    override fun markRootPromptResult(granted: Boolean) {
        StartupPermissionStore.markRootPromptResult(appContext, granted)
    }

    override fun rememberNotificationGrant() {
        StartupPermissionStore.rememberNotificationGrant(appContext)
    }

    override fun rememberWriteSettingsGrant() {
        StartupPermissionStore.rememberWriteSettingsGrant(appContext)
    }

    override fun rememberAllFilesGrant() {
        StartupPermissionStore.rememberAllFilesGrant(appContext)
    }

    override fun rememberAccessibilityGrant() {
        StartupPermissionStore.rememberAccessibilityGrant(appContext)
    }

    override fun rememberAutomationGrant() {
        StartupPermissionStore.rememberAutomationGrant(appContext)
    }

    override fun rememberGrantedPermissions(status: LocalEnvironmentStatus) {
        StartupPermissionStore.rememberGrantedPermissions(appContext, status)
    }

    override suspend fun probeLocalEnvironment(includeRootCheck: Boolean): LocalEnvironmentStatus {
        return LocalEnvironmentProbe.probe(
            context = appContext,
            includeRootCheck = includeRootCheck
        )
    }

    override suspend fun requestRootAccess(): RootActionResult = AppPermissionManager.requestRootAccess()

    override suspend fun grantNotificationViaRoot(): RootActionResult {
        return AppPermissionManager.grantNotificationViaRoot(appContext)
    }

    override suspend fun grantWriteSettingsViaRoot(): RootActionResult {
        return AppPermissionManager.grantWriteSettingsViaRoot(appContext)
    }

    override suspend fun grantAllFilesAccessViaRoot(): RootActionResult {
        return AppPermissionManager.grantAllFilesAccessViaRoot(appContext)
    }

    override suspend fun enableAccessibilityViaRoot(): RootActionResult {
        return AppPermissionManager.enableAccessibilityViaRoot(appContext)
    }

    override suspend fun grantAutomationPermissionsViaRoot(): RootActionResult {
        return AppPermissionManager.grantAutomationPermissionsViaRoot(appContext)
    }

    override suspend fun chmodPathViaRoot(path: String, mode: String): RootActionResult {
        return AppPermissionManager.chmodPathViaRoot(path = path, mode = mode)
    }

    override suspend fun chownPathViaRoot(path: String, ownerSpec: String): RootActionResult {
        return AppPermissionManager.chownPathViaRoot(path = path, ownerSpec = ownerSpec)
    }
}

internal interface OverviewMetricsSampler {
    suspend fun sample(rootAvailable: Boolean): DashboardRuntimeMetrics
}

internal object DefaultOverviewMetricsSampler : OverviewMetricsSampler {
    override suspend fun sample(rootAvailable: Boolean): DashboardRuntimeMetrics {
        return DashboardMetricsCollector.sample(rootAvailable = rootAvailable)
    }
}
