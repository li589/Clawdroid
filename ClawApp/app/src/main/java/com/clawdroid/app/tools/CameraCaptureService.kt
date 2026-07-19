package com.clawdroid.app.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot still JPEG capture via Camera2 ImageReader (no preview UI / no video).
 */
class CameraCaptureService(
    private val context: Context
) {
    @SuppressLint("MissingPermission")
    fun capture(
        facing: String = "back",
        maxDimension: Int = 1280
    ): ClawToolCallResult {
        if (!hasCameraPermission()) {
            return ClawToolCallResult(
                success = false,
                output = "失败: 未授予 CAMERA 权限；请在系统应用设置中开启摄像头权限后重试",
                error = "camera_permission_denied"
            )
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ClawToolCallResult(false, "失败: CameraManager 不可用", error = "camera_unavailable")
        val cameraId = selectCameraId(manager, facing)
            ?: return ClawToolCallResult(false, "失败: 未找到可用摄像头", error = "camera_not_found")

        val thread = HandlerThread("claw-camera").also { it.start() }
        val handler = Handler(thread.looper)
        val errorRef = AtomicReference<String?>(null)
        val jpegRef = AtomicReference<ByteArray?>(null)
        val sizeRef = AtomicReference<Size?>(null)
        val done = CountDownLatch(1)

        var cameraDevice: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null

        try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return fail("camera_config_missing", "失败: 无法读取相机配置", thread)
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
                ?.takeIf { it.isNotEmpty() }
                ?: return fail("camera_no_jpeg", "失败: 相机不支持 JPEG", thread)
            val size = chooseSize(jpegSizes, maxDimension.coerceIn(320, 4096))
            sizeRef.set(size)
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).also { imageReader ->
                imageReader.setOnImageAvailableListener({ r ->
                    try {
                        r.acquireNextImage()?.use { image ->
                            val plane = image.planes.firstOrNull() ?: return@use
                            val buffer = plane.buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            jpegRef.set(bytes)
                        }
                    } catch (error: Exception) {
                        errorRef.compareAndSet(null, error.message ?: "image_read_failed")
                    } finally {
                        done.countDown()
                    }
                }, handler)
            }

            val openLatch = CountDownLatch(1)
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        openLatch.countDown()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        errorRef.compareAndSet(null, "camera_disconnected")
                        camera.close()
                        openLatch.countDown()
                        done.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        errorRef.compareAndSet(null, "camera_open_error_$error")
                        camera.close()
                        openLatch.countDown()
                        done.countDown()
                    }
                },
                handler
            )
            if (!openLatch.await(8, TimeUnit.SECONDS)) {
                return fail("camera_open_timeout", "失败: 打开摄像头超时", thread, cameraDevice, session, reader)
            }
            val device = cameraDevice
                ?: return fail(
                    errorRef.get() ?: "camera_open_failed",
                    "失败: 打开摄像头失败 (${errorRef.get()})",
                    thread,
                    cameraDevice,
                    session,
                    reader
                )

            val sessionLatch = CountDownLatch(1)
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(reader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        session = captureSession
                        sessionLatch.countDown()
                    }

                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        errorRef.compareAndSet(null, "camera_session_failed")
                        sessionLatch.countDown()
                        done.countDown()
                    }
                },
                handler
            )
            if (!sessionLatch.await(8, TimeUnit.SECONDS) || session == null) {
                return fail(
                    errorRef.get() ?: "camera_session_failed",
                    "失败: 创建拍照会话失败",
                    thread,
                    cameraDevice,
                    session,
                    reader
                )
            }

            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader!!.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(characteristics))
            }.build()

            session!!.capture(
                request,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure
                    ) {
                        errorRef.compareAndSet(null, "capture_failed_${failure.reason}")
                        done.countDown()
                    }

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        // ImageReader callback finishes the latch.
                    }
                },
                handler
            )

            if (!done.await(12, TimeUnit.SECONDS)) {
                return fail("camera_capture_timeout", "失败: 拍照超时", thread, cameraDevice, session, reader)
            }
            val err = errorRef.get()
            val jpeg = jpegRef.get()
            if (err != null || jpeg == null || jpeg.isEmpty()) {
                return fail(
                    err ?: "camera_empty_frame",
                    "失败: 未获取到图像 (${err ?: "empty"})",
                    thread,
                    cameraDevice,
                    session,
                    reader
                )
            }

            val outDir = File(context.cacheDir, "camera").also { it.mkdirs() }
            CacheDirPruner.prune(outDir)
            val outFile = File(outDir, "capture_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { it.write(jpeg) }
            val chosen = sizeRef.get() ?: size
            val json = JSONObject()
                .put("path", outFile.absolutePath)
                .put("size_bytes", outFile.length())
                .put("width", chosen.width)
                .put("height", chosen.height)
                .put("mime", "image/jpeg")
                .put("facing", normalizeFacing(facing))
                .put("camera_id", cameraId)
            return ClawToolCallResult(success = true, output = json.toString(2)).also {
                closeQuietly(cameraDevice, session, reader)
                thread.quitSafely()
            }
        } catch (security: SecurityException) {
            return fail(
                "camera_permission_denied",
                "失败: CAMERA 权限被拒绝",
                thread,
                cameraDevice,
                session,
                reader
            )
        } catch (error: Exception) {
            return fail(
                error.message ?: "camera_error",
                "失败: ${error.message}",
                thread,
                cameraDevice,
                session,
                reader
            )
        }
    }

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun selectCameraId(manager: CameraManager, facing: String): String? {
        val wantFront = normalizeFacing(facing) == "front"
        val want = if (wantFront) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        var fallback: String? = null
        for (id in manager.cameraIdList) {
            val facingVal = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facingVal == want) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    private fun chooseSize(sizes: Array<Size>, maxDimension: Int): Size {
        val sorted = sizes.sortedByDescending { it.width.toLong() * it.height.toLong() }
        return sorted.firstOrNull { size ->
            maxOf(size.width, size.height) <= maxDimension
        } ?: sorted.last()
    }

    private fun jpegOrientation(characteristics: CameraCharacteristics): Int {
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        return sensor
    }

    private fun normalizeFacing(facing: String): String {
        return when (facing.trim().lowercase()) {
            "front", "selfie", "1" -> "front"
            else -> "back"
        }
    }

    private fun fail(
        code: String,
        message: String,
        thread: HandlerThread,
        camera: CameraDevice? = null,
        session: CameraCaptureSession? = null,
        reader: ImageReader? = null
    ): ClawToolCallResult {
        closeQuietly(camera, session, reader)
        thread.quitSafely()
        return ClawToolCallResult(false, message, error = code)
    }

    private fun closeQuietly(
        camera: CameraDevice?,
        session: CameraCaptureSession?,
        reader: ImageReader?
    ) {
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { reader?.close() }
    }
}
