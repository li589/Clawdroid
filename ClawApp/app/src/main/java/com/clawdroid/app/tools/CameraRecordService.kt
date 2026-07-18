package com.clawdroid.app.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
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
 * Short muted MP4 clip via Camera2 + MediaRecorder, plus a JPEG preview frame.
 * No live Compose Surface preview.
 */
class CameraRecordService(
    private val context: Context
) {
    @SuppressLint("MissingPermission")
    fun record(
        facing: String = "back",
        durationMs: Int = 3_000,
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

        val clampedDuration = durationMs.coerceIn(500, 15_000)
        val thread = HandlerThread("claw-camera-record").also { it.start() }
        val handler = Handler(thread.looper)
        val errorRef = AtomicReference<String?>(null)

        var cameraDevice: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var recorder: MediaRecorder? = null
        var outFile: File? = null

        try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return fail("camera_config_missing", "失败: 无法读取相机配置", thread)
            val videoSizes = map.getOutputSizes(MediaRecorder::class.java)
                ?.takeIf { it.isNotEmpty() }
                ?: return fail("camera_no_video", "失败: 相机不支持录像尺寸", thread)
            val size = chooseSize(videoSizes, maxDimension.coerceIn(320, 1920))

            val outDir = File(context.cacheDir, "camera").also { it.mkdirs() }
            outFile = File(outDir, "record_${System.currentTimeMillis()}.mp4")

            recorder = createRecorder(size, outFile!!).also { it.prepare() }
            val recorderSurface = recorder!!.surface

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
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        errorRef.compareAndSet(null, "camera_open_error_$error")
                        camera.close()
                        openLatch.countDown()
                    }
                },
                handler
            )
            if (!openLatch.await(8, TimeUnit.SECONDS)) {
                return fail("camera_open_timeout", "失败: 打开摄像头超时", thread, cameraDevice, session, recorder)
            }
            val device = cameraDevice
                ?: return fail(
                    errorRef.get() ?: "camera_open_failed",
                    "失败: 打开摄像头失败 (${errorRef.get()})",
                    thread,
                    cameraDevice,
                    session,
                    recorder
                )

            val sessionLatch = CountDownLatch(1)
            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf(recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(captureSession: CameraCaptureSession) {
                        session = captureSession
                        sessionLatch.countDown()
                    }

                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                        errorRef.compareAndSet(null, "camera_session_failed")
                        sessionLatch.countDown()
                    }
                },
                handler
            )
            if (!sessionLatch.await(8, TimeUnit.SECONDS) || session == null) {
                return fail(
                    errorRef.get() ?: "camera_session_failed",
                    "失败: 创建录像会话失败",
                    thread,
                    cameraDevice,
                    session,
                    recorder
                )
            }

            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(recorderSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }.build()
            session!!.setRepeatingRequest(request, null, handler)

            recorder!!.start()
            Thread.sleep(clampedDuration.toLong())
            runCatching { recorder!!.stop() }
                .onFailure { errorRef.compareAndSet(null, it.message ?: "recorder_stop_failed") }

            closeQuietly(cameraDevice, session, recorder)
            cameraDevice = null
            session = null
            recorder = null
            thread.quitSafely()

            val err = errorRef.get()
            val file = outFile!!
            if (!file.exists() || file.length() < 32L) {
                runCatching { file.delete() }
                return ClawToolCallResult(
                    false,
                    "失败: 录像文件无效 (${err ?: "empty"})",
                    error = err ?: "camera_record_empty"
                )
            }

            val previewPath = extractPreviewFrame(file, outDir)
            val json = JSONObject()
                .put("path", file.absolutePath)
                .put("preview_path", previewPath ?: JSONObject.NULL)
                .put("size_bytes", file.length())
                .put("duration_ms", clampedDuration)
                .put("width", size.width)
                .put("height", size.height)
                .put("mime", "video/mp4")
                .put("facing", normalizeFacing(facing))
                .put("camera_id", cameraId)
                .put("muted", true)
            return ClawToolCallResult(success = true, output = json.toString(2))
        } catch (security: SecurityException) {
            return fail(
                "camera_permission_denied",
                "失败: CAMERA 权限被拒绝",
                thread,
                cameraDevice,
                session,
                recorder
            )
        } catch (error: Exception) {
            return fail(
                error.message ?: "camera_record_error",
                "失败: ${error.message}",
                thread,
                cameraDevice,
                session,
                recorder
            )
        }
    }

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createRecorder(size: Size, outFile: File): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setVideoSize(size.width, size.height)
        recorder.setVideoFrameRate(30)
        recorder.setVideoEncodingBitRate((size.width * size.height * 4).coerceIn(500_000, 8_000_000))
        recorder.setOutputFile(outFile.absolutePath)
        return recorder
    }

    private fun extractPreviewFrame(video: File, outDir: File): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.absolutePath)
            val bitmap = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val preview = File(outDir, "preview_${System.currentTimeMillis()}.jpg")
            FileOutputStream(preview).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
            preview.absolutePath
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
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
        recorder: MediaRecorder? = null
    ): ClawToolCallResult {
        closeQuietly(camera, session, recorder)
        thread.quitSafely()
        return ClawToolCallResult(false, message, error = code)
    }

    private fun closeQuietly(
        camera: CameraDevice?,
        session: CameraCaptureSession?,
        recorder: MediaRecorder?
    ) {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching {
            recorder?.reset()
            recorder?.release()
        }
    }
}
