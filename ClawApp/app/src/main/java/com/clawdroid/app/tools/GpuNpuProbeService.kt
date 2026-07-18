package com.clawdroid.app.tools

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only GLES / Vulkan / NNAPI capability inventory (no compute kernels).
 */
class GpuNpuProbeService(
    private val context: Context
) {
    fun probe(): ClawToolCallResult {
        val json = JSONObject()
            .put("gles", probeGles())
            .put("vulkan", probeVulkan())
            .put("nnapi", probeNnapi())
            .put("activity_manager", probeActivityManager())
        return ClawToolCallResult(success = true, output = json.toString(2))
    }

    private fun probeActivityManager(): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = am?.deviceConfigurationInfo
        return JSONObject()
            .put("req_gl_es_version", info?.glEsVersion ?: JSONObject.NULL)
            .put("req_gl_es_version_int", info?.reqGlEsVersion ?: 0)
            .put("is_low_ram", am?.isLowRamDevice ?: false)
    }

    private fun probeVulkan(): JSONObject {
        val pm = context.packageManager
        return JSONObject()
            .put(
                "hardware_level",
                pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
            )
            .put(
                "hardware_compute",
                pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE)
            )
            .put(
                "hardware_version",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
                } else {
                    false
                }
            )
            .put(
                "android_software_vulkan_deqp",
                pm.hasSystemFeature("android.software.vulkan.deqp.level")
            )
    }

    private fun probeNnapi(): JSONObject {
        val out = JSONObject()
            .put("api_level_ok", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
            .put("enumerable", false)
            .put("devices", JSONArray())
            .put(
                "note",
                "P0 reports SDK gate only; device enumeration needs NDK ANeuralNetworks"
            )
        // Best-effort reflection for OEM-exposed manager classes (may be absent).
        val deviceNames = JSONArray()
        runCatching {
            val clazz = Class.forName("android.os.Device")
            val getAll = clazz.methods.firstOrNull {
                it.name == "getAll" && it.parameterCount == 0
            }
            @Suppress("UNCHECKED_CAST")
            val list = getAll?.invoke(null) as? List<*>
            list?.forEach { item ->
                val name = runCatching {
                    item?.javaClass?.getMethod("getName")?.invoke(item)?.toString()
                }.getOrNull()
                if (!name.isNullOrBlank()) deviceNames.put(name)
            }
        }
        if (deviceNames.length() > 0) {
            out.put("enumerable", true)
            out.put("devices", deviceNames)
            out.put("note", "reflected android.os.Device.getAll()")
        }
        return out
    }

    private fun probeGles(): JSONObject {
        val out = JSONObject()
            .put("ok", false)
        var display = EGL14.EGL_NO_DISPLAY
        var contextEgl = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                return out.put("error", "egl_no_display")
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return out.put("error", "egl_init_failed")
            }
            val configAttrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, numConfig, 0) ||
                numConfig[0] == 0
            ) {
                return out.put("error", "egl_no_config")
            }
            val config = configs[0]
            val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            contextEgl = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                contextAttrs,
                0
            )
            if (contextEgl == EGL14.EGL_NO_CONTEXT) {
                return out.put("error", "egl_no_context")
            }
            val pbufferAttrs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            surface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttrs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                return out.put("error", "egl_no_surface")
            }
            if (!EGL14.eglMakeCurrent(display, surface, surface, contextEgl)) {
                return out.put("error", "egl_make_current_failed")
            }
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
            out.put("ok", true)
                .put("vendor", GLES20.glGetString(GLES20.GL_VENDOR).orEmpty())
                .put("renderer", GLES20.glGetString(GLES20.GL_RENDERER).orEmpty())
                .put("version", GLES20.glGetString(GLES20.GL_VERSION).orEmpty())
                .put("extensions_count", extensions.split(" ").filter { it.isNotBlank() }.size)
                .put("extensions_sample", extensions.take(400))
                .put("egl_version", "${version[0]}.${version[1]}")
        } catch (error: Exception) {
            out.put("error", error.message ?: "gles_probe_failed")
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                if (surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface)
                }
                if (contextEgl != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, contextEgl)
                }
                EGL14.eglTerminate(display)
            }
        }
        return out
    }
}
