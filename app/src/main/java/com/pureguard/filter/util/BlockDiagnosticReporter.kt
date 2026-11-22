package com.pureguard.filter.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BlockDiagnosticReporter {

    private const val CAPTURE_FILE_NAME = "last_block_capture.jpg"
    private const val REPORT_PREFS = "block_diagnostic_prefs"
    private const val KEY_LAST_REPORT_TEXT = "last_report_text"
    private const val KEY_LAST_CAPTURE_TIME = "last_capture_time"

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
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    out.flush()
                }
                hasSavedImage = true
            } else if (captureFile.exists() && (System.currentTimeMillis() - captureFile.lastModified()) > 15_000L) {
                // Delete stale screenshot from an old block so we don't attach an unrelated screenshot
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
                    appendLine("📸 צילום מסך של רגע החסימה: מצורף לקובץ")
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

    fun shareLastBlockReport(
        context: Context,
        targetUrlOrApp: String,
        blockReason: String,
        pageTitle: String,
        textSnippet: String
    ) {
        try {
            val prefs = context.getSharedPreferences(REPORT_PREFS, Context.MODE_PRIVATE)
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

            val dir = context.externalCacheDir ?: context.cacheDir
            val captureFile = File(dir, CAPTURE_FILE_NAME)
            val hasRecentImage = captureFile.exists() && captureFile.length() > 1024L &&
                (System.currentTimeMillis() - captureFile.lastModified()) < 10 * 60 * 1000L

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_SUBJECT, "דו\"ח חסימה לבדיקה – מגן הקודש")
                putExtra(Intent.EXTRA_TEXT, reportText)
                if (hasRecentImage) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
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

            val chooser = Intent.createChooser(sendIntent, "שלח צילום ודו\"ח חסימה לבדיקה").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Throwable) {
            Toast.makeText(context, "שגיאה בפתיחת שיתוף דו\"ח: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
