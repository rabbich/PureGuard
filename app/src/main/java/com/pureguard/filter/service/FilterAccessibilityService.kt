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
import com.pureguard.filter.ai.ScreenOcrScanner
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
    private var ocrScanner: ScreenOcrScanner? = null

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
            ocrScanner = ScreenOcrScanner()
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

        // Layer 2: Event-driven debounced visual AI + OCR check (650ms debounce)
        serviceScope.launch(Dispatchers.Default) {
            visualScanTrigger
                .debounce(DEBOUNCE_VISUAL_SCAN_MS)
                .collect { pkg ->
                    performVisualAiScanIfEligible(pkg)
                }
        }

        // Video Watchdog: When user is actively watching a video/Short in Chrome/YouTube/X.com/TikTok
        // without touching the screen, sample every 3.5 seconds while screen is interactive.
        serviceScope.launch(Dispatchers.Default) {
            while (true) {
                kotlinx.coroutines.delay(3500L)
                val pkg = currentForegroundPackage
                if (powerManager.isInteractive && pkg.isNotEmpty() && isMonitoredContentPackage(pkg)) {
                    visualScanTrigger.tryEmit(pkg)
                }
            }
        }

        Log.i(TAG, "FilterAccessibilityService v2 connected and active.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Never inspect our own package or system UI overlays
        if (pkg == packageName || pkg == "com.android.systemui") return

        // Screen off check: zero work when screen is not interactive
        if (!powerManager.isInteractive) return

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

        // Also check the direct event text/contentDescription immediately (catches fast web/video titles)
        if (isMonitoredContentPackage(pkg)) {
            val eventImmediateText = buildString {
                event.text?.forEach { append(it).append(' ') }
                event.contentDescription?.let { append(it) }
            }.trim()

            if (eventImmediateText.isNotEmpty()) {
                val matched = findMatchingKeyword(eventImmediateText, "")
                if (matched != null) {
                    triggerBlockOverlay(
                        targetAppOrUrl = pkg,
                        reason = "נחסם עקב מילת מפתח במסך: ${matched.keyword}",
                        pageTitle = pkg,
                        textSnippet = eventImmediateText.take(500),
                        isTextBlock = true,
                        inferenceTimeMs = 1L,
                        navigateBack = true
                    )
                    return
                }
            }
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (isMonitoredContentPackage(pkg)) {
                    scheduleTextAndUrlScan(pkg, forceImmediate = true)
                    visualScanTrigger.tryEmit(pkg)
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isMonitoredContentPackage(pkg)) {
                    scheduleTextAndUrlScan(pkg, forceImmediate = false)
                    visualScanTrigger.tryEmit(pkg)
                }
            }

            else -> Unit
        }
    }

    /**
     * Checks if package should be scanned for text/keywords, OCR AND visual AI screenshots.
     * Excludes safe utility/messaging apps (WhatsApp, Waze, Dialer, Settings, Launcher).
     */
    private fun isMonitoredContentPackage(pkg: String): Boolean {
        if (WHITELIST_SAFE_PACKAGES.contains(pkg)) return false
        return true // Monitor ANY non-whitelisted app (Browsers, YouTube, X.com, Facebook, Instagram, Telegram, Gallery, etc.)
    }

    private fun findMatchingKeyword(fullText: String, urlText: String): BlockedKeywordEntity? {
        val fullTextLower = fullText.lowercase()
        val urlLower = urlText.lowercase()
        // Also strip spaces/underscores for glued handles like "@NUDEgirlsTongueKiss" or "ONLYFEMZ"
        val compactTextLower = fullTextLower.replace(" ", "").replace("_", "").replace("-", "")

        for (item in cachedKeywords) {
            val kw = item.keyword.lowercase().trim()
            if (kw.isEmpty()) continue
            val compactKw = kw.replace(" ", "").replace("_", "").replace("-", "")
            if (item.isDomain) {
                if (urlLower.contains(kw) || fullTextLower.contains(kw)) {
                    return item
                }
            } else {
                if (fullTextLower.contains(kw) || (compactKw.length >= 5 && compactTextLower.contains(compactKw))) {
                    return item
                }
            }
        }
        return null
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

            if (db.temporaryWhitelistDao().isWhitelisted(urlOrIdentifier)) {
                return@launch
            }

            val combinedText = "${extracted.url} ${extracted.pageTitle} ${extracted.visibleText}"
            val matchedKeyword = findMatchingKeyword(combinedText, extracted.url)
            val elapsedMs = SystemClock.elapsedRealtime() - scanStart

            if (matchedKeyword != null) {
                val reason = if (matchedKeyword.isDomain) {
                    "נחסם עקב דומיין אסור: ${matchedKeyword.keyword}"
                } else {
                    "נחסם עקב מילת מפתח בדף: ${matchedKeyword.keyword}"
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

        val now = SystemClock.elapsedRealtime()
        if (now - lastScreenshotElapsed < HARD_SCREENSHOT_COOLDOWN_MS) {
            return
        }
        lastScreenshotElapsed = now

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            serviceScope.launch(Dispatchers.Default) {
                                processAndroid11ScreenshotInMemory(pkg, screenshotResult)
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
        } else {
            // Android 10 (API 29) support via RAM MediaProjection Capturer
            if (Android10ScreenCapturer.isReady) {
                serviceScope.launch(Dispatchers.Default) {
                    val bitmap = Android10ScreenCapturer.captureLatestBitmap()
                    if (bitmap != null) {
                        processSoftwareBitmapInMemory(pkg, bitmap)
                    }
                }
            } else {
                showDebugToastIfEnabled("Android 10: לחץ באפליקציה על 'הפעל סריקת תמונות לאנדרואיד 10'")
            }
        }
    }

    private suspend fun processAndroid11ScreenshotInMemory(pkg: String, screenshotResult: ScreenshotResult) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val hardwareBuffer = screenshotResult.hardwareBuffer
        val colorSpace = screenshotResult.colorSpace
        var hardwareBitmap: Bitmap? = null
        var softwareBitmap: Bitmap? = null
        try {
            hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
            if (hardwareBitmap == null) return
            softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            if (softwareBitmap != null) {
                processSoftwareBitmapInMemory(pkg, softwareBitmap)
            }
        } finally {
            try {
                if (hardwareBitmap != null && !hardwareBitmap.isRecycled) hardwareBitmap.recycle()
            } catch (_: Throwable) {}
            try {
                hardwareBuffer.close()
            } catch (_: Throwable) {}
        }
    }

    private suspend fun processSoftwareBitmapInMemory(pkg: String, softwareBitmap: Bitmap) {
        try {
            val totalStart = SystemClock.elapsedRealtime()

            // STEP A: On-Device Visual OCR Scan (reads text burned into YouTube Shorts, video overlays, X.com images!)
            val ocrText = ocrScanner?.extractTextFromBitmap(softwareBitmap).orEmpty()
            if (ocrText.isNotBlank()) {
                val ocrMatched = findMatchingKeyword(ocrText, "")
                if (ocrMatched != null) {
                    val ocrElapsed = SystemClock.elapsedRealtime() - totalStart
                    showDebugToastIfEnabled("OCR Blocked: '${ocrMatched.keyword}' (${ocrElapsed}ms)")
                    triggerBlockOverlay(
                        targetAppOrUrl = pkg,
                        reason = "נחסם עקב זיהוי טקסט בתוך התמונה/וידאו (OCR): ${ocrMatched.keyword}",
                        pageTitle = pkg,
                        textSnippet = ocrText.take(600),
                        isTextBlock = true,
                        inferenceTimeMs = ocrElapsed,
                        navigateBack = true
                    )
                    return
                }
            }

            // STEP B: 7-Zone TFLite Visual NSFW Model (Full zones + 4 Grid Quadrants for 2x2 Shorts/Images grids)
            val classifier = nsfwClassifier
            if (classifier == null || !classifier.isAvailable) {
                val fallbackElapsed = SystemClock.elapsedRealtime() - totalStart
                showDebugToastIfEnabled("Scan: ${fallbackElapsed}ms | OCR OK | AI Model Loading")
                return
            }

            val pornThresh = prefs.pornThreshold
            val sexyThresh = prefs.sexyThreshold
            val hentaiThresh = prefs.hentaiThreshold

            val result = classifier.classifyFullScreenBitmap(
                fullScreenSoftwareBitmap = softwareBitmap,
                pornThresh = pornThresh,
                sexyThresh = sexyThresh,
                hentaiThresh = hentaiThresh
            )

            val totalElapsed = SystemClock.elapsedRealtime() - totalStart
            val isViolation = result.isViolation(pornThresh, sexyThresh, hentaiThresh)
            val statusLabel = if (isViolation) "BLOCKED" else "Safe"

            showDebugToastIfEnabled(
                "Scan: ${totalElapsed}ms | Porn: ${(result.porn * 100).toInt()}% | Sexy: ${(result.sexy * 100).toInt()}% | $statusLabel"
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
                    textSnippet = ocrText.ifBlank { "זיהוי תמונה/וידאו ויזואלי באמצעות מודל AI מקומי" },
                    isTextBlock = false,
                    inferenceTimeMs = totalElapsed,
                    navigateBack = true
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error processing in-memory screenshot: ${e.message}")
        } finally {
            try {
                if (!softwareBitmap.isRecycled) {
                    softwareBitmap.recycle()
                }
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

        // Deep DOM traversal up to 650 nodes (including non-visible-flagged WebView DOM children in Chrome YouTube Shorts & X.com!)
        var visitedCount = 0
        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || visitedCount > 650) return
            visitedCount++

            val nodeText = node.text?.toString()?.trim().orEmpty()
            val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()

            if (nodeText.isNotEmpty()) {
                if (detectedUrl.isEmpty() && looksLikeUrl(nodeText)) {
                    detectedUrl = nodeText
                } else if (detectedTitle.isEmpty() && nodeText.length in 5..120) {
                    detectedTitle = nodeText
                }
                if (textBuilder.length < 4500) {
                    textBuilder.append(nodeText).append(' ')
                }
            }
            if (contentDesc.isNotEmpty() && textBuilder.length < 4500) {
                if (detectedTitle.isEmpty() && contentDesc.length in 5..120) {
                    detectedTitle = contentDesc
                }
                textBuilder.append(contentDesc).append(' ')
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                if (visitedCount > 650) break
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
        ocrScanner?.close()
    }

    companion object {
        private const val TAG = "FilterAccessService"

        private const val TEXT_SCAN_THROTTLE_MS = 350L
        private const val DEBOUNCE_VISUAL_SCAN_MS = 650L
        private const val HARD_SCREENSHOT_COOLDOWN_MS = 1800L

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
