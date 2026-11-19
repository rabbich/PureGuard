package com.pureguard.filter.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class OtaUpdateManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun downloadAndInstallLatestApk(
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            withContext(Dispatchers.Main) {
                onProgress(0, "מתחבר לשרת העדכונים ומוריד את הגרסה החדשה...")
            }

            val request = Request.Builder()
                .url(LATEST_APK_URL)
                .header("Cache-Control", "no-cache")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("שרת העדכונים החזיר קוד ${response.code}. ודא שהגרסה פורסמה.")
                    )
                }

                val body = response.body ?: return@withContext Result.failure(
                    Exception("קובץ העדכון ריק")
                )

                val totalBytes = body.contentLength()
                val updateFile = File(context.externalCacheDir ?: context.cacheDir, "PureGuard-Update.apk")
                if (updateFile.exists()) {
                    updateFile.delete()
                }

                body.byteStream().use { input ->
                    FileOutputStream(updateFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var downloadedBytes = 0L
                        var read: Int
                        var lastReportedPct = -1

                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                val pct = ((downloadedBytes * 100) / totalBytes).toInt()
                                if (pct != lastReportedPct && pct % 5 == 0) {
                                    lastReportedPct = pct
                                    val mbDone = downloadedBytes / (1024 * 1024)
                                    val mbTotal = totalBytes / (1024 * 1024)
                                    withContext(Dispatchers.Main) {
                                        onProgress(pct, "מוריד עדכון: $pct% ($mbDone MB / $mbTotal MB)...")
                                    }
                                }
                            }
                        }
                        output.flush()
                    }
                }

                withContext(Dispatchers.Main) {
                    onProgress(100, "ההורדה הושלמה! פותח מסך התקנת עדכון...")
                    launchPackageInstaller(updateFile)
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun launchPackageInstaller(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permIntent)
            }
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }

    companion object {
        // Direct GitHub Release download URL updated automatically on every build
        const val LATEST_APK_URL =
            "https://github.com/rabbich/PureGuard/releases/download/latest/PureGuard-Sandbox-Debug.apk"
    }
}
