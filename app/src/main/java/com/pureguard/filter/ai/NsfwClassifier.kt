package com.pureguard.filter.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

data class NsfwResult(
    val drawing: Float,
    val hentai: Float,
    val neutral: Float,
    val porn: Float,
    val sexy: Float,
    val inferenceTimeMs: Long,
    val isModelLoaded: Boolean
) {
    fun isViolation(pornThresh: Float, sexyThresh: Float, hentaiThresh: Float): Boolean {
        if (!isModelLoaded) return false
        // Combined risk score: when both Porn + Sexy are moderately high (e.g. thong/bikini/kissing)
        val combinedAdultScore = porn + (sexy * 0.85f)
        val combinedThreshold = sexyThresh * 1.05f
        return porn >= pornThresh ||
            sexy >= sexyThresh ||
            hentai >= hentaiThresh ||
            combinedAdultScore >= combinedThreshold
    }

    fun formatReason(pornThresh: Float, sexyThresh: Float, hentaiThresh: Float): String {
        val pornPct = (porn * 100).toInt()
        val sexyPct = (sexy * 100).toInt()
        val hentaiPct = (hentai * 100).toInt()
        return when {
            porn >= pornThresh -> "נחסם עקב זיהוי תמונה לא ראויה (Porn: $pornPct%, Sexy: $sexyPct%)"
            sexy >= sexyThresh -> "נחסם עקב זיהוי תמונה חשופה (Sexy: $sexyPct%, Porn: $pornPct%)"
            hentai >= hentaiThresh -> "נחסם עקב זיהוי איור לא ראוי (Hentai: $hentaiPct%)"
            else -> "נחסם עקב זיהוי תוכן ויזואלי חשוף (Sexy: $sexyPct%, Porn: $pornPct%)"
        }
    }
}

class NsfwClassifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var inputDataType: DataType = DataType.FLOAT32
    var isAvailable: Boolean = false
        private set

    init {
        loadModelGracefully()
    }

    @Synchronized
    private fun loadModelGracefully() {
        try {
            val assetList = context.assets.list("") ?: emptyArray()
            if (!assetList.contains(MODEL_FILE)) {
                Log.w(TAG, "Model file $MODEL_FILE not found in assets yet. Running in text-only fallback mode.")
                isAvailable = false
                return
            }

            val modelBuffer = FileUtil.loadMappedFile(context, MODEL_FILE)
            if (modelBuffer.capacity() < 1024) {
                Log.w(TAG, "Model file $MODEL_FILE is a tiny placeholder (<1KB). Skipping TFLite initialization.")
                isAvailable = false
                return
            }

            val options = Interpreter.Options().apply {
                setNumThreads(3)
            }
            val tflite = Interpreter(modelBuffer, options)
            inputDataType = tflite.getInputTensor(0).dataType()
            interpreter = tflite
            isAvailable = true
            Log.i(TAG, "NSFW TFLite model loaded successfully (Size: ${modelBuffer.capacity()} bytes, Input type: $inputDataType)")
        } catch (e: Throwable) {
            Log.e(TAG, "Graceful fallback: Failed to load $MODEL_FILE: ${e.message}")
            interpreter = null
            isAvailable = false
        }
    }

    /**
     * Multi-Zone Smart Scanner:
     * A full tall phone screenshot (e.g. 1080x2400) squished to 224x224 dilutes a YouTube video player
     * or an image of a girl in a thong/bikini with surrounding white/dark UI chrome.
     *
     * We evaluate up to 3 square crops in RAM:
     * 1. Top video/hero zone (where YouTube plays videos and top search images appear)
     * 2. Center screen zone (where main feed images/reels appear)
     * 3. Full screen overview
     * And return the maximum Porn/Sexy/Hentai scores across zones.
     */
    @Synchronized
    fun classifyFullScreenBitmap(
        fullScreenSoftwareBitmap: Bitmap,
        pornThresh: Float,
        sexyThresh: Float,
        hentaiThresh: Float
    ): NsfwResult {
        val currentInterpreter = interpreter
        if (!isAvailable || currentInterpreter == null) {
            return NsfwResult(0f, 0f, 1f, 0f, 0f, 0L, isModelLoaded = false)
        }

        val startTime = SystemClock.elapsedRealtime()
        val width = fullScreenSoftwareBitmap.width
        val height = fullScreenSoftwareBitmap.height

        var maxDrawing = 0f
        var maxHentai = 0f
        var maxNeutral = 1f
        var maxPorn = 0f
        var maxSexy = 0f

        val cropsToTest = mutableListOf<Bitmap>()
        try {
            if (height > width * 1.25f) {
                val cropSize = width
                // Zone 1: Top Video Player / Header Image Zone (skip top status bar ~6% of height)
                val topY = (height * 0.06f).toInt().coerceAtMost(height - cropSize)
                cropsToTest.add(Bitmap.createBitmap(fullScreenSoftwareBitmap, 0, topY, cropSize, cropSize))

                // Zone 2: Center Screen Zone
                val centerY = ((height - cropSize) / 2).coerceAtLeast(0)
                cropsToTest.add(Bitmap.createBitmap(fullScreenSoftwareBitmap, 0, centerY, cropSize, cropSize))

                // Zone 3: Lower-Middle Feed Zone
                val lowerY = (height * 0.48f).toInt().coerceAtMost(height - cropSize)
                if (lowerY != centerY) {
                    cropsToTest.add(Bitmap.createBitmap(fullScreenSoftwareBitmap, 0, lowerY, cropSize, cropSize))
                }
            } else {
                // Landscape (e.g. fullscreen YouTube video): test center square + full frame
                val cropSize = height
                val centerX = ((width - cropSize) / 2).coerceAtLeast(0)
                cropsToTest.add(Bitmap.createBitmap(fullScreenSoftwareBitmap, centerX, 0, cropSize, cropSize))
            }

            for (crop in cropsToTest) {
                val scores = runSingleInference(currentInterpreter, crop)
                maxDrawing = max(maxDrawing, scores[0])
                maxHentai = max(maxHentai, scores[1])
                maxNeutral = scores[2]
                maxPorn = max(maxPorn, scores[3])
                maxSexy = max(maxSexy, scores[4])

                // Early exit as soon as a zone violates thresholds (saves CPU & battery!)
                val currentRes = NsfwResult(
                    drawing = maxDrawing,
                    hentai = maxHentai,
                    neutral = maxNeutral,
                    porn = maxPorn,
                    sexy = maxSexy,
                    inferenceTimeMs = SystemClock.elapsedRealtime() - startTime,
                    isModelLoaded = true
                )
                if (currentRes.isViolation(pornThresh, sexyThresh, hentaiThresh)) {
                    return currentRes
                }
            }

            val elapsed = SystemClock.elapsedRealtime() - startTime
            return NsfwResult(
                drawing = maxDrawing,
                hentai = maxHentai,
                neutral = maxNeutral,
                porn = maxPorn,
                sexy = maxSexy,
                inferenceTimeMs = elapsed,
                isModelLoaded = true
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error during multi-zone TFLite inference: ${e.message}")
            val elapsed = SystemClock.elapsedRealtime() - startTime
            return NsfwResult(0f, 0f, 1f, 0f, 0f, elapsed, isModelLoaded = false)
        } finally {
            for (crop in cropsToTest) {
                try {
                    if (crop != fullScreenSoftwareBitmap && !crop.isRecycled) {
                        crop.recycle()
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun runSingleInference(currentInterpreter: Interpreter, bitmap: Bitmap): FloatArray {
        val imageProcessorBuilder = ImageProcessor.Builder()
            .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))

        if (inputDataType == DataType.FLOAT32) {
            imageProcessorBuilder.add(NormalizeOp(0f, 255f))
        }

        val imageProcessor = imageProcessorBuilder.build()
        var tensorImage = TensorImage(inputDataType)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        val outputTensor = currentInterpreter.getOutputTensor(0)
        val outputType = outputTensor.dataType()

        val probs = FloatArray(5)
        if (outputType == DataType.UINT8) {
            val outBuffer = ByteBuffer.allocateDirect(5).order(ByteOrder.nativeOrder())
            currentInterpreter.run(tensorImage.buffer, outBuffer)
            outBuffer.rewind()
            for (i in 0 until 5) {
                probs[i] = (outBuffer.get().toInt() and 0xFF) / 255.0f
            }
        } else {
            val outputArray = Array(1) { FloatArray(5) }
            currentInterpreter.run(tensorImage.buffer, outputArray)
            for (i in 0 until 5) {
                probs[i] = outputArray[0][i]
            }
        }
        return probs
    }

    @Synchronized
    fun close() {
        try {
            interpreter?.close()
        } catch (_: Throwable) {
        } finally {
            interpreter = null
            isAvailable = false
        }
    }

    companion object {
        private const val TAG = "NsfwClassifier"
        const val MODEL_FILE = "nsfw.tflite"
        const val INPUT_SIZE = 224
    }
}
