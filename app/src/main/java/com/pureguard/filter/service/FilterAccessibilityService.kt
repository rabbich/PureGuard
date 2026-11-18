package com.pureguard.filter.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.pureguard.filter.ai.NsfwClassifier
import com.pureguard.filter.data.BlockLogEntity
import com.pureguard.filter.data.BlockedAppEntity
import com.pureguard.filter.data.BlockedKeywordEntity
import com.pureguard.filter.data.PreferencesManager
import com.pureguard.filter.data.PureGuardDatabase
import com.pureguard.filter.ui.BlockOverlayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@OptIn(FlowPreview::class)
class FilterAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    private lateinit var db: PureGuardDatabase
    private lateinit var prefs: PreferencesManager
    private lateinit var powerManager: PowerManager
    private var nsfwClassifier: NsfwClassifier? = null

    @Volatile
    private var cachedBlockedApps: Set<String> = emptySet()

    @Volatile
    private var cachedKeywords: List<BlockedKeywordEntity> = emptyList()

    @Volatile
    private var currentForegroundPackage: String = ""

    @Volatile
    private var lastTextScanElapsed: Long = 0L

    @Volatile
    private var lastScreenshotElapsed: Long = 0L

    @Volatile
    private var lastBlockOverlayElapsed: Long = 0L

    private val visualScanTrigger = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        db = PureGuardDatabase.getInstance(applicationContext)
        prefs = PreferencesManager(applicationContext)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        serviceScope.launch(Dispatchers.IO) {
            nsfwClassifier = NsfwClassifier(applicationContext)
        }

        // Observe Room DB changes into in-memory caches for <0.5% battery zero-lag checks
        serviceScope.launch(Dispatchers.IO) {
            db.blockedAppsDao().observeAll().collectLatest { list: List<BlockedAppEntity> ->
                cachedBlockedApps = list.map { it.packageName }.toSet()
            }
        }

        serviceScope.launch(Dispatchers.IO) {
            db.blockedKeywordsDao().observeAll().collectLatest { list: List<BlockedKeywordEntity> ->
                cachedKeywords = list
            }
        }

        // Layer 2: Event-driven debounced visual AI check (750ms debounce after scroll/video/content event)
        serviceScope.launch(Dispatchers.Default) {
            visualScanTrigger
                .debounce(DEBOUNCE_VISUAL_SCAN_MS)
                .collect { pkg ->
                    performVisualAiScanIfEligible(pkg)
                }
        }

        Log.i(TAG, "FilterAccessibilityService connected and active.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Never inspect our own package or system UI overlays
        if (pkg == packageName || pkg == "com.android.systemui") return

        // Screen off check: zero work when screen is not interactive
        if (!powerManager.isInteractive) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                currentForegroundPackage = pkg

                // Layer 1A: App Blocker check
                if (cachedBlockedApps.contains(pkg)) {
                    triggerBlockOverlay(
                        targetAppOrUrl = pkg,
                        reason = "נחסם עקב אפליקציה ברשימת החסימה ($pkg)",
                        pageTitle = pkg,
                        textSnippet = "",
                        isTextBlock = false,
                        inferenceTimeMs = 0L,
                        navigateBack = true
                    )
                    return
                }

                // If monitored app (browser, YouTube, social, search) entered foreground:
                if (isMonitoredContentPackage(pkg)) {
                    scheduleTextAndUrlScan(pkg, forceImmediate = true)
                    visualScanTrigger.tryEmit(pkg)
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (cachedBlockedApps.contains(pkg)) {
                    triggerBlockOverlay(
                        targetAppOrUrl = pkg,
                        reason = "נחסם עקב אפליקציה ברשימת החסימה ($pkg)",
                        pageTitle = pkg,
                        textSnippet = "",
                        isTextBlock = false,
                        inferenceTimeMs = 0L,
                        navigateBack = true
                    )
                    return
                }

                // Layer 1B + Layer 2 on any monitored content app (Browsers, YouTube, Instagram, TikTok, Google Search, etc.)
                if (isMonitoredContentPackage(pkg)) {
                    scheduleTextAndUrlScan(pkg, forceImmediate = false)
                    visualScanTrigger.tryEmit(pkg)
                }
            }

            else -> Unit
        }
    }

    /**
     * Checks if package should be scanned for text/keywords AND visual AI screenshots.
     * Excludes safe utility/messaging apps (WhatsApp, Waze, Dialer, Settings, Launcher).
     */
    private fun isMonitoredContentPackage(pkg: String): Boolean {
        if (WHITELIST_SAFE_PACKAGES.contains(pkg)) return false
        return MONITORED_PACKAGES.contains(pkg) ||
            pkg.contains("browser", ignoreCase = true) ||
            pkg.contains("chrome", ignoreCase = true) ||
            pkg.contains("youtube", ignoreCase = true) ||
            pkg.contains("webview", ignoreCase = true)
    }

    private fun scheduleTextAndUrlScan(pkg: String, forceImmediate: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!forceImmediate && (now - lastTextScanElapsed) < TEXT_SCAN_THROTTLE_MS) {
            return
        }
        lastTextScanElapsed = now

        val rootNode = rootInActiveWindow ?: return
        serviceScope.launch(Dispatchers.Default) {
            val scanStart = SystemClock.elapsedRealtime()
            val extracted = try {
                extractScreenContent(rootNode, pkg)
            } catch (e: Exception) {
                null
            } finally {
                try {
                    rootNode.recycle()
                } catch (_: Exception) {
                }
            } ?: return@launch

            val urlOrIdentifier = extracted.url.ifBlank {
                if (extracted.pageTitle.isNotBlank()) "$pkg (${extracted.pageTitle.take(35)})" else pkg
            }

            // Check temporary whitelist (from AI Re-Check or Sandbox 1-time approve)
            if (db.temporaryWhitelistDao().isWhitelisted(urlOrIdentifier)) {
                return@launch
            }

            val fullTextLower = "${extracted.url} ${extracted.pageTitle} ${extracted.visibleText}".lowercase()
            val urlLower = extracted.url.lowercase()

            var matchedKeyword: BlockedKeywordEntity? = null
            for (item in cachedKeywords) {
                val kw = item.keyword.lowercase().trim()
                if (kw.isEmpty()) continue
                if (item.isDomain) {
                    if (urlLower.contains(kw) || fullTextLower.contains(kw)) {
                        matchedKeyword = item
                        break
                    }
                } else {
                    if (fullTextLower.contains(kw)) {
                        matchedKeyword = item
                        break
                    }
                }
            }

            val elapsedMs = SystemClock.elapsedRealtime() - scanStart

            if (matchedKeyword != null) {
                val reason = if (matchedKeyword.isDomain) {
                    "נחסם עקב דומיין אסור: ${matchedKeyword.keyword}"
                } else {
                    "נחסם עקב מילת מפתח: ${matchedKeyword.keyword}"
                }

                triggerBlockOverlay(
                    targetAppOrUrl = urlOrIdentifier,
                    reason = reason,
                    pageTitle = extracted.pageTitle.ifBlank { urlOrIdentifier },
                    textSnippet = extracted.visibleText.take(800),
                    isTextBlock = true,
                    inferenceTimeMs = elapsedMs,
                    navigateBack = true
                )
            }
        }
    }

    private fun performVisualAiScanIfEligible(pkg: String) {
        if (!powerManager.isInteractive) return
        if (!isMonitoredContentPackage(pkg)) return

        // Enforce hard minimum cooldown of 2200ms between screenshots for zero battery drain
        val now = SystemClock.elapsedRealtime()
        if (now - lastScreenshotElapsed < HARD_SCREENSHOT_COOLDOWN_MS) {
            return
        }
        lastScreenshotElapsed = now

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        serviceScope.launch(Dispatchers.Default) {
                            processScreenshotInMemory(pkg, screenshotResult)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.d(TAG, "takeScreenshot skipped/failed with errorCode=$errorCode")
                    }
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Exception requesting screenshot: ${e.message}")
        }
    }

    private suspend fun processScreenshotInMemory(pkg: String, screenshotResult: ScreenshotResult) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val hardwareBuffer = screenshotResult.hardwareBuffer
        val colorSpace = screenshotResult.colorSpace
        var hardwareBitmap: Bitmap? = null
        var softwareBitmap: Bitmap? = null

        try {
            val totalStart = SystemClock.elapsedRealtime()
            hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
            if (hardwareBitmap == null) return

            // Copy HardwareBuffer to an in-memory ARGB_8888 Bitmap for multi-zone crop evaluation
            softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            if (softwareBitmap == null) return

            val classifier = nsfwClassifier
            if (classifier == null || !classifier.isAvailable) {
                val fallbackElapsed = SystemClock.elapsedRealtime() - totalStart
                showDebugToastIfEnabled(
                    "Scan: ${fallbackElapsed}ms | שגיאה: מודל AI חסר ב-APK"
                )
                return
            }

            val pornThresh = prefs.pornThreshold
            val sexyThresh = prefs.sexyThreshold
            val hentaiThresh = prefs.hentaiThreshold

            // Multi-Zone Crop analysis (checks YouTube top video zone, center image zone, and feed zone)
            val result = classifier.classifyFullScreenBitmap(
                fullScreenSoftwareBitmap = softwareBitmap,
                pornThresh = pornThresh,
                sexyThresh = sexyThresh,
                hentaiThresh = hentaiThresh
            )

            val isViolation = result.isViolation(pornThresh, sexyThresh, hentaiThresh)
            val statusLabel = if (isViolation) "BLOCKED" else "Safe"

            showDebugToastIfEnabled(
                "Scan: ${result.inferenceTimeMs}ms | Porn: ${(result.porn * 100).toInt()}% | Sexy: ${(result.sexy * 100).toInt()}% | $statusLabel"
            )

            if (isViolation) {
                val rootNode = rootInActiveWindow
                val currentIdentifier = try {
                    if (rootNode != null) {
                        val ext = extractScreenContent(rootNode, pkg)
                        ext.url.ifBlank { if (ext.pageTitle.isNotBlank()) "$pkg (${ext.pageTitle.take(30)})" else pkg }
                    } else pkg
                } catch (_: Exception) {
                    pkg
                } finally {
                    try {
                        rootNode?.recycle()
                    } catch (_: Exception) {
                    }
                }

                if (db.temporaryWhitelistDao().isWhitelisted(currentIdentifier)) {
                    return
                }

                val reason = result.formatReason(pornThresh, sexyThresh, hentaiThresh)
                triggerBlockOverlay(
                    targetAppOrUrl = currentIdentifier,
                    reason = reason,
                    pageTitle = currentIdentifier,
                    textSnippet = "זיהוי תמונה/וידאו ויזואלי באמצעות מודל AI מקומי",
                    isTextBlock = false,
                    inferenceTimeMs = result.inferenceTimeMs,
                    navigateBack = true
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error processing in-memory screenshot: ${e.message}")
        } finally {
            // Strictly recycle all Bitmaps and close HardwareBuffer immediately in RAM
            try {
                if (softwareBitmap != null && !softwareBitmap.isRecycled) {
                    softwareBitmap.recycle()
                }
            } catch (_: Throwable) {
            }
            try {
                if (hardwareBitmap != null && !hardwareBitmap.isRecycled) {
                    hardwareBitmap.recycle()
                }
            } catch (_: Throwable) {
            }
            try {
                hardwareBuffer.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun triggerBlockOverlay(
        targetAppOrUrl: String,
        reason: String,
        pageTitle: String,
        textSnippet: String,
        isTextBlock: Boolean,
        inferenceTimeMs: Long,
        navigateBack: Boolean
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockOverlayElapsed < 1200L) {
            return
        }
        lastBlockOverlayElapsed = now

        if (navigateBack) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }

        serviceScope.launch(Dispatchers.IO) {
            db.blockLogsDao().insertLog(
                BlockLogEntity(
                    timestamp = System.currentTimeMillis(),
                    targetAppOrUrl = targetAppOrUrl,
                    triggerReason = reason,
                    inferenceTimeMs = inferenceTimeMs
                )
            )
        }

        mainHandler.post {
            val intent = Intent(applicationContext, BlockOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(BlockOverlayActivity.EXTRA_TARGET_URL_OR_APP, targetAppOrUrl)
                putExtra(BlockOverlayActivity.EXTRA_REASON, reason)
                putExtra(BlockOverlayActivity.EXTRA_PAGE_TITLE, pageTitle)
                putExtra(BlockOverlayActivity.EXTRA_TEXT_SNIPPET, textSnippet)
                putExtra(BlockOverlayActivity.EXTRA_IS_TEXT_BLOCK, isTextBlock)
                putExtra(BlockOverlayActivity.EXTRA_INFERENCE_MS, inferenceTimeMs)
            }
            startActivity(intent)
        }
    }

    private fun showDebugToastIfEnabled(message: String) {
        if (!prefs.showDebugToast) return
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private data class ScreenExtractedContent(
        val url: String,
        val pageTitle: String,
        val visibleText: String
    )

    private fun extractScreenContent(root: AccessibilityNodeInfo, pkg: String): ScreenExtractedContent {
        var detectedUrl = ""
        var detectedTitle = ""
        val textBuilder = StringBuilder()

        // Try known URL bar IDs first
        val urlViewIds = URL_BAR_VIEW_IDS[pkg] ?: DEFAULT_URL_BAR_IDS
        for (viewId in urlViewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val text = node.text?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        detectedUrl = text
                        break
                    }
                }
                nodes.forEach {
                    try {
                        it.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
            if (detectedUrl.isNotEmpty()) break
        }

        // Traverse visible nodes (bounded to 150 nodes for fast <3ms traversal across YouTube & Browsers)
        var visitedCount = 0
        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || visitedCount > 150) return
            visitedCount++

            if (node.isVisibleToUser) {
                val nodeText = node.text?.toString()?.trim().orEmpty()
                val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()

                if (nodeText.isNotEmpty()) {
                    if (detectedUrl.isEmpty() && looksLikeUrl(nodeText)) {
                        detectedUrl = nodeText
                    } else if (detectedTitle.isEmpty() && nodeText.length in 5..100) {
                        detectedTitle = nodeText
                    }
                    if (textBuilder.length < 2000) {
                        textBuilder.append(nodeText).append(' ')
                    }
                }
                if (contentDesc.isNotEmpty() && textBuilder.length < 2000) {
                    if (detectedTitle.isEmpty() && contentDesc.length in 5..100) {
                        detectedTitle = contentDesc
                    }
                    textBuilder.append(contentDesc).append(' ')
                }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                if (visitedCount > 150) break
                val child = node.getChild(i)
                if (child != null) {
                    traverse(child)
                    try {
                        child.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        traverse(root)

        return ScreenExtractedContent(
            url = detectedUrl,
            pageTitle = detectedTitle,
            visibleText = textBuilder.toString().trim()
        )
    }

    private fun looksLikeUrl(text: String): Boolean {
        if (text.contains(" ")) return false
        return text.startsWith("http://") ||
            text.startsWith("https://") ||
            text.contains(".com") ||
            text.contains(".co.il") ||
            text.contains(".org") ||
            text.contains(".net")
    }

    override fun onInterrupt() {
        Log.w(TAG, "FilterAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        screenshotExecutor.shutdownNow()
        nsfwClassifier?.close()
    }

    companion object {
        private const val TAG = "FilterAccessService"

        private const val TEXT_SCAN_THROTTLE_MS = 400L
        private const val DEBOUNCE_VISUAL_SCAN_MS = 750L
        private const val HARD_SCREENSHOT_COOLDOWN_MS = 2200L

        /**
         * Monitored content packages where both Layer 1 (Keyword/URL) and Layer 2 (Visual AI) run.
         * Includes all major Browsers, YouTube, Google Search/Photos, Instagram, TikTok, Telegram, Facebook, X/Twitter, Reddit.
         */
        val MONITORED_PACKAGES = setOf(
            // Browsers
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.mi.globalbrowser",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.duckduckgo.mobile.android",
            // YouTube & Video Players
            "com.google.android.youtube",
            "com.google.android.apps.youtube.mango",
            "com.google.android.apps.youtube.kids",
            "app.revanced.android.youtube",
            // Google Search & Images
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.photos",
            "com.sec.android.gallery3d",
            "com.miui.gallery",
            // Social & Feeds
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.facebook.katana",
            "com.facebook.lite",
            "com.twitter.android",
            "com.reddit.frontpage",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.pinterest"
        )

        val WHITELIST_SAFE_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.waze",
            "com.android.settings",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.android.launcher3"
        )

        private val URL_BAR_VIEW_IDS = mapOf(
            "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
            "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
            "org.mozilla.firefox" to listOf(
                "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
                "org.mozilla.firefox:id/url_bar_title"
            ),
            "com.sec.android.app.sbrowser" to listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text"
            ),
            "com.mi.globalbrowser" to listOf(
                "com.mi.globalbrowser:id/url",
                "com.mi.globalbrowser:id/search_hint"
            )
        )

        private val DEFAULT_URL_BAR_IDS = listOf(
            "com.android.chrome:id/url_bar",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.mi.globalbrowser:id/url",
            "com.brave.browser:id/url_bar"
        )
    }
}
