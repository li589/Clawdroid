package com.clawdroid.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainToolsCatalogTest {
    @Test
    fun notification_web_shizuku_are_registered_and_available() {
        val ids = listOf(
            "notification_list",
            "web_preview",
            "web_search",
            "sandbox_shell",
            "camera_capture",
            "camera_record",
            "sensor_read",
            "ftp_transfer",
            "gpu_npu_probe",
            "shizuku_status",
            "shizuku_request",
            "shizuku_exec"
        )
        ids.forEach { id ->
            val tool = ClawTool.byToolId(id)
            assertNotNull(id, tool)
            val spec = ClawToolDefinitions.spec(tool!!)
            assertEquals(ToolAvailability.Available, spec.status)
        }
    }

    @Test
    fun notification_and_web_grants() {
        val notify = ClawToolDefinitions.spec(ClawTool.NOTIFICATION_LIST)
        assertTrue(ToolPermissionGrant.NOTIFICATION_ACCESS in notify.grants)
        val web = ClawToolDefinitions.spec(ClawTool.WEB_PREVIEW)
        assertTrue(ToolPermissionGrant.INTERNET in web.grants)
        val search = ClawToolDefinitions.spec(ClawTool.WEB_SEARCH)
        assertTrue(ToolPermissionGrant.INTERNET in search.grants)
        val sandbox = ClawToolDefinitions.spec(ClawTool.SANDBOX_SHELL)
        assertEquals(ToolPermissionTier.Basic, sandbox.tier)
        assertTrue(ToolPermissionGrant.SHELL_ALLOWLIST in sandbox.grants)
        val camera = ClawToolDefinitions.spec(ClawTool.CAMERA_CAPTURE)
        assertEquals(ToolPermissionTier.Basic, camera.tier)
        assertTrue(ToolPermissionGrant.CAMERA in camera.grants)
        val sensor = ClawToolDefinitions.spec(ClawTool.SENSOR_READ)
        assertEquals(ToolPermissionTier.Basic, sensor.tier)
        assertEquals(ToolRisk.Read, sensor.risk)
        val record = ClawToolDefinitions.spec(ClawTool.CAMERA_RECORD)
        assertTrue(ToolPermissionGrant.CAMERA in record.grants)
        val ftp = ClawToolDefinitions.spec(ClawTool.FTP_TRANSFER)
        assertTrue(ToolPermissionGrant.INTERNET in ftp.grants)
        val gpu = ClawToolDefinitions.spec(ClawTool.GPU_NPU_PROBE)
        assertEquals(ToolRisk.Read, gpu.risk)
        val exec = ClawToolDefinitions.spec(ClawTool.SHIZUKU_EXEC)
        assertEquals(ToolPermissionTier.AdbShizuku, exec.tier)
        assertTrue(ToolPermissionGrant.SHIZUKU in exec.grants)
    }

    @Test
    fun camera_and_sensor_schemas_expose_keys() {
        val cameraKeys = ClawToolCatalog.allowedArgumentKeys(ClawTool.CAMERA_CAPTURE)
        assertNotNull(cameraKeys)
        assertTrue(cameraKeys!!.contains("facing"))
        val sensorKeys = ClawToolCatalog.allowedArgumentKeys(ClawTool.SENSOR_READ)
        assertNotNull(sensorKeys)
        assertTrue(sensorKeys!!.contains("op"))
        assertTrue(sensorKeys.contains("type"))
    }
}
