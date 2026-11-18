package com.pureguard.filter.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Ultra-fast On-Device OCR (Google ML Kit Text Recognition) running 100% offline in RAM.
 * Catches words rendered directly inside video frames, YouTube Shorts overlays, X.com/Facebook canvas,
 * or image watermarks (e.g. "@NUDEgirlsTongueKiss", "ONLY FEMZ", "NUDE girls Tongue Kiss", "bikini", "thong")
 * even when Chrome's DOM hides them from standard Accessibility text nodes.
 */
class ScreenOcrScanner {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractTextFromBitmap(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) {
                        cont.resume(visionText.text.orEmpty())
                    }
                }
                .addOnFailureListener { e ->
                    Log.d(TAG, "OCR scan skipped: ${e.message}")
                    if (cont.isActive) {
                        cont.resume("")
                    }
                }
        } catch (e: Throwable) {
            if (cont.isActive) {
                cont.resume("")
            }
        }
    }

    fun close() {
        try {
            recognizer.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "ScreenOcrScanner"
    }
}
