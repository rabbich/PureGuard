package com.pureguard.filter.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Browser
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.pureguard.filter.ai.NsfwClassifier
import com.pureguard.filter.ai.ObjectAndSkinDetector
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
    private var objectAndSkinDetector: ObjectAndSkinDetector? = null

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
    private var lastContentEventElapsed: Long = 0L

    @Volatile
    private var lastBitmapSignature: Long = 0L

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
            objectAndSkinDetector = ObjectAndSkinDetector()
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

        // Battery-Safe Video Watchdog:
        // Only samples when the user is inside a video app (YouTube/TikTok) OR when content changed recently (<12s ago),
        // preventing infinite scanning on static web pages.
        serviceScope.launch(Dispatchers.Default) {
            while (true) {
                kotlinx.coroutines.delay(3500L)
                val pkg = currentForegroundPackage
                val now = SystemClock.elapsedRealtime()
                val isVideoApp = pkg.contains("youtube") || pkg.contains("musically") || pkg.contains("trill")
                val hadRecentActivity = (now - lastContentEventElapsed) < 12000L
                if (powerManager.isInteractive &&
                    !prefs.isFilterPausedForTesting &&
                    pkg.isNotEmpty() &&
                    isMonitoredContentPackage(pkg) &&
                    (isVideoApp || hadRecentActivity)
                ) {
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

        // Quick 1-Click Sandbox Testing Pause Toggle (only works when isLockdownEnabled == false)
        if (prefs.isFilterPausedForTesting) return

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

        // Block alternative search engine apps (DuckDuckGo, Yandex, Bing) when Google-only SafeSearch is active
        if (prefs.enforceSafeSearchAndRestrictedMode && BLOCKED_SEARCH_ENGINE_PACKAGES.contains(pkg)) {
            triggerBlockOverlay(
                targetAppOrUrl = pkg,
                reason = "נחסם: מותר שימוש במנוע החיפוש של גוגל בלבד (עם חיפוש בטוח SafeSearch)",
                pageTitle = pkg,
                textSnippet = "",
                isTextBlock = false,
                inferenceTimeMs = 0L,
                navigateBack = true
            )
            return
        }

        // Block Grok & Uncensored AI generator apps (Grok, CrushOn, Chai, JanitorAI, etc.)
        if (prefs.blockUncensoredAiAndGrok && BLOCKED_UNCENSORED_AI_PACKAGES.contains(pkg)) {
            triggerBlockOverlay(
                targetAppOrUrl = pkg,
                reason = "נחסם: אפליקציית AI ללא סינון צניעות (Grok / Uncensored AI) חסומה לשמירת העיניים",
                pageTitle = pkg,
                textSnippet = "",
                isTextBlock = false,
                inferenceTimeMs = 0L,
                navigateBack = true
            )
            return
        }

        lastContentEventElapsed = SystemClock.elapsedRealtime()

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

        // Extract only continuous handle/hashtag/URL tokens (e.g. "@NUDEgirlsTongueKiss") for compact matching
        // so normal two-word sentences never glue together into false positives!
        val gluedTokensLower = fullTextLower
            .split(Regex("\\s+"))
            .filter { it.startsWith("@") || it.startsWith("#") || it.length >= 14 || it.contains("_") }
            .joinToString(" ") { it.replace("_", "").replace("-", "") }

        for (item in cachedKeywords) {
            val kw = item.keyword.lowercase().trim()
            if (kw.isEmpty()) continue
            val compactKw = kw.replace(" ", "").replace("_", "").replace("-", "")
            if (item.isDomain) {
                if (urlLower.contains(kw) || fullTextLower.contains(kw)) {
                    return item
                }
            } else {
                if (fullTextLower.contains(kw) || (compactKw.length >= 6 && gluedTokensLower.contains(compactKw))) {
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

        val activeRoot = rootInActiveWindow
        val allRoots = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        try {
            val interactiveWindows = windows
            if (!interactiveWindows.isNullOrEmpty()) {
                for (win in interactiveWindows) {
                    val winRoot = win.root ?: continue
                    val winPkg = winRoot.packageName?.toString() ?: pkg
                    if (winPkg != packageName && winPkg != "com.android.systemui" && isMonitoredContentPackage(winPkg)) {
                        allRoots.add(winPkg to winRoot)
                    } else {
                        try { winRoot.recycle() } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (allRoots.isEmpty() && activeRoot != null) {
            allRoots.add(pkg to activeRoot)
        } else if (activeRoot != null) {
            try { activeRoot.recycle() } catch (_: Exception) {}
        }

        if (allRoots.isEmpty()) return

        serviceScope.launch(Dispatchers.Default) {
            for ((targetPkg, rootNode) in allRoots) {
                val scanStart = SystemClock.elapsedRealtime()
                val extracted = try {
                    extractScreenContent(rootNode, targetPkg)
                } catch (e: Exception) {
                    null
                } finally {
                    try {
                        rootNode.recycle()
                    } catch (_: Exception) {
                    }
                } ?: continue

                val urlOrIdentifier = extracted.url.ifBlank {
                    if (extracted.pageTitle.isNotBlank()) "$targetPkg (${extracted.pageTitle.take(35)})" else targetPkg
                }

                if (db.temporaryWhitelistDao().isWhitelisted(targetPkg) ||
                    db.temporaryWhitelistDao().isWhitelisted(urlOrIdentifier) ||
                    extracted.url.contains("antigravity.google.com", ignoreCase = true)
                ) {
                    continue
                }

                // UPGRADE 1: Block YouTube Shorts & Reels automatically while keeping regular YouTube open
                val urlLower = extracted.url.lowercase()
                if (prefs.blockYoutubeShortsAndReels) {
                    val isShortsUrl = urlLower.contains("youtube.com/shorts") ||
                        urlLower.contains("youtu.be/shorts") ||
                        urlLower.contains("instagram.com/reels") ||
                        urlLower.contains("facebook.com/reel")
                    val isYoutubeAppShortsPlayer = (targetPkg.contains("youtube") && extracted.isShortsPlayerActive)

                    if (isShortsUrl || isYoutubeAppShortsPlayer) {
                        val elapsedMs = SystemClock.elapsedRealtime() - scanStart
                        triggerBlockOverlay(
                            targetAppOrUrl = urlOrIdentifier,
                            reason = "נחסם אוטומטית: צפייה ב-YouTube Shorts / Reels חסומה לשמירת העיניים (יוטיוב רגיל פתוח)",
                            pageTitle = extracted.pageTitle.ifBlank { "YouTube Shorts" },
                            textSnippet = extracted.visibleText.take(400),
                            isTextBlock = false,
                            inferenceTimeMs = elapsedMs,
                            navigateBack = true
                        )
                        return@launch
                    }
                }

                // UPGRADE 2: Google-Only Search Engine Policy + Strict SafeSearch Enforcement
                if (prefs.enforceSafeSearchAndRestrictedMode) {
                    val blockedSearchDomain = findBlockedAlternativeSearchEngine(urlLower)
                    if (blockedSearchDomain != null) {
                        val elapsedMs = SystemClock.elapsedRealtime() - scanStart
                        triggerBlockOverlay(
                            targetAppOrUrl = urlOrIdentifier,
                            reason = "נחסם ($blockedSearchDomain): מותר שימוש במנוע החיפוש של גוגל בלבד (עם חיפוש בטוח)",
                            pageTitle = blockedSearchDomain,
                            textSnippet = "",
                            isTextBlock = false,
                            inferenceTimeMs = elapsedMs,
                            navigateBack = true
                        )
                        return@launch
                    }

                    if (BROWSER_PACKAGES.contains(targetPkg) && isUnfilteredGoogleSearchUrl(urlLower)) {
                        enforceSafeSearchRedirectInBrowser(targetPkg, extracted.url)
                        return@launch
                    }
                }

                // UPGRADE 3: Block Grok (grok.com, x.com/i/grok, Grok tab inside X app) & Uncensored NSFW AI Generators
                if (prefs.blockUncensoredAiAndGrok) {
                    val blockedAiSite = findBlockedUncensoredAiSite(urlLower, targetPkg, extracted)
                    if (blockedAiSite != null) {
                        val elapsedMs = SystemClock.elapsedRealtime() - scanStart
                        triggerBlockOverlay(
                            targetAppOrUrl = urlOrIdentifier,
                            reason = "נחסם ($blockedAiSite): מחולל AI ללא צנזורה (Grok / Uncensored AI) חסום לשמירת העיניים",
                            pageTitle = blockedAiSite,
                            textSnippet = "",
                            isTextBlock = false,
                            inferenceTimeMs = elapsedMs,
                            navigateBack = true
                        )
                        return@launch
                    }
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
                    return@launch
                }
            }
        }
    }

    private fun performVisualAiScanIfEligible(pkg: String) {
        if (!powerManager.isInteractive) return
        if (prefs.isFilterPausedForTesting) return
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

            // Check if current app or URL is already in the temporary whitelist (from 15-min Sandbox Bypass or 24h AI Approval)
            val rootNodeForContext = rootInActiveWindow
            val screenContext = try {
                if (rootNodeForContext != null) extractScreenContent(rootNodeForContext, pkg)
                else ScreenExtractedContent("", "", "")
            } catch (_: Exception) {
                ScreenExtractedContent("", "", "")
            } finally {
                try {
                    rootNodeForContext?.recycle()
                } catch (_: Exception) {
                }
            }

            val currentIdentifier = screenContext.url.ifBlank {
                if (screenContext.pageTitle.isNotBlank()) "$pkg (${screenContext.pageTitle.take(30)})" else pkg
            }

            if (db.temporaryWhitelistDao().isWhitelisted(pkg) ||
                db.temporaryWhitelistDao().isWhitelisted(currentIdentifier) ||
                screenContext.url.contains("antigravity.google.com", ignoreCase = true)
            ) {
                return
            }

            // Battery-Saving Visual Fingerprint Check:
            // If the screen pixels haven't changed since the last scan, skip OCR & AI inference completely!
            val currentSignature = objectAndSkinDetector?.computeFastBitmapSignature(softwareBitmap) ?: 0L
            if (currentSignature != 0L && currentSignature == lastBitmapSignature) {
                return
            }
            lastBitmapSignature = currentSignature

            // SMART BATTERY FUNNEL:
            // Step 1: Fast skin pre-check (<0.5ms) before running heavy ML Kit OCR or ObjectDetector
            val quickFullSkinRatio = objectAndSkinDetector?.calculateSkinToneRatio(softwareBitmap) ?: 0f
            val domHasEnoughText = screenContext.visibleText.length > 80
            val shouldRunOcr = !domHasEnoughText || quickFullSkinRatio >= 0.04f || pkg.contains("youtube")

            // STEP A: On-Device Visual OCR Scan (runs only when needed: video/image overlays or low DOM text)
            val ocrText = if (shouldRunOcr) {
                ocrScanner?.extractTextFromBitmap(softwareBitmap).orEmpty()
            } else {
                ""
            }
            if (ocrText.isNotBlank()) {
                val ocrMatched = findMatchingKeyword(ocrText, "")
                if (ocrMatched != null) {
                    val ocrElapsed = SystemClock.elapsedRealtime() - totalStart
                    showDebugToastIfEnabled("OCR Blocked: '${ocrMatched.keyword}' (${ocrElapsed}ms)")
                    val fullContextSnippet = "${screenContext.pageTitle}\n${screenContext.visibleText}\n$ocrText".take(1200)
                    triggerBlockOverlay(
                        targetAppOrUrl = currentIdentifier,
                        reason = "נחסם עקב זיהוי טקסט בתוך התמונה/וידאו (OCR): ${ocrMatched.keyword}",
                        pageTitle = screenContext.pageTitle.ifBlank { currentIdentifier },
                        textSnippet = fullContextSnippet,
                        isTextBlock = true,
                        inferenceTimeMs = ocrElapsed,
                        navigateBack = true
                    )
                    return
                }
            }

            // STEP B: Smart Battery-Safe TFLite Visual NSFW Model
            val classifier = nsfwClassifier
            if (classifier == null || !classifier.isAvailable) {
                val fallbackElapsed = SystemClock.elapsedRealtime() - totalStart
                showDebugToastIfEnabled("Scan: ${fallbackElapsed}ms | OCR OK | AI Model Loading")
                return
            }

            // Only run ML Kit ObjectDetector when there is noticeable skin tone on screen (>= 3.5%)
            val detectedObjectCrops = if (quickFullSkinRatio >= 0.035f) {
                objectAndSkinDetector?.detectObjectCrops(softwareBitmap) ?: emptyList()
            } else {
                emptyList()
            }

            val pornThresh = prefs.pornThreshold
            val sexyThresh = prefs.sexyThreshold
            val hentaiThresh = prefs.hentaiThreshold

            val result = classifier.classifyFullScreenBitmap(
                fullScreenSoftwareBitmap = softwareBitmap,
                detectedObjectCrops = detectedObjectCrops,
                skinDetector = objectAndSkinDetector,
                pornThresh = pornThresh,
                sexyThresh = sexyThresh,
                hentaiThresh = hentaiThresh
            )

            val totalElapsed = SystemClock.elapsedRealtime() - totalStart
            val isViolation = result.isViolation(pornThresh, sexyThresh, hentaiThresh)
            val statusLabel = if (isViolation) "BLOCKED" else "Safe"

            showDebugToastIfEnabled(
                "Scan: ${totalElapsed}ms | Porn: ${(result.porn * 100).toInt()}% | Sexy: ${(result.sexy * 100).toInt()}% | Skin: ${(result.skinRatio * 100).toInt()}% | $statusLabel"
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

        // Delay 180ms after GLOBAL_ACTION_BACK so the system Back action finishes closing the browser page
        // BEFORE BlockOverlayActivity opens on top (prevents Back action from accidentally dismissing the red overlay!)
        val launchDelayMs = if (navigateBack) 180L else 0L
        mainHandler.postDelayed({
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
        }, launchDelayMs)
    }

    private fun showDebugToastIfEnabled(message: String) {
        if (!prefs.showDebugToast) return
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isUnfilteredGoogleSearchUrl(urlLower: String): Boolean {
        return (urlLower.contains("google.com/search") || urlLower.contains("google.co.il/search")) &&
            !urlLower.contains("safe=active")
    }

    private fun findBlockedAlternativeSearchEngine(urlLower: String): String? {
        if (urlLower.isBlank()) return null
        for (engine in BLOCKED_ALTERNATIVE_SEARCH_DOMAINS) {
            if (urlLower == engine ||
                urlLower.startsWith("$engine/") ||
                urlLower.startsWith("https://$engine") ||
                urlLower.startsWith("http://$engine") ||
                urlLower.startsWith("www.$engine") ||
                urlLower.contains(".$engine/") ||
                urlLower.contains("://$engine")
            ) {
                return engine
            }
        }
        return null
    }

    private fun findBlockedUncensoredAiSite(
        urlLower: String,
        pkg: String,
        extracted: ScreenExtractedContent
    ): String? {
        // 1. Direct URL check for Grok & Uncensored AI websites
        if (urlLower.isNotBlank()) {
            for (domain in BLOCKED_UNCENSORED_AI_DOMAINS) {
                if (urlLower.contains(domain)) {
                    return domain
                }
            }
        }

        // 2. Inside X / Twitter app or web: check if user opened the embedded Grok tab/chat
        if (pkg == "com.twitter.android" || urlLower.contains("x.com") || urlLower.contains("twitter.com")) {
            val titleAndText = "${extracted.pageTitle} ${extracted.visibleText}".lowercase()
            if (titleAndText.contains("ask grok") ||
                titleAndText.contains("grok 2") ||
                titleAndText.contains("grok 3") ||
                titleAndText.contains("x.com/i/grok") ||
                extracted.pageTitle.trim().equals("grok", ignoreCase = true)
            ) {
                return "Grok AI (X.com)"
            }
        }
        return null
    }

    private fun enforceSafeSearchRedirectInBrowser(browserPkg: String, rawUrl: String) {
        try {
            val fixedUrl = buildString {
                if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                    append("https://")
                }
                append(rawUrl)
                if (rawUrl.contains("?")) {
                    append("&safe=active")
                } else {
                    append("?safe=active")
                }
            }

            mainHandler.post {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fixedUrl)).apply {
                        setPackage(browserPkg)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(Browser.EXTRA_APPLICATION_ID, browserPkg)
                    }
                    startActivity(intent)
                    showDebugToastIfEnabled("SafeSearch: הופעל חיפוש בטוח אוטומטית 🛡️")
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private data class ScreenExtractedContent(
        val url: String,
        val pageTitle: String,
        val visibleText: String,
        val isShortsPlayerActive: Boolean = false
    )

    private fun extractScreenContent(root: AccessibilityNodeInfo, pkg: String): ScreenExtractedContent {
        var detectedUrl = ""
        var detectedTitle = ""
        var isShortsActive = false
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

        // Detect YouTube app Shorts player view IDs
        if (pkg.contains("youtube")) {
            for (shortsId in YOUTUBE_SHORTS_VIEW_IDS) {
                val nodes = root.findAccessibilityNodeInfosByViewId(shortsId)
                if (!nodes.isNullOrEmpty()) {
                    isShortsActive = true
                    nodes.forEach {
                        try {
                            it.recycle()
                        } catch (_: Exception) {
                        }
                    }
                    break
                }
            }
        }

        // Deep DOM traversal up to 650 nodes (including non-visible-flagged WebView DOM children in Chrome YouTube Shorts & X.com!)
        var visitedCount = 0
        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || visitedCount > 650) return
            visitedCount++

            val viewIdRes = node.viewIdResourceName.orEmpty()
            if (viewIdRes.contains("reel_player") || viewIdRes.contains("shorts_player")) {
                isShortsActive = true
            }

            // Auto-close intrusive popup ad banners (e.g. Google Ads / Taboola close buttons)
            val descLower = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            if (node.isClickable && (
                    descLower == "close ad" ||
                    descLower == "סגור מודעה" ||
                    descLower == "סגירת המודעה" ||
                    descLower == "dismiss ad" ||
                    viewIdRes.endsWith(":id/ad_close_button") ||
                    viewIdRes.endsWith(":id/close_ad")
                )
            ) {
                try {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Exception) {
                }
            }

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
            visibleText = textBuilder.toString().trim(),
            isShortsPlayerActive = isShortsActive
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
        objectAndSkinDetector?.close()
    }

    companion object {
        private const val TAG = "FilterAccessService"

        private const val TEXT_SCAN_THROTTLE_MS = 550L
        private const val DEBOUNCE_VISUAL_SCAN_MS = 950L
        private const val HARD_SCREENSHOT_COOLDOWN_MS = 2500L

        val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.mi.globalbrowser",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.duckduckgo.mobile.android"
        )

        private val YOUTUBE_SHORTS_VIEW_IDS = listOf(
            "com.google.android.youtube:id/reel_player_page_container",
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_watch_Fragment_root"
        )

        val BLOCKED_ALTERNATIVE_SEARCH_DOMAINS = listOf(
            "duckduckgo.com",
            "yandex.com",
            "yandex.ru",
            "ya.ru",
            "bing.com",
            "search.yahoo.com",
            "yahoo.com",
            "search.brave.com",
            "ecosia.org",
            "qwant.com",
            "startpage.com",
            "baidu.com",
            "ask.com",
            "aol.com",
            "dogpile.com",
            "metacrawler.com",
            "swisscows.com",
            "mojeek.com",
            "searx.be",
            "you.com"
        )

        val BLOCKED_SEARCH_ENGINE_PACKAGES = setOf(
            "com.duckduckgo.mobile.android",
            "com.yandex.browser",
            "ru.yandex.searchplugin",
            "com.microsoft.bing",
            "com.yahoo.mobile.client.android.search",
            "com.ecosia.android"
        )

        val BLOCKED_UNCENSORED_AI_DOMAINS = listOf(
            "grok.com",
            "x.com/i/grok",
            "twitter.com/i/grok",
            "x.ai",
            "civitai.com",
            "janitorai.com",
            "crushon.ai",
            "spicychat.ai",
            "chub.ai",
            "venus.chub.ai",
            "character.ai",
            "beta.character.ai",
            "joyland.ai",
            "candy.ai",
            "dreamgf.ai",
            "Promptchan.ai",
            "promptchan.ai",
            "seduced.ai",
            "unfilteredai.com",
            "perchance.org",
            "seaart.ai",
            "tensor.art",
            "yodayo.com",
            "sakura.fm",
            "pephop.ai",
            "muah.ai",
            "kupid.ai",
            "soulgen.net",
            "pornpen.ai",
            "undress.ai"
        )

        val BLOCKED_UNCENSORED_AI_PACKAGES = setOf(
            "ai.x.grok",
            "ai.character.app",
            "com.chai.app",
            "ai.replika.app",
            "com.crushon.ai",
            "com.seaart.ai",
            "com.polyai.app",
            "com.talkie.ai"
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
