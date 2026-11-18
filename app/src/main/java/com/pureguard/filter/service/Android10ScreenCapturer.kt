package com.pureguard.filter.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * Provides zero-disk, in-memory screen capture for Android 10 (API 29),
 * where AccessibilityService.takeScreenshot (API 30+) is not available.
 */
object Android10ScreenCapturer {
    private const val TAG = "Android10Capturer"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 720
    private var screenHeight = 1280
    private var screenDensity = 320

    val isReady: Boolean
        get() = mediaProjection != null && imageReader != null

    @SuppressLint("WrongConstant")
    fun initialize(context: Context, resultCode: Int, data: Intent) {
        try {
            stop()
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)

            // Downscale by 2x in RAM for ultra-fast, low-battery processing (e.g. 540x960)
            screenWidth = (metrics.widthPixels / 2).coerceAtLeast(360)
            screenHeight = (metrics.heightPixels / 2).coerceAtLeast(640)
            screenDensity = metrics.densityDpi

            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data) ?: return

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stop()
                }
            }, Handler(Looper.getMainLooper()))

            val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            val vDisplay = projection.createVirtualDisplay(
                "PureGuardRamCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )

            mediaProjection = projection
            imageReader = reader
            virtualDisplay = vDisplay
            Log.i(TAG, "Android 10 RAM Screen Capturer initialized ($screenWidth x $screenHeight)")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Android 10 Screen Capturer: ${e.message}")
        }
    }

    fun captureLatestBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return null

        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val paddedWidth = screenWidth + (rowPadding / pixelStride)
            val tempBitmap = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            tempBitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) {
                tempBitmap
            } else {
                val cropped = Bitmap.createBitmap(tempBitmap, 0, 0, screenWidth, screenHeight)
                tempBitmap.recycle()
                cropped
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to extract bitmap from ImageReader: ${e.message}")
            null
        } finally {
            try {
                image.close()
            } catch (_: Throwable) {
            }
        }
    }

    fun stop() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        try {
            imageReader?.close()
        } catch (_: Throwable) {
        }
        try {
            mediaProjection?.stop()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }
}
