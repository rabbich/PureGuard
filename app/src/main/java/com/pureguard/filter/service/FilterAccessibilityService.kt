package com.pureguard.filter.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.VpnService
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
import com.pureguard.filter.admin.LockdownManager
import com.pureguard.filter.ai.NsfwClassifier
import com.pureguard.filter.ai.ObjectAndSkinDetector
import com.pureguard.filter.ai.ScreenOcrScanner
import com.pureguard.filter.data.BlockLogEntity
import com.pureguard.filter.data.BlockedAppEntity
import com.pureguard.filter.data.BlockedKeywordEntity
import com.pureguard.filter.data.PreferencesManager
import com.pureguard.filter.data.PureGuardDatabase
import com.pureguard.filter.data.TrustedSafeDomains
import com.pureguard.filter.data.Ut1LingerieDomainFilter
import com.pureguard.filter.ui.AccessibilityBlockOverlayManager
import com.pureguard.filter.ui.BlockOverlayActivity
import com.pureguard.filter.util.BlockDiagnosticReporter
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
    private lateinit var keyguardManager: KeyguardManager
    private var nsfwClassifier: NsfwClassifier? = null
    private var ocrScanner: ScreenOcrScanner? = null
    private var objectAndSkinDetector: ObjectAndSkinDetector? = null
    private var overlayManager: AccessibilityBlockOverlayManager? = null
    private var ut1LingerieFilter: Ut1LingerieDomainFilter? = null

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
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        overlayManager = AccessibilityBlockOverlayManager(this, serviceScope)

        // If Device Owner is enabled, natively hide/disable Incognito button inside Chrome
        try {
            LockdownManager(applicationContext).syncChromeIncognitoPolicy(prefs.blockIncognitoMode)
        } catch (_: Throwable) {
        }

        serviceScope.launch(Dispatchers.IO) {
            nsfwClassifier = NsfwClassifier(applicationContext)
            ocrScanner = ScreenOcrScanner()
            objectAndSkinDetector = ObjectAndSkinDetector()
            ut1LingerieFilter = Ut1LingerieDomainFilter(applicationContext)
        }

        // Auto-resume Split-Tunnel AdGuard Family DNS VPN if user already authorized it
        if (prefs.isAutoDnsVpnEnabled && !AdGuardDnsVpnService.isRunning) {
            try {
                if (VpnService.prepare(applicationContext) == null) {
                    startService(Intent(applicationContext, AdGuardDnsVpnService::class.java))
                }
            } catch (_: Throwable) {
            }
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

        // Layer 2: Immediate + Trailing Throttled visual AI + OCR check
        // (Unlike debounce, this NEVER gets postponed for 20s by continuous ad animations or scrolling!)
        serviceScope.launch(Dispatchers.Default) {
            visualScanTrigger
                .collectLatest { pkg ->
                    val elapsed = SystemClock.elapsedRealtime() - lastScreenshotElapsed
                    val waitMs = (HARD_SCREENSHOT_COOLDOWN_MS - elapsed).coerceAtLeast(180L)
                    if (elapsed < HARD_SCREENSHOT_COOLDOWN_MS) {
                        kotlinx.coroutines.delay(waitMs)
                    } else {
                        kotlinx.coroutines.delay(180L)
                    }
                    performVisualAiScanIfEligible(pkg)
                }
        }

        // Battery-Safe Video & Dynamic Ad Watchdog:
        // Samples when the user is inside a video app (YouTube/TikTok) OR when content changed recently (<15s ago)
        serviceScope.launch(Dispatchers.Default) {
            while (true) {
                kotlinx.coroutines.delay(2200L)
                val pkg = currentForegroundPackage
                val now = SystemClock.elapsedRealtime()
                val isVideoApp = pkg.contains("youtube") || pkg.contains("musically") || pkg.contains("trill")
                val hadRecentActivity = (now - lastContentEventElapsed) < 15000L
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

        // Never inspect our own package, system UI, or keyboards/background services
        if (pkg == packageName || isIgnoredSystemOrKeyboardPackage(pkg)) return

        // Screen off or Lock Screen check: zero work when screen is off or locked
        if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) return

        // Quick 1-Click Sandbox Testing Pause Toggle (only works when isLockdownEnabled == false)
        if (prefs.isFilterPausedForTesting) return

        // Verify actual visible foreground window package so background Xiaomi events never scan WhatsApp!
        val activeRootNode = try { rootInActiveWindow } catch (_: Throwable) { null }
        val activeWindowPkg = try {
            activeRootNode?.packageName?.toString()
        } finally {
            try { activeRootNode?.recycle() } catch (_: Throwable) {}
        }
        if (!activeWindowPkg.isNullOrBlank() && !isMonitoredContentPackage(activeWindowPkg)) {
            currentForegroundPackage = activeWindowPkg
            return
        }

        val effectivePkg = if (!activeWindowPkg.isNullOrBlank()) activeWindowPkg else pkg
        currentForegroundPackage = effectivePkg

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

        // Immediate Incognito / Private Tab click & event interception inside browsers
        if (prefs.blockIncognitoMode && BROWSER_PACKAGES.contains(pkg)) {
            val evTextLower = buildString {
                event.text?.forEach { append(it).append(' ') }
                event.contentDescription?.let { append(it) }
            }.lowercase().trim()

            if (evTextLower.isNotEmpty()) {
                val isClickedIncognitoMenu = (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) &&
                    (evTextLower.contains("גלישה בסתר") ||
                        evTextLower.contains("כרטיסייה פרטית") ||
                        evTextLower.contains("גלישה פרטית") ||
                        evTextLower.contains("מצב אנונימי") ||
                        evTextLower.contains("כרטיסייה אנונימית") ||
                        evTextLower.contains("incognito") ||
                        evTextLower.contains("private tab") ||
                        evTextLower.contains("secret mode"))

                val isActiveIncognitoEvent = !evTextLower.contains("חדשה") &&
                    !evTextLower.contains("new ") &&
                    (evTextLower.contains("עברת לגלישה בסתר") ||
                        evTextLower.contains("כעת באפשרותך לגלוש באופן פרטי") ||
                        evTextLower.contains("לא יישמרו ב-chrome") ||
                        evTextLower.contains("you've gone incognito"))

                if (isClickedIncognitoMenu || isActiveIncognitoEvent) {
                    triggerBlockOverlay(
                        targetAppOrUrl = pkg,
                        reason = "נחסם: גלישה בסתר (Incognito) חסומה כיוון שהיא מונעת את פעולת סינון התמונות. אנא גלוש בכרטיסייה רגילה.",
                        pageTitle = "גלישה בסתר",
                        textSnippet = "",
                        isTextBlock = false,
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
                if (isMonitoredContentPackage(effectivePkg)) {
                    scheduleTextAndUrlScan(effectivePkg, forceImmediate = true)
                    // Fast 250ms visual scan on new window/image opened (catches Grok or File Manager images before the user can stare or screenshot!)
                    serviceScope.launch(Dispatchers.Default) {
                        kotlinx.coroutines.delay(250L)
                        performVisualAiScanIfEligible(effectivePkg, bypassCooldownForNewWindow = true)
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isMonitoredContentPackage(effectivePkg)) {
                    scheduleTextAndUrlScan(effectivePkg, forceImmediate = false)
                    visualScanTrigger.tryEmit(effectivePkg)
                }
            }

            else -> Unit
        }
    }

    private fun isIgnoredSystemOrKeyboardPackage(pkg: String): Boolean {
        if (pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") ||
            pkg.contains("keyboard") ||
            pkg.contains("honeyboard") ||
            pkg.contains("swiftkey") ||
            pkg == "com.miui.securitycenter" ||
            pkg == "com.miui.powerkeeper" ||
            pkg == "com.miui.notification" ||
            pkg == "com.miui.home" ||
            pkg == "com.miui.aod" ||
            pkg == "com.miui.systemAdSolution"
        ) {
            return true
        }
        return false
    }

    /**
     * Focused Content-App Architecture:
     * Scans ONLY actual browsers, video apps, social media, and galleries/file managers.
     * NEVER scans WhatsApp, Trello, ChatGPT, system services, keyboards, or productivity tools!
     */
    private fun isMonitoredContentPackage(pkg: String): Boolean {
        if (pkg.isBlank() || pkg == packageName || isIgnoredSystemOrKeyboardPackage(pkg)) {
            return false
        }
        if (WHITELIST_SAFE_PACKAGES.contains(pkg) || TrustedSafeDomains.SAFE_UTILITY_PACKAGES.contains(pkg)) {
            return false
        }
        if (BROWSER_PACKAGES.contains(pkg) || MONITORED_CONTENT_PACKAGES.contains(pkg)) {
            return true
        }
        val p = pkg.lowercase()
        return p.contains("browser") ||
            p.contains("chrome") ||
            p.contains("firefox") ||
            p.contains("opera") ||
            p.contains("brave") ||
            p.contains("youtube") ||
            p.contains("telegram") ||
            p.contains("twitter") ||
            p.contains("instagram") ||
            p.contains("facebook") ||
            p.contains("tiktok") ||
            p.contains("musically") ||
            p.contains("trill") ||
            p.contains("reddit") ||
            p.contains("gallery") ||
            p.contains("photos") ||
            p.contains("fileexplorer") ||
            p.contains("filemanager") ||
            p.contains("documentsui") ||
            p.contains("videoplayer") ||
            p.contains("vlc") ||
            p.contains("grok")
    }

    private fun findMatchingKeyword(fullText: String, urlText: String): BlockedKeywordEntity? {
        // Strip out Kodeshield's own diagnostic score pattern ("Porn: 65%", "Sexy: 2%") so logs never self-block!
        val fullTextLower = fullText.lowercase().replace(DIAGNOSTIC_LOG_SCORE_REGEX, " ")
        val urlLower = urlText.lowercase()
        val isModerateShoppingProfile = (prefs.filterProfileLevel == PreferencesManager.PROFILE_MODERATE_SHOPPING)

        // Extract only continuous handle/hashtag/URL tokens (e.g. "@NUDEgirlsTongueKiss") for compact matching
        // so normal two-word sentences never glue together into false positives!
        val gluedTokensLower = fullTextLower
            .split(Regex("\\s+"))
            .filter { it.startsWith("@") || it.startsWith("#") || it.length >= 14 || it.contains("_") }
            .joinToString(" ") { it.replace("_", "").replace("-", "") }

        for (item in cachedKeywords) {
            val kw = item.keyword.lowercase().trim()
            if (kw.isEmpty()) continue

            // In Level 2 ("מותאם לנשים - קניות ואופנה פתוחים"), allow clothing/lingerie/swimwear shopping terms
            if (isModerateShoppingProfile && SHOPPING_ALLOWED_KEYWORDS.contains(kw)) {
                continue
            }

            // Word-boundary check for short sensitive words ("סקס", "sex") so "סקסופון" / "unisex" are never falsely blocked
            if (kw == "סקס") {
                if (HEBREW_SEX_WORD_REGEX.containsMatchIn(fullTextLower)) {
                    return item
                }
                continue
            }
            if (kw == "sex") {
                if (ENGLISH_SEX_WORD_REGEX.containsMatchIn(fullTextLower)) {
                    return item
                }
                continue
            }

            val compactKw = kw.replace(" ", "").replace("_", "").replace("-", "")
            if (item.isDomain) {
                if (urlLower.contains(kw) || isStandaloneKeywordMatch(fullTextLower, kw)) {
                    return item
                }
            } else {
                if (isStandaloneKeywordMatch(fullTextLower, kw) ||
                    (compactKw.length >= 8 && gluedTokensLower.contains(compactKw))
                ) {
                    return item
                }
            }
        }
        return null
    }

    /**
     * Strict Word-Boundary matcher for Hebrew & English keywords:
     * Prevents false positives where a short forbidden word appears inside an innocent longer word
     * (e.g. inside news articles or Telegram channels like Abu Ali Express).
     * Allows standard single-letter Hebrew prefixes (ה, ל, ב, מ, ו, כ, ש) before Hebrew words.
     */
    private fun isStandaloneKeywordMatch(textLower: String, kwLower: String): Boolean {
        var idx = textLower.indexOf(kwLower)
        if (idx == -1) return false

        val isHebrewKw = kwLower.any { it in '\u0590'..'\u05FF' }
        val hebrewPrefixes = "הלבמוכש"

        while (idx != -1) {
            val endIdx = idx + kwLower.length
            val afterOk = (endIdx >= textLower.length) || !Character.isLetterOrDigit(textLower[endIdx])

            val beforeOk = if (idx == 0) {
                true
            } else {
                val prevChar = textLower[idx - 1]
                if (!Character.isLetterOrDigit(prevChar)) {
                    true
                } else if (isHebrewKw && hebrewPrefixes.contains(prevChar)) {
                    // Valid only if the prefix itself is at the start of the word!
                    idx - 1 == 0 || !Character.isLetterOrDigit(textLower[idx - 2])
                } else {
                    false
                }
            }

            if (beforeOk && afterOk) {
                return true
            }
            idx = textLower.indexOf(kwLower, idx + 1)
        }
        return false
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

                // CRITICAL SECURITY FIX: Block Incognito / Private Browsing tabs!
                // Incognito sets Android FLAG_SECURE which blacks out screenshots and blinds Visual AI.
                if (prefs.blockIncognitoMode && BROWSER_PACKAGES.contains(targetPkg) && extracted.isIncognitoActive) {
                    val elapsedMs = SystemClock.elapsedRealtime() - scanStart
                    triggerBlockOverlay(
                        targetAppOrUrl = targetPkg,
                        reason = "נחסם: גלישה בסתר (Incognito) חסומה כיוון שהיא מונעת את פעולת סינון התמונות. אנא גלוש בכרטיסייה רגילה.",
                        pageTitle = "גלישה בסתר",
                        textSnippet = "",
                        isTextBlock = false,
                        inferenceTimeMs = elapsedMs,
                        navigateBack = true
                    )
                    return@launch
                }

                // Trusted Kosher, Banking, Health, Government & Productivity Whitelist (0% battery + 100% privacy)
                if (TrustedSafeDomains.isTrustedSafeUrl(extracted.url)) {
                    continue
                }

                // UT1 Open-Source Lingerie & Swimwear Domain Filter (active in Strict/Custom profiles; bypassed in Women's Shopping profile)
                if (prefs.filterProfileLevel != PreferencesManager.PROFILE_MODERATE_SHOPPING) {
                    val matchedLingerieDomain = ut1LingerieFilter?.findBlockedDomain(extracted.url)
                    if (matchedLingerieDomain != null) {
                        val elapsedMs = SystemClock.elapsedRealtime() - scanStart
                        triggerBlockOverlay(
                            targetAppOrUrl = urlOrIdentifier,
                            reason = "נחסם ($matchedLingerieDomain): אתר הלבשה תחתונה/בגדי ים (מאגר UT1) חסום בפרופיל שמירת עיניים",
                            pageTitle = matchedLingerieDomain,
                            textSnippet = "",
                            isTextBlock = false,
                            inferenceTimeMs = elapsedMs,
                            navigateBack = true
                        )
                        return@launch
                    }
                }

                if (db.temporaryWhitelistDao().isWhitelisted(targetPkg) ||
                    db.temporaryWhitelistDao().isWhitelisted(urlOrIdentifier)
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

    private fun performVisualAiScanIfEligible(pkg: String, bypassCooldownForNewWindow: Boolean = false) {
        if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) return
        if (prefs.isFilterPausedForTesting) return

        val activeRoot = try { rootInActiveWindow } catch (_: Throwable) { null }
        val activeWinPkg = try {
            activeRoot?.packageName?.toString()
        } finally {
            try { activeRoot?.recycle() } catch (_: Throwable) {}
        }
        val targetPkg = if (!activeWinPkg.isNullOrBlank()) activeWinPkg else pkg
        if (!isMonitoredContentPackage(targetPkg)) return

        val now = SystemClock.elapsedRealtime()
        val effectiveCooldown = if (bypassCooldownForNewWindow) 650L else HARD_SCREENSHOT_COOLDOWN_MS
        if (now - lastScreenshotElapsed < effectiveCooldown) {
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
                            // Error code 5 (Android 14+ ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) or 1 (Android 11-13 INTERNAL_ERROR on FLAG_SECURE)
                            if ((errorCode == 5 || errorCode == 1) && prefs.blockIncognitoMode && BROWSER_PACKAGES.contains(pkg)) {
                                triggerBlockOverlay(
                                    targetAppOrUrl = pkg,
                                    reason = "נחסם: גלישה בסתר / חלון מאובטח חסום כיוון שהוא מונע את פעולת סינון התמונות. אנא גלוש בכרטיסייה רגילה.",
                                    pageTitle = "גלישה בסתר",
                                    textSnippet = "",
                                    isTextBlock = false,
                                    inferenceTimeMs = 0L,
                                    navigateBack = true
                                )
                            }
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
            if (keyguardManager.isKeyguardLocked) return
            val rootNodeForContext = rootInActiveWindow
            val actualRootPkg = rootNodeForContext?.packageName?.toString().orEmpty()
            if (actualRootPkg.isNotBlank() && !isMonitoredContentPackage(actualRootPkg)) {
                try { rootNodeForContext?.recycle() } catch (_: Exception) {}
                return
            }
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

            // Block Incognito / FLAG_SECURE blacked-out browser screenshots immediately
            if (prefs.blockIncognitoMode && BROWSER_PACKAGES.contains(pkg)) {
                if (screenContext.isIncognitoActive || isPitchBlackSecureBrowserFrame(softwareBitmap)) {
                    triggerBlockOverlay(
                        targetAppOrUrl = pkg,
                        reason = "נחסם: גלישה בסתר (Incognito) חסומה כיוון שהיא חוסמת צילום מסך ומעוורת את סינון התמונות. אנא גלוש בכרטיסייה רגילה.",
                        pageTitle = "גלישה בסתר",
                        textSnippet = "",
                        isTextBlock = false,
                        inferenceTimeMs = SystemClock.elapsedRealtime() - totalStart,
                        navigateBack = true
                    )
                    return
                }
            }

            // Trusted Kosher, Banking, Health, Government & Productivity Whitelist (skip OCR & AI -> 0% battery + 100% privacy)
            if (TrustedSafeDomains.isTrustedSafeUrl(screenContext.url)) {
                return
            }

            if (db.temporaryWhitelistDao().isWhitelisted(pkg) ||
                db.temporaryWhitelistDao().isWhitelisted(currentIdentifier)
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
                        navigateBack = true,
                        capturedBitmap = softwareBitmap
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
                    navigateBack = true,
                    capturedBitmap = softwareBitmap
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
        navigateBack: Boolean,
        capturedBitmap: Bitmap? = null
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockOverlayElapsed < 1200L) {
            return
        }
        lastBlockOverlayElapsed = now

        // Save snapshot & full technical diagnostic report before navigating back or recycling the bitmap
        BlockDiagnosticReporter.saveBlockSnapshot(
            context = applicationContext,
            bitmap = capturedBitmap,
            targetUrlOrApp = targetAppOrUrl,
            blockReason = reason,
            pageTitle = pageTitle,
            textSnippet = textSnippet
        )

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

        // Draw universal TYPE_ACCESSIBILITY_OVERLAY directly on top of all apps (works 100% on Xiaomi, Samsung, Pixel, etc.)
        overlayManager?.showBlockOverlay(
            targetUrlOrApp = targetAppOrUrl,
            blockReason = reason,
            pageTitle = pageTitle,
            textSnippet = textSnippet,
            isTextBlock = isTextBlock
        )
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
        val isShortsPlayerActive: Boolean = false,
        val isIncognitoActive: Boolean = false
    )

    /**
     * Detects when Android SurfaceFlinger zeroes out a browser window due to FLAG_SECURE (Incognito mode).
     * Unlike normal Dark Mode (which still has white/gray text, URL bar, and icons),
     * a FLAG_SECURE blacked-out window renders literal RGB(0,0,0) across the entire content area.
     */
    private fun isPitchBlackSecureBrowserFrame(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 40 || h < 80) return false
        var pureZeroCount = 0
        var total = 0
        // Sample the middle 50% web content zone (y: 25%..75%, x: 15%..85%), avoiding status/nav/toolbar edges
        for (gy in 0..8) {
            val y = ((h * 0.25f) + (h * 0.50f * gy / 8f)).toInt().coerceIn(0, h - 1)
            for (gx in 0..8) {
                val x = ((w * 0.15f) + (w * 0.70f * gx / 8f)).toInt().coerceIn(0, w - 1)
                val p = bitmap.getPixel(x, y)
                val rgb = p and 0x00FFFFFF
                if (rgb == 0) {
                    pureZeroCount++
                }
                total++
            }
        }
        return total > 0 && (pureZeroCount.toFloat() / total.toFloat()) >= 0.85f
    }

    private fun extractScreenContent(root: AccessibilityNodeInfo, pkg: String): ScreenExtractedContent {
        var detectedUrl = ""
        var detectedTitle = ""
        var isShortsActive = false
        var isIncognitoActive = false
        val textBuilder = StringBuilder()

        val isBrowser = BROWSER_PACKAGES.contains(pkg)
        if (isBrowser) {
            for (incogId in INCOGNITO_VIEW_IDS) {
                val nodes = root.findAccessibilityNodeInfosByViewId(incogId)
                if (!nodes.isNullOrEmpty()) {
                    isIncognitoActive = true
                    nodes.forEach {
                        try { it.recycle() } catch (_: Exception) {}
                    }
                    break
                }
            }
        }

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
            if (isBrowser && !isIncognitoActive && descLower.isNotEmpty() && !descLower.contains("חדשה") && !descLower.contains("new ")) {
                for (marker in INCOGNITO_DESC_MARKERS) {
                    if (descLower.contains(marker)) {
                        isIncognitoActive = true
                        break
                    }
                }
            }
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
                if (isBrowser && !isIncognitoActive) {
                    val textLower = nodeText.lowercase().trim()
                    for (marker in INCOGNITO_PAGE_MARKERS) {
                        if (textLower.contains(marker)) {
                            isIncognitoActive = true
                            break
                        }
                    }
                }
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
            isShortsPlayerActive = isShortsActive,
            isIncognitoActive = isIncognitoActive
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
        overlayManager?.dismissOverlayImmediate()
        serviceScope.cancel()
        screenshotExecutor.shutdownNow()
        nsfwClassifier?.close()
        ocrScanner?.close()
        objectAndSkinDetector?.close()
    }

    companion object {
        private const val TAG = "FilterAccessService"

        private const val TEXT_SCAN_THROTTLE_MS = 450L
        private const val DEBOUNCE_VISUAL_SCAN_MS = 500L
        private const val HARD_SCREENSHOT_COOLDOWN_MS = 1300L

        // Word-boundary regexes for short sensitive terms so "סקסופון" or "unisex" are NOT falsely blocked
        private val HEBREW_SEX_WORD_REGEX = Regex("(^|[\\s\\p{Punct}])([הלבמוכש]?סקס)($|[\\s\\p{Punct}])")
        private val ENGLISH_SEX_WORD_REGEX = Regex("\\bsex\\b", RegexOption.IGNORE_CASE)
        private val DIAGNOSTIC_LOG_SCORE_REGEX = Regex("\\b(porn|sexy|hentai|neutral|skin):\\s*\\d+%?", RegexOption.IGNORE_CASE)

        private val INCOGNITO_VIEW_IDS = listOf(
            "com.android.chrome:id/incognito_badge",
            "com.android.chrome:id/incognito_tabs_count",
            "com.android.chrome:id/new_tab_incognito_container",
            "com.android.chrome:id/new_tab_incognito_title",
            "com.android.chrome:id/new_tab_incognito_icon",
            "com.brave.browser:id/incognito_badge",
            "org.mozilla.firefox:id/private_browsing_indicator",
            "com.sec.android.app.sbrowser:id/secret_mode_icon"
        )

        private val INCOGNITO_DESC_MARKERS = listOf(
            "כרטיסיות גלישה בסתר",
            "כרטיסיות אנונימיות",
            "כרטיסיות פרטיות",
            "close all incognito tabs",
            "סגור את כל הכרטיסיות של גלישה בסתר"
        )

        private val INCOGNITO_PAGE_MARKERS = listOf(
            "עברת לגלישה בסתר",
            "עברת למצב גלישה בסתר",
            "עברת למצב אנונימי",
            "כעת באפשרותך לגלוש באופן פרטי",
            "לא יישמרו ב-chrome",
            "הפעלת גלישה בסתר",
            "אתה גולש בסתר",
            "you've gone incognito",
            "you’ve gone incognito",
            "now you can browse privately",
            "close all incognito tabs",
            "סגור את כל הכרטיסיות של גלישה בסתר"
        )

        val SHOPPING_ALLOWED_KEYWORDS = setOf(
            "bikini",
            "lingerie",
            "lace-bra",
            "lace bra",
            "sheer bra",
            "transparent bra",
            "see-through",
            "see through bra",
            "panties",
            "cleavage",
            "thong",
            "g-string",
            "ביקיני",
            "בביקיני",
            "הלבשה תחתונה",
            "חזיה שקופה",
            "חזייה שקופה",
            "בחזיה",
            "בתחתונים",
            "חוטיני",
            "בחוטיני"
        )

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

        val MONITORED_CONTENT_PACKAGES = setOf(
            "com.google.android.youtube",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.twitter.android",
            "com.instagram.android",
            "com.facebook.katana",
            "com.facebook.lite",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.reddit.frontpage",
            "com.snapchat.android",
            "com.pinterest",
            "com.miui.gallery",
            "com.mi.android.globalFileexplorer",
            "com.sec.android.gallery3d",
            "com.google.android.apps.photos",
            "com.google.android.apps.nbu.files",
            "com.android.documentsui",
            "com.google.android.documentsui",
            "ai.x.grok"
        )

        val WHITELIST_SAFE_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.trello",
            "com.openai.chatgpt",
            "com.google.android.googlequicksearchbox",
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
