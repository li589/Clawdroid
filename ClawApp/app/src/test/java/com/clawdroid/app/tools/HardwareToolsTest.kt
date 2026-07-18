package com.clawdroid.app.tools

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareToolsTest {
    @Test
    fun sensor_aliases_cover_core_types() {
        assertEquals(Sensor.TYPE_ACCELEROMETER, SensorReadService.SUPPORTED["accelerometer"])
        assertEquals(Sensor.TYPE_ACCELEROMETER, SensorReadService.SUPPORTED["accel"])
        assertEquals(Sensor.TYPE_GYROSCOPE, SensorReadService.SUPPORTED["gyro"])
        assertEquals(Sensor.TYPE_LIGHT, SensorReadService.SUPPORTED["light"])
        assertEquals(Sensor.TYPE_PROXIMITY, SensorReadService.SUPPORTED["proximity"])
        assertEquals(Sensor.TYPE_MAGNETIC_FIELD, SensorReadService.SUPPORTED["magnetic"])
    }

    @Test
    fun hardware_tools_registered() {
        assertNotNull(ClawTool.byToolId("camera_capture"))
        assertNotNull(ClawTool.byToolId("camera_record"))
        assertNotNull(ClawTool.byToolId("sensor_read"))
        assertNotNull(ClawTool.byToolId("gpu_npu_probe"))
        assertEquals(ToolRisk.Write, ClawToolDefinitions.spec(ClawTool.CAMERA_CAPTURE).risk)
        assertEquals(ToolRisk.Write, ClawToolDefinitions.spec(ClawTool.CAMERA_RECORD).risk)
        assertEquals(ToolRisk.Read, ClawToolDefinitions.spec(ClawTool.SENSOR_READ).risk)
        assertEquals(ToolRisk.Read, ClawToolDefinitions.spec(ClawTool.GPU_NPU_PROBE).risk)
        assertTrue(ToolExecutionPolicy.requiresSerialization(ClawTool.CAMERA_CAPTURE))
        assertTrue(ToolExecutionPolicy.requiresSerialization(ClawTool.CAMERA_RECORD))
        assertTrue(!ToolExecutionPolicy.requiresSerialization(ClawTool.SENSOR_READ))
        assertTrue(!ToolExecutionPolicy.requiresSerialization(ClawTool.GPU_NPU_PROBE))
    }

    @Test
    fun camera_record_schema_has_duration() {
        val keys = ClawToolCatalog.allowedArgumentKeys(ClawTool.CAMERA_RECORD)
        assertNotNull(keys)
        assertTrue(keys!!.contains("duration_ms"))
        assertTrue(keys.contains("facing"))
    }
}
