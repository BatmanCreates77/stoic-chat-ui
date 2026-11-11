package com.screenmeter.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the chosen camera can do; shown on the home screen and attached to every measurement. */
data class CameraCapabilities(
    val cameraId: String,
    val supportsRaw: Boolean,
    val supportsManualSensor: Boolean,
    val exposureRangeNs: Range<Long>?,
    val isoRange: Range<Int>?,
    val cfaName: String,
    val whiteLevel: Int?,
    val hasColorMatrices: Boolean,
    val outputSize: Size,
    val outputFormat: Int,
) {
    val formatName: String get() = if (outputFormat == ImageFormat.RAW_SENSOR) "RAW_SENSOR" else "YUV_420_888 (fallback)"
}

class Capture(val image: Image, val result: TotalCaptureResult)

/**
 * Thin Camera2 wrapper: picks the back camera that advertises RAW + MANUAL_SENSOR when possible,
 * opens a session with a single ImageReader surface, and performs fully manual single captures.
 * There is no preview surface on purpose: with the lens pressed to the screen there is nothing to see.
 */
class CameraController(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("ScreenMeterCamera").apply { start() }
    private val handler = Handler(thread.looper)

    lateinit var characteristics: CameraCharacteristics
        private set
    lateinit var capabilities: CameraCapabilities
        private set

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    val isOpen: Boolean get() = session != null

    fun probe(): CameraCapabilities {
        val id = pickCameraId()
        val chars = manager.getCameraCharacteristics(id)
        characteristics = chars
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val raw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
        val manual = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val format = if (raw && map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.isNotEmpty() == true)
            ImageFormat.RAW_SENSOR else ImageFormat.YUV_420_888
        val sizes = map?.getOutputSizes(format) ?: arrayOf(Size(640, 480))
        val size = if (format == ImageFormat.RAW_SENSOR) sizes.maxByOrNull { it.width.toLong() * it.height }!!
        else sizes.filter { it.width <= 1920 }.maxByOrNull { it.width.toLong() * it.height } ?: sizes.first()
        val cfa = when (chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB"
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "MONO"
            else -> "unknown"
        }
        capabilities = CameraCapabilities(
            cameraId = id,
            supportsRaw = format == ImageFormat.RAW_SENSOR,
            supportsManualSensor = manual,
            exposureRangeNs = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            cfaName = cfa,
            whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
            hasColorMatrices = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) != null,
            outputSize = size,
            outputFormat = format,
        )
        return capabilities
    }

    private fun pickCameraId(): String {
        val ids = manager.cameraIdList
        fun score(id: String): Int {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return -1
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            var s = 1
            if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) s += 4
            if (caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) s += 2
            return s
        }
        return ids.maxByOrNull { score(it) } ?: ids.first()
    }

    @SuppressLint("MissingPermission")
    suspend fun open() {
        if (session != null) return
        if (!::capabilities.isInitialized) probe()
        val dev = suspendCancellableCoroutine<CameraDevice> { cont ->
            manager.openCamera(capabilities.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { cont.resume(camera) }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); if (cont.isActive) cont.resumeWithException(CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED)) }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); if (cont.isActive) cont.resumeWithException(IllegalStateException("Camera error $error")) }
            }, handler)
        }
        device = dev
        val rdr = ImageReader.newInstance(capabilities.outputSize.width, capabilities.outputSize.height, capabilities.outputFormat, 4)
        reader = rdr
        @Suppress("DEPRECATION")
        session = suspendCancellableCoroutine { cont ->
            dev.createCaptureSession(listOf(rdr.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { cont.resume(s) }
                override fun onConfigureFailed(s: CameraCaptureSession) { if (cont.isActive) cont.resumeWithException(IllegalStateException("Capture session configuration failed")) }
            }, handler)
        }
    }

    fun close() {
        session?.close(); session = null
        device?.close(); device = null
        reader?.close(); reader = null
    }

    fun buildManualRequest(exposureNs: Long, iso: Int): CaptureRequest {
        val dev = device ?: throw IllegalStateException("Camera not open")
        val rdr = reader ?: throw IllegalStateException("Reader missing")
        val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(rdr.surface)
        val caps = capabilities
        if (caps.supportsManualSensor) {
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs.coerceIn(caps.exposureRangeNs?.lower ?: exposureNs, caps.exposureRangeNs?.upper ?: exposureNs))
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(caps.isoRange?.lower ?: iso, caps.isoRange?.upper ?: iso))
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposureNs + 1_000_000L, 33_333_333L))
        } else {
            // Best effort: lock what we can so successive frames are at least consistent.
            b.set(CaptureRequest.CONTROL_AE_LOCK, true)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        }
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f) // infinity: the screen a few mm away stays blurred, averaging sub-pixels
        b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        return b.build()
    }

    /** One manual capture. The caller must close the returned image. */
    suspend fun capture(exposureNs: Long, iso: Int): Capture = withTimeout(8_000) {
        val s = session ?: throw IllegalStateException("Camera not open")
        val rdr = reader ?: throw IllegalStateException("Reader missing")
        val request = buildManualRequest(exposureNs, iso)
        suspendCancellableCoroutine { cont ->
            var image: Image? = null
            var result: TotalCaptureResult? = null
            fun deliver() {
                val i = image; val r = result
                if (i != null && r != null && cont.isActive) cont.resume(Capture(i, r))
            }
            rdr.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (image != null) image?.close()
                image = img
                deliver()
            }, handler)
            s.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(sess: CameraCaptureSession, req: CaptureRequest, res: TotalCaptureResult) {
                    result = res
                    deliver()
                }
                override fun onCaptureFailed(sess: CameraCaptureSession, req: CaptureRequest, failure: CaptureFailure) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("Capture failed (${failure.reason})"))
                }
            }, handler)
            cont.invokeOnCancellation { image?.close() }
        }
    }
}
