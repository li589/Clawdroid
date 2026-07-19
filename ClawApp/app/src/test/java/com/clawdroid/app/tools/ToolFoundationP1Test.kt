package com.clawdroid.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClawToolCatalogAllowedKeysTest {
    @Test
    fun emptySchemaTools_returnNullForPassthrough() {
        assertNull(ClawToolCatalog.allowedArgumentKeys(ClawTool.GET_CAPABILITIES))
        assertNull(ClawToolCatalog.allowedArgumentKeys(ClawTool.LIST_SKILLS))
        assertNull(ClawToolCatalog.allowedArgumentKeys(ClawTool.SAFE_TAP))
    }

    @Test
    fun injectTap_keysFromSchema() {
        val keys = ClawToolCatalog.allowedArgumentKeys(ClawTool.INJECT_TAP)!!
        assertTrue(keys.containsAll(listOf("x", "y", "display_id")))
        assertFalse(keys.contains("source"))
    }

    @Test
    fun runAgent_includesAliases() {
        val keys = ClawToolCatalog.allowedArgumentKeys(ClawTool.RUN_AGENT)!!
        assertTrue(keys.contains("agent_id"))
        assertTrue(keys.containsAll(listOf("agent", "id", "name")))
    }
}

class ToolExecutionPolicyTest {
    @Test
    fun readLocalTools_areNotSerialized() {
        assertFalse(ToolExecutionPolicy.requiresSerialization(ClawTool.LIST_TOOLS))
        assertFalse(ToolExecutionPolicy.requiresSerialization(ClawTool.FILE_READ))
        assertFalse(ToolExecutionPolicy.requiresSerialization(ClawTool.APP_LIST))
        assertFalse(ToolExecutionPolicy.requiresSerialization(ClawTool.NOTIFICATION_LIST))
        assertFalse(ToolExecutionPolicy.requiresSerialization(ClawTool.WEB_PREVIEW))
        assertFalse(ToolExecutionPolicy.requiresSerialization(ClawTool.WEB_SEARCH))
        assertFalse(ToolExecutionPolicy.requiresSerialization(ClawTool.SENSOR_READ))
        assertFalse(ToolExecutionPolicy.requiresSerialization(ClawTool.GPU_NPU_PROBE))
        assertFalse(ToolExecutionPolicy.requiresSerialization(ClawTool.DOWNLOAD_START))
        assertFalse(ToolExecutionPolicy.requiresSerialization(ClawTool.DOWNLOAD_STATUS))
        assertFalse(ToolExecutionPolicy.requiresSerialization(ClawTool.DOWNLOAD_VERIFY))
        assertFalse(ToolExecutionPolicy.requiresSerialization(ClawTool.SHIZUKU_STATUS))
    }

    @Test
    fun mutatingTools_areSerialized() {
        assertTrue(ToolExecutionPolicy.requiresSerialization(ClawTool.RUN_AGENT))
        assertTrue(ToolExecutionPolicy.requiresSerialization(ClawTool.CAPTURE_SCREEN))
        assertTrue(ToolExecutionPolicy.requiresSerialization(ClawTool.INJECT_TAP))
        assertTrue(ToolExecutionPolicy.requiresSerialization(ClawTool.FILE_WRITE))
        assertTrue(ToolExecutionPolicy.requiresSerialization(ClawTool.APP_STOP))
        assertTrue(ToolExecutionPolicy.requiresSerialization(ClawTool.SANDBOX_SHELL))
        assertTrue(ToolExecutionPolicy.requiresSerialization(ClawTool.CAMERA_CAPTURE))
        assertTrue(ToolExecutionPolicy.requiresSerialization(ClawTool.CAMERA_RECORD))
        assertTrue(ToolExecutionPolicy.requiresSerialization(ClawTool.FTP_TRANSFER))
        assertTrue(ToolExecutionPolicy.requiresSerialization(ClawTool.SUBSCRIBE_EVENTS))
        assertTrue(ToolExecutionPolicy.requiresSerialization(ClawTool.EXECUTE_SHELL_LIMITED))
    }

    @Test
    fun serializeLanes_splitAgentCaptureAndDevice() {
        assertEquals(ToolSerializeLane.Agent, ToolExecutionPolicy.serializeLane(ClawTool.RUN_AGENT))
        assertEquals(ToolSerializeLane.Capture, ToolExecutionPolicy.serializeLane(ClawTool.CAPTURE_SCREEN))
        assertEquals(ToolSerializeLane.Capture, ToolExecutionPolicy.serializeLane(ClawTool.CAMERA_CAPTURE))
        assertEquals(ToolSerializeLane.DeviceMutate, ToolExecutionPolicy.serializeLane(ClawTool.INJECT_TAP))
        assertEquals(ToolSerializeLane.DeviceMutate, ToolExecutionPolicy.serializeLane(ClawTool.TASK_SUBMIT))
        assertEquals(ToolSerializeLane.None, ToolExecutionPolicy.serializeLane(ClawTool.TASK_GET))
        assertEquals(ToolSerializeLane.None, ToolExecutionPolicy.serializeLane(ClawTool.FILE_READ))
    }

    @Test
    fun registryEmpty_defaults() {
        val empty = ToolServiceRegistry.EMPTY
        assertNull(empty.files)
        assertEquals(null, empty.assist)
    }
}
