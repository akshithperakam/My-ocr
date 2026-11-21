package com.codecapture.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages CameraX Preview, high-resolution ImageCapture, and low-resolution ImageAnalysis
 * for real-time verification frames during closed-loop scrolling (FR-03, NFR-02).
 */
class CameraCaptureManager(
    private val context: Context
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private val latestPreviewBitmap = AtomicReference<Bitmap?>(null)

    fun bindToLifecycle(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        jpegQuality: Int = 94,
        onError: (String) -> Unit = {}
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val captureResolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(jpegQuality.coerceIn(75, 100))
                    .setResolutionSelector(captureResolutionSelector)
                    .build()
                imageCapture = capture

                val analysisResolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(960, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(analysisResolutionSelector)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    try {
                        val bmp = imageProxyToRotatedBitmap(imageProxy)
                        latestPreviewBitmap.getAndSet(bmp)
                    } catch (e: Exception) {
                        Log.w(TAG, "Frame conversion error", e)
                    } finally {
                        imageProxy.close()
                    }
                }

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind CameraX use cases", e)
                onError("Unable to initialize camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun getLatestVerificationBitmap(): Bitmap? {
        val current = latestPreviewBitmap.get() ?: return null
        return current.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun triggerCenterAutofocus() {
        val cam = camera ?: return
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(0.5f, 0.5f)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun setExposureCompensation(index: Int) {
        val cam = camera ?: return
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        val clamped = index.coerceIn(range.lower, range.upper)
        cam.cameraControl.setExposureCompensationIndex(clamped)
    }

    fun getExposureRange(): IntRange {
        val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange ?: return 0..0
        return range.lower..range.upper
    }

    suspend fun captureHighResPhoto(outputFile: File): File = suspendCancellableCoroutine { cont ->
        val capture = imageCapture
        if (capture == null) {
            cont.resumeWithException(IllegalStateException("Camera is not initialized"))
            return@suspendCancellableCoroutine
        }
        outputFile.parentFile?.mkdirs()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (cont.isActive) cont.resume(outputFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            }
        )
    }

    fun decodeOrientedBitmap(imageFile: File, maxDimension: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > maxDimension) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val raw = BitmapFactory.decodeFile(imageFile.absolutePath, decodeOpts) ?: return null

        val exif = runCatching { ExifInterface(imageFile.absolutePath) }.getOrNull()
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        ) ?: ExifInterface.ORIENTATION_NORMAL

        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return raw

        val matrix = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    fun release() {
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        latestPreviewBitmap.set(null)
    }

    private fun imageProxyToRotatedBitmap(imageProxy: ImageProxy): Bitmap {
        val bitmap = imageProxy.toBitmap()
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    companion object {
        private const val TAG = "CameraCaptureManager"
    }
}
