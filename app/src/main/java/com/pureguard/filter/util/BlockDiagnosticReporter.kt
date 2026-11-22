package com.pureguard.filter.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object BlockDiagnosticReporter {

    private const val CAPTURE_FILE_NAME = "last_block_capture.jpg"
    private const val REPORT_PREFS = "block_diagnostic_prefs"
    private const val KEY_LAST_REPORT_TEXT = "last_report_text"
    private const val KEY_LAST_CAPTURE_TIME = "last_capture_time"
    private const val CLOUD_DIAGNOSTIC_URL = "https://ntfy.sh/kodeshield_diag_1fac7646"

    private val uploadExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun saveBlockSnapshot(
        context: Context,
        bitmap: Bitmap?,
        targetUrlOrApp: String,
        blockReason: String,
        pageTitle: String,
        textSnippet: String
    ) {
        try {
            val timestampStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            val dir = context.externalCacheDir ?: context.cacheDir
            val captureFile = File(dir, CAPTURE_FILE_NAME)

            var hasSavedImage = false
            if (bitmap != null && !bitmap.isRecycled) {
                FileOutputStream(captureFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
                    out.flush()
                }
                hasSavedImage = true
            } else if (captureFile.exists() && (System.currentTimeMillis() - captureFile.lastModified()) > 15_000L) {
                captureFile.delete()
            }

            val reportText = buildString {
                appendLine("🛡️ דו\"ח אבחון חסימה – Kodeshield (מגן הקודש)")
                appendLine("⏰ זמן: $timestampStr")
                appendLine("📱 אפליקציה / כתובת: $targetUrlOrApp")
                if (pageTitle.isNotBlank() && pageTitle != targetUrlOrApp) {
                    appendLine("📄 כותרת דף: $pageTitle")
                }
                appendLine("🚨 סיבת החסימה: $blockReason")
                if (hasSavedImage) {
                    appendLine("📸 צילום מסך של רגע החסימה: מצורף")
                }
                if (textSnippet.isNotBlank()) {
                    appendLine("📝 קטע טקסט / OCR מהמסך:")
                    appendLine(textSnippet.take(500))
                }
            }

            context.getSharedPreferences(REPORT_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_REPORT_TEXT, reportText)
                .putLong(KEY_LAST_CAPTURE_TIME, System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {
        }
    }

    /**
     * 1-Tap Direct Cloud Upload from the Block Screen or Dashboard:
     * Sends the full diagnostic report + screenshot directly over HTTPS without calling startActivity()
     * (so Xiaomi never blocks it, and the AI engineer receives it immediately!).
     */
    fun shareLastBlockReport(
        context: Context,
        targetUrlOrApp: String,
        blockReason: String,
        pageTitle: String,
        textSnippet: String,
        openShareSheetToo: Boolean = false
    ) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(REPORT_PREFS, Context.MODE_PRIVATE)
        val savedReport = prefs.getString(KEY_LAST_REPORT_TEXT, null)
        val timestampStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())

        val reportText = savedReport ?: buildString {
            appendLine("🛡️ דו\"ח אבחון חסימה – Kodeshield (מגן הקודש)")
            appendLine("⏰ זמן: $timestampStr")
            appendLine("📱 אפליקציה / כתובת: $targetUrlOrApp")
            if (pageTitle.isNotBlank()) appendLine("📄 כותרת: $pageTitle")
            appendLine("🚨 סיבת החסימה: $blockReason")
            if (textSnippet.isNotBlank()) {
                appendLine("📝 טקסט מהמסך:")
                appendLine(textSnippet.take(500))
            }
        }

        mainHandler.post {
            Toast.makeText(appContext, "שולח צילום מצב ודו\"ח חסימה לבדיקה... 📤", Toast.LENGTH_SHORT).show()
        }

        uploadExecutor.execute {
            try {
                // 1. Upload full text report immediately
                val textReq = Request.Builder()
                    .url(CLOUD_DIAGNOSTIC_URL)
                    .post(reportText.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .header("Title", "Kodeshield Block Report")
                    .build()
                httpClient.newCall(textReq).execute().close()

                // 2. Upload screenshot image if available
                val dir = appContext.externalCacheDir ?: appContext.cacheDir
                val captureFile = File(dir, CAPTURE_FILE_NAME)
                val hasRecentImage = captureFile.exists() && captureFile.length() > 1024L &&
                    (System.currentTimeMillis() - captureFile.lastModified()) < 15 * 60 * 1000L

                if (hasRecentImage) {
                    val imgReq = Request.Builder()
                        .url(CLOUD_DIAGNOSTIC_URL)
                        .put(captureFile.asRequestBody("image/jpeg".toMediaType()))
                        .header("Filename", "block_capture_${System.currentTimeMillis()}.jpg")
                        .build()
                    httpClient.newCall(imgReq).execute().close()
                }

                mainHandler.post {
                    Toast.makeText(
                        appContext,
                        "✅ צילום המצב ופרטי החסימה נשלחו בהצלחה לבדיקה!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Throwable) {
                mainHandler.post {
                    Toast.makeText(
                        appContext,
                        "שגיאה בשליחה לענן: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        if (openShareSheetToo) {
            try {
                val dir = appContext.externalCacheDir ?: appContext.cacheDir
                val captureFile = File(dir, CAPTURE_FILE_NAME)
                val hasRecentImage = captureFile.exists() && captureFile.length() > 1024L
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_SUBJECT, "דו\"ח חסימה לבדיקה – מגן הקודש")
                    putExtra(Intent.EXTRA_TEXT, reportText)
                    if (hasRecentImage) {
                        val uri = FileProvider.getUriForFile(
                            appContext,
                            "${appContext.packageName}.fileprovider",
                            captureFile
                        )
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("block_capture", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } else {
                        type = "text/plain"
                    }
                }
                val chooser = Intent.createChooser(sendIntent, "שלח צילום ודו\"ח חסימה").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (_: Throwable) {
            }
        }
    }
}
