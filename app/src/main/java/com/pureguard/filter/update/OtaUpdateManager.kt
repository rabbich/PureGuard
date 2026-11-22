package com.pureguard.filter.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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

    private val fastHeadClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun downloadAndInstallLatestApk(
        onProgress: (percent: Int, statusText: String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cacheDir = context.externalCacheDir ?: context.cacheDir
            val updateFile = File(cacheDir, "PureGuard-Update.apk")
            val tempFile = File(cacheDir, "PureGuard-Update.apk.tmp")
            val otaPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // Step 1: Check if a valid downloaded APK already exists on the device!
            if (isValidCachedApk(updateFile)) {
                withContext(Dispatchers.Main) {
                    onProgress(15, "בודק אם קובץ העדכון שכבר במכשיר עדכני...")
                }

                val savedEtag = otaPrefs.getString(KEY_CACHED_ETAG, "").orEmpty()
                val savedLength = otaPrefs.getLong(KEY_CACHED_LENGTH, -1L)
                val ageMs = System.currentTimeMillis() - updateFile.lastModified()
                val archiveVersion = getApkVersionCode(updateFile)
                val installedVersion = getInstalledVersionCode()

                // If downloaded in the last 10 minutes or version is newer than installed, verify quickly with HEAD
                val matchesCloud = isCachedApkMatchingCloud(
                    updateFile = updateFile,
                    savedEtag = savedEtag,
                    savedLength = savedLength,
                    ageMs = ageMs,
                    archiveVersion = archiveVersion,
                    installedVersion = installedVersion
                )

                if (matchesCloud) {
                    withContext(Dispatchers.Main) {
                        onProgress(100, "קובץ העדכון כבר קיים במכשיר! פותח מסך התקנה מיד...")
                        launchPackageInstaller(updateFile)
                    }
                    return@withContext Result.success(Unit)
                }
            }

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
                val remoteEtag = response.header("ETag").orEmpty()

                // Extra safety: if GET headers show the exact same ETag & byte size as the already-downloaded APK, reuse it immediately!
                if (isValidCachedApk(updateFile) &&
                    totalBytes > 10_000_000L &&
                    updateFile.length() == totalBytes &&
                    (remoteEtag.isEmpty() || remoteEtag == otaPrefs.getString(KEY_CACHED_ETAG, ""))
                ) {
                    withContext(Dispatchers.Main) {
                        onProgress(100, "קובץ העדכון כבר קיים במכשיר! פותח מסך התקנה מיד...")
                        launchPackageInstaller(updateFile)
                    }
                    return@withContext Result.success(Unit)
                }

                if (tempFile.exists()) {
                    tempFile.delete()
                }

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
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

                // Atomically replace PureGuard-Update.apk only after 100% completion
                if (updateFile.exists()) {
                    updateFile.delete()
                }
                val renamed = tempFile.renameTo(updateFile)
                val finalFile = if (renamed) updateFile else tempFile

                otaPrefs.edit()
                    .putString(KEY_CACHED_ETAG, remoteEtag)
                    .putLong(KEY_CACHED_LENGTH, finalFile.length())
                    .apply()

                withContext(Dispatchers.Main) {
                    onProgress(100, "ההורדה הושלמה! פותח מסך התקנת עדכון...")
                    launchPackageInstaller(finalFile)
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isValidCachedApk(file: File): Boolean {
        if (!file.exists() || file.length() < 10_000_000L) return false
        return try {
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            info != null && info.packageName == context.packageName
        } catch (_: Throwable) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun getApkVersionCode(file: File): Long {
        return try {
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return -1L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        } catch (_: Throwable) {
            -1L
        }
    }

    @Suppress("DEPRECATION")
    private fun getInstalledVersionCode(): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun isCachedApkMatchingCloud(
        updateFile: File,
        savedEtag: String,
        savedLength: Long,
        ageMs: Long,
        archiveVersion: Long,
        installedVersion: Long
    ): Boolean {
        // Fast path: if the user just downloaded this APK within the last 3 minutes (e.g. lost install dialog or granted permission), open immediately!
        if (ageMs in 0..180_000L && updateFile.length() == savedLength) {
            return true
        }
        return try {
            val headReq = Request.Builder()
                .url(LATEST_APK_URL)
                .head()
                .header("Cache-Control", "no-cache")
                .build()
            fastHeadClient.newCall(headReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return archiveVersion > installedVersion
                }
                val remoteEtag = resp.header("ETag").orEmpty()
                val remoteLen = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                val etagMatches = remoteEtag.isNotEmpty() && savedEtag.isNotEmpty() && remoteEtag == savedEtag
                val lenMatches = remoteLen > 10_000_000L && updateFile.length() == remoteLen
                etagMatches || lenMatches
            }
        } catch (_: Throwable) {
            // If network check times out, still install the cached APK if it's newer than or equal to current version
            archiveVersion >= installedVersion && archiveVersion > 0
        }
    }

    private fun launchPackageInstaller(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(
                    context,
                    "אשר התקנת אפליקציות לא מוכרות עבור Kodeshield ולחץ שוב על עדכון (הקובץ כבר שמור במכשיר!)",
                    Toast.LENGTH_LONG
                ).show()
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permIntent)
                return
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
        private const val PREFS_NAME = "ota_update_cache_prefs"
        private const val KEY_CACHED_ETAG = "cached_etag"
        private const val KEY_CACHED_LENGTH = "cached_length"

        // Direct GitHub Release download URL updated automatically on every build
        const val LATEST_APK_URL =
            "https://github.com/rabbich/PureGuard/releases/download/latest/PureGuard-Sandbox-Debug.apk"
    }
}
