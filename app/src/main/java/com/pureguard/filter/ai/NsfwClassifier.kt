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
    val skinRatio: Float = 0f,
    val inferenceTimeMs: Long,
    val isModelLoaded: Boolean
) {
    fun isViolation(pornThresh: Float, sexyThresh: Float, hentaiThresh: Float): Boolean {
        if (!isModelLoaded) return false
        val combinedAdultScore = porn + (sexy * 0.90f)
        // NetSpark-style fusion: if skinRatio is very high (>42% of frame is exposed skin/close-up)
        // and the AI sees even moderate Sexy/Porn signal, trigger block!
        val skinBoostedScore = if (skinRatio >= 0.42f) {
            porn + sexy + (skinRatio * 0.35f)
        } else {
            combinedAdultScore
        }
        return porn >= pornThresh ||
            sexy >= sexyThresh ||
            hentai >= hentaiThresh ||
            combinedAdultScore >= sexyThresh ||
            skinBoostedScore >= sexyThresh
    }

    fun formatReason(pornThresh: Float, sexyThresh: Float, hentaiThresh: Float): String {
        val pornPct = (porn * 100).toInt()
        val sexyPct = (sexy * 100).toInt()
        val hentaiPct = (hentai * 100).toInt()
        val skinPct = (skinRatio * 100).toInt()
        return when {
            porn >= pornThresh -> "נחסם עקב זיהוי תמונה לא ראויה (Porn: $pornPct%, Sexy: $sexyPct%)"
            sexy >= sexyThresh -> "נחסם עקב זיהוי תמונה חשופה (Sexy: $sexyPct%, Porn: $pornPct%)"
            hentai >= hentaiThresh -> "נחסם עקב זיהוי איור לא ראוי (Hentai: $hentaiPct%)"
            else -> "נחסם עקב זיהוי חשיפת עור ותוכן ויזואלי (Sexy: $sexyPct%, Skin: $skinPct%)"
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
     * 7-Zone Smart Scanner (Full-Width Zones + 2x2 Grid Quadrants):
     * Handles both:
     * A) Fullscreen / single-video layouts (YouTube Shorts, TikTok, Reels, X.com single post)
     * B) 2x2 Grid thumbnail layouts (like YouTube Shorts search grid where 1 of the 4 tiles has a bikini/thong image!)
     */
    @Synchronized
    fun classifyFullScreenBitmap(
        fullScreenSoftwareBitmap: Bitmap,
        detectedObjectCrops: List<Bitmap>,
        skinDetector: ObjectAndSkinDetector?,
        pornThresh: Float,
        sexyThresh: Float,
        hentaiThresh: Float
    ): NsfwResult {
        val currentInterpreter = interpreter
        if (!isAvailable || currentInterpreter == null) {
            return NsfwResult(0f, 0f, 1f, 0f, 0f, 0f, 0L, isModelLoaded = false)
        }

        val startTime = SystemClock.elapsedRealtime()
        val width = fullScreenSoftwareBitmap.width
        val height = fullScreenSoftwareBitmap.height

        var maxDrawing = 0f
        var maxHentai = 0f
        var maxNeutral = 1f
        var maxPorn = 0f
        var maxSexy = 0f
        var maxSkinRatio = skinDetector?.calculateSkinToneRatio(fullScreenSoftwareBitmap) ?: 0f

        val cropsToTest = mutableListOf<Bitmap>()
        try {
            val candidateCrops = mutableListOf<Bitmap>()
            // Priority 1: Exact object bounding boxes detected by ML Kit ObjectDetector
            candidateCrops.addAll(detectedObjectCrops)

            if (height > width * 1.2f) {
                val fullW = width
                // Priority 2: Inner Center Portrait Zoom Crop (catches a model in the middle of a white background)
                val zoomW = (width * 0.58f).toInt().coerceAtLeast(100)
                val zoomH = (height * 0.52f).toInt().coerceAtLeast(100)
                val zoomX = ((width - zoomW) / 2).coerceAtLeast(0)
                val zoomY = ((height - zoomH) / 2).coerceAtLeast(0)
                candidateCrops.add(Bitmap.createBitmap(fullScreenSoftwareBitmap, zoomX, zoomY, zoomW, zoomH))

                // Priority 3: Center main square (catches fullscreen Shorts/Reels/Photos)
                val centerY = ((height - fullW) / 2).coerceAtLeast(0)
                candidateCrops.add(Bitmap.createBitmap(fullScreenSoftwareBitmap, 0, centerY, fullW, fullW))

                // Priority 4: Top video player square (catches top YouTube video player)
                val topY = (height * 0.08f).toInt().coerceAtMost(height - fullW)
                candidateCrops.add(Bitmap.createBitmap(fullScreenSoftwareBitmap, 0, topY, fullW, fullW))

                // Priority 5: Four 2x2 Grid Quadrants (Left-Top, Right-Top, Left-Bottom, Right-Bottom)
                val halfW = (width / 2).coerceAtLeast(1)
                val gridTopY = (height * 0.10f).toInt().coerceAtLeast(0)
                val tileH = ((height * 0.38f).toInt()).coerceAtMost(height - gridTopY)
                val gridBottomY = (gridTopY + tileH).coerceAtMost(height - tileH)

                if (tileH > 50 && halfW > 50) {
                    candidateCrops.add(Bitmap.createBitmap(fullScreenSoftwareBitmap, 0, gridTopY, halfW, tileH))
                    candidateCrops.add(Bitmap.createBitmap(fullScreenSoftwareBitmap, width - halfW, gridTopY, halfW, tileH))
                    candidateCrops.add(Bitmap.createBitmap(fullScreenSoftwareBitmap, 0, gridBottomY, halfW, tileH))
                    candidateCrops.add(Bitmap.createBitmap(fullScreenSoftwareBitmap, width - halfW, gridBottomY, halfW, tileH))
                }
            } else {
                val cropSize = height
                val centerX = ((width - cropSize) / 2).coerceAtLeast(0)
                candidateCrops.add(Bitmap.createBitmap(fullScreenSoftwareBitmap, centerX, 0, cropSize, cropSize))
            }

            // SMART BATTERY FUNNEL:
            // Measure skin-tone density (<0.4ms each) on candidate crops first.
            // Sort by highest skin ratio and only run heavy TFLite inference on the top 2 skin-richest regions!
            val scoredCrops = candidateCrops.map { crop ->
                val skin = skinDetector?.calculateSkinToneRatio(crop) ?: 0f
                maxSkinRatio = max(maxSkinRatio, skin)
                crop to skin
            }.sortedByDescending { it.second }

            cropsToTest.addAll(candidateCrops)

            // Gate 1: If no region on the entire screen has at least 4.5% skin tone, skip heavy neural net completely!
            if (maxSkinRatio < 0.045f) {
                val fastElapsed = SystemClock.elapsedRealtime() - startTime
                return NsfwResult(
                    drawing = 0f,
                    hentai = 0f,
                    neutral = 1f,
                    porn = 0f,
                    sexy = 0f,
                    skinRatio = maxSkinRatio,
                    inferenceTimeMs = fastElapsed,
                    isModelLoaded = true
                )
            }

            // Gate 2: Run TFLite neural network ONLY on the top 2 skin-richest crops (cuts CPU work by 75%!)
            val topCropsForAi = scoredCrops.filter { it.second >= 0.045f }.take(2)
            for ((crop, cropSkin) in topCropsForAi) {
                val scores = runSingleInference(currentInterpreter, crop)
                maxDrawing = max(maxDrawing, scores[0])
                maxHentai = max(maxHentai, scores[1])
                maxNeutral = scores[2]
                maxPorn = max(maxPorn, scores[3])
                maxSexy = max(maxSexy, scores[4])

                val currentRes = NsfwResult(
                    drawing = maxDrawing,
                    hentai = maxHentai,
                    neutral = maxNeutral,
                    porn = maxPorn,
                    sexy = maxSexy,
                    skinRatio = max(maxSkinRatio, cropSkin),
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
                skinRatio = maxSkinRatio,
                inferenceTimeMs = elapsed,
                isModelLoaded = true
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error during multi-zone TFLite inference: ${e.message}")
            val elapsed = SystemClock.elapsedRealtime() - startTime
            return NsfwResult(0f, 0f, 1f, 0f, 0f, maxSkinRatio, elapsed, isModelLoaded = false)
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
