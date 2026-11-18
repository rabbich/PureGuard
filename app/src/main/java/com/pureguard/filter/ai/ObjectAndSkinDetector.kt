package com.pureguard.filter.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * Inspired by NetSpark's internal `detectAndCropObjects` + `skinThreshold` pipeline:
 * 1. Uses Google ML Kit ObjectDetector (`MULTIPLE_OBJECTS`) to find exact bounding boxes
 *    of people, thumbnails, or video frames on the screen.
 * 2. Computes `skinToneRatio` (YCbCr human skin color density) on cropped regions and full screen.
 */
class ObjectAndSkinDetector {

    private val options = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
        .enableMultipleObjects()
        .build()

    private val detector = ObjectDetection.getClient(options)

    suspend fun detectObjectCrops(fullBitmap: Bitmap): List<Bitmap> = suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromBitmap(fullBitmap, 0)
            detector.process(image)
                .addOnSuccessListener { detectedObjects ->
                    val crops = mutableListOf<Bitmap>()
                    val w = fullBitmap.width
                    val h = fullBitmap.height
                    for (obj in detectedObjects.take(5)) {
                        val box: Rect = obj.boundingBox
                        val left = box.left.coerceIn(0, w - 1)
                        val top = box.top.coerceIn(0, h - 1)
                        val right = box.right.coerceIn(left + 1, w)
                        val bottom = box.bottom.coerceIn(top + 1, h)
                        val boxW = right - left
                        val boxH = bottom - top
                        if (boxW >= 64 && boxH >= 64) {
                            try {
                                crops.add(Bitmap.createBitmap(fullBitmap, left, top, boxW, boxH))
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    if (cont.isActive) {
                        cont.resume(crops)
                    }
                }
                .addOnFailureListener {
                    if (cont.isActive) {
                        cont.resume(emptyList())
                    }
                }
        } catch (e: Throwable) {
            Log.d(TAG, "Object detection skipped: ${e.message}")
            if (cont.isActive) {
                cont.resume(emptyList())
            }
        }
    }

    /**
     * Calculates human skin-tone pixel ratio (0.0 to 1.0) using YCbCr + RGB rules
     * (NetSpark `skinThreshold` equivalent).
     * Very fast: samples a 48x48 grid across the bitmap (<1ms).
     */
    fun calculateSkinToneRatio(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 16 || h < 16) return 0f

        val stepX = max(1, w / 48)
        val stepY = max(1, h / 48)
        var skinPixels = 0
        var totalSampled = 0

        var y = (h * 0.08f).toInt()
        val maxY = (h * 0.90f).toInt()
        while (y < maxY) {
            var x = (w * 0.05f).toInt()
            val maxX = (w * 0.95f).toInt()
            while (x < maxX) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (isHumanSkinRgbYCbCr(r, g, b)) {
                    skinPixels++
                }
                totalSampled++
                x += stepX
            }
            y += stepY
        }

        return if (totalSampled > 0) skinPixels.toFloat() / totalSampled.toFloat() else 0f
    }

    private fun isHumanSkinRgbYCbCr(r: Int, g: Int, b: Int): Boolean {
        // Classic peer-reviewed RGB + YCbCr human skin cluster rule
        val rgbRule = r > 95 && g > 40 && b > 20 &&
            (max(r, max(g, b)) - min(r, min(g, b)) > 15) &&
            kotlin.math.abs(r - g) > 15 && r > g && r > b

        if (!rgbRule) return false

        val cb = 128 - (0.168736f * r) - (0.331264f * g) + (0.5f * b)
        val cr = 128 + (0.5f * r) - (0.418688f * g) - (0.081312f * b)
        return (cr in 135f..180f) && (cb in 85f..135f)
    }

    fun close() {
        try {
            detector.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "ObjectAndSkinDetector"
    }
}
