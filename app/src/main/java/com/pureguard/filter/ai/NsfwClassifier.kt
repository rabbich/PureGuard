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
        return porn >= pornThresh || sexy >= sexyThresh || hentai >= hentaiThresh
    }

    fun formatReason(pornThresh: Float, sexyThresh: Float, hentaiThresh: Float): String {
        return when {
            porn >= pornThresh -> "נחסם עקב זיהוי תמונה לא ראויה (Porn: ${(porn * 100).toInt()}%)"
            sexy >= sexyThresh -> "נחסם עקב זיהוי תמונה לא ראויה (Sexy: ${(sexy * 100).toInt()}%)"
            hentai >= hentaiThresh -> "נחסם עקב זיהוי תמונה לא ראויה (Hentai: ${(hentai * 100).toInt()}%)"
            else -> "בדיקת תמונה תקינה"
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
                setNumThreads(2)
            }
            val tflite = Interpreter(modelBuffer, options)
            inputDataType = tflite.getInputTensor(0).dataType()
            interpreter = tflite
            isAvailable = true
            Log.i(TAG, "NSFW TFLite model loaded successfully (Input type: $inputDataType)")
        } catch (e: Throwable) {
            Log.e(TAG, "Graceful fallback: Failed to load $MODEL_FILE: ${e.message}")
            interpreter = null
            isAvailable = false
        }
    }

    @Synchronized
    fun classify(bitmap224: Bitmap): NsfwResult {
        val currentInterpreter = interpreter
        if (!isAvailable || currentInterpreter == null) {
            return NsfwResult(
                drawing = 0f,
                hentai = 0f,
                neutral = 1f,
                porn = 0f,
                sexy = 0f,
                inferenceTimeMs = 0L,
                isModelLoaded = false
            )
        }

        val startTime = SystemClock.elapsedRealtime()
        return try {
            val imageProcessorBuilder = ImageProcessor.Builder()
                .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.ResizeMethod.BILINEAR))

            if (inputDataType == DataType.FLOAT32) {
                // Standard MobileNetV2 normalization [0, 1]
                imageProcessorBuilder.add(NormalizeOp(0f, 255f))
            }

            val imageProcessor = imageProcessorBuilder.build()
            var tensorImage = TensorImage(inputDataType)
            tensorImage.load(bitmap224)
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

            val elapsed = SystemClock.elapsedRealtime() - startTime
            // Standard 5 outputs alphabetical order: Drawing, Hentai, Neutral, Porn, Sexy
            NsfwResult(
                drawing = probs[0],
                hentai = probs[1],
                neutral = probs[2],
                porn = probs[3],
                sexy = probs[4],
                inferenceTimeMs = elapsed,
                isModelLoaded = true
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error during TFLite inference: ${e.message}")
            val elapsed = SystemClock.elapsedRealtime() - startTime
            NsfwResult(
                drawing = 0f,
                hentai = 0f,
                neutral = 1f,
                porn = 0f,
                sexy = 0f,
                inferenceTimeMs = elapsed,
                isModelLoaded = false
            )
        }
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
