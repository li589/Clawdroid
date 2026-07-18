package com.clawdroid.app.tools

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * List / one-shot (or short sample) sensor reads via SensorManager.
 */
class SensorReadService(
    private val context: Context
) {
    fun list(): ClawToolCallResult {
        val manager = sensorManager()
            ?: return ClawToolCallResult(false, "失败: SensorManager 不可用", error = "sensor_unavailable")
        val arr = JSONArray()
        manager.getSensorList(Sensor.TYPE_ALL).forEach { sensor ->
            arr.put(
                JSONObject()
                    .put("name", sensor.name)
                    .put("vendor", sensor.vendor)
                    .put("type", sensor.type)
                    .put("type_name", typeName(sensor.type))
                    .put("max_range", sensor.maximumRange.toDouble())
                    .put("resolution", sensor.resolution.toDouble())
                    .put("power_ma", sensor.power.toDouble())
            )
        }
        val known = SUPPORTED.map { (alias, type) ->
            JSONObject()
                .put("alias", alias)
                .put("type", type)
                .put("available", manager.getDefaultSensor(type) != null)
        }
        val json = JSONObject()
            .put("count", arr.length())
            .put("supported_aliases", JSONArray(known))
            .put("sensors", arr)
        return ClawToolCallResult(success = true, output = json.toString(2))
    }

    fun read(
        typeAlias: String,
        durationMs: Int = 0,
        maxSamples: Int = 1
    ): ClawToolCallResult {
        val manager = sensorManager()
            ?: return ClawToolCallResult(false, "失败: SensorManager 不可用", error = "sensor_unavailable")
        val type = resolveType(typeAlias)
            ?: return ClawToolCallResult(
                false,
                "失败: 未知传感器类型 $typeAlias（支持: ${SUPPORTED.keys.joinToString()}）",
                error = "invalid_sensor_type"
            )
        val sensor = manager.getDefaultSensor(type)
            ?: return ClawToolCallResult(
                false,
                "失败: 设备无此传感器 ($typeAlias)",
                error = "sensor_not_present"
            )

        val samplesWanted = maxSamples.coerceIn(1, 32)
        val waitMs = when {
            durationMs > 0 -> durationMs.coerceIn(50, 2_000).toLong()
            else -> 1_500L
        }
        val samples = ArrayList<JSONObject>()
        val errorRef = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || event.sensor.type != type) return
                synchronized(samples) {
                    if (samples.size >= samplesWanted) return
                    samples += JSONObject()
                        .put("values", JSONArray(event.values.map { it.toDouble() }))
                        .put("accuracy", event.accuracy)
                        .put("timestamp_ns", event.timestamp)
                    if (samples.size >= samplesWanted) {
                        latch.countDown()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        return try {
            val ok = manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            if (!ok) {
                return ClawToolCallResult(false, "失败: 注册传感器监听失败", error = "sensor_register_failed")
            }
            latch.await(waitMs, TimeUnit.MILLISECONDS)
            manager.unregisterListener(listener)
            val err = errorRef.get()
            if (err != null) {
                return ClawToolCallResult(false, "失败: $err", error = err)
            }
            if (samples.isEmpty()) {
                return ClawToolCallResult(false, "失败: 采样超时无数据", error = "sensor_timeout")
            }
            val json = JSONObject()
                .put("alias", typeAlias.trim().lowercase())
                .put("type", type)
                .put("type_name", typeName(type))
                .put("name", sensor.name)
                .put("vendor", sensor.vendor)
                .put("sample_count", samples.size)
                .put("samples", JSONArray(samples))
                .put("latest", samples.last())
            ClawToolCallResult(success = true, output = json.toString(2))
        } catch (error: Exception) {
            runCatching { manager.unregisterListener(listener) }
            ClawToolCallResult(false, "失败: ${error.message}", error = error.message)
        }
    }

    private fun sensorManager(): SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private fun resolveType(alias: String): Int? {
        val key = alias.trim().lowercase()
        SUPPORTED[key]?.let { return it }
        return key.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun typeName(type: Int): String {
        return when (type) {
            Sensor.TYPE_ACCELEROMETER -> "accelerometer"
            Sensor.TYPE_GYROSCOPE -> "gyroscope"
            Sensor.TYPE_LIGHT -> "light"
            Sensor.TYPE_PROXIMITY -> "proximity"
            Sensor.TYPE_MAGNETIC_FIELD -> "magnetic_field"
            Sensor.TYPE_GRAVITY -> "gravity"
            Sensor.TYPE_LINEAR_ACCELERATION -> "linear_acceleration"
            Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
            Sensor.TYPE_PRESSURE -> "pressure"
            Sensor.TYPE_AMBIENT_TEMPERATURE -> "ambient_temperature"
            else -> "type_$type"
        }
    }

    companion object {
        val SUPPORTED: Map<String, Int> = linkedMapOf(
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "accel" to Sensor.TYPE_ACCELEROMETER,
            "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "gyro" to Sensor.TYPE_GYROSCOPE,
            "light" to Sensor.TYPE_LIGHT,
            "proximity" to Sensor.TYPE_PROXIMITY,
            "magnetic_field" to Sensor.TYPE_MAGNETIC_FIELD,
            "magnetic" to Sensor.TYPE_MAGNETIC_FIELD,
            "magnetometer" to Sensor.TYPE_MAGNETIC_FIELD
        )
    }
}
