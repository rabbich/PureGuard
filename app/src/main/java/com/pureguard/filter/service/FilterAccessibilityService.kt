package com.pureguard.filter.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Browser
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.pureguard.filter.admin.LockdownManager
import com.pureguard.filter.data.BlockLogEntity
import com.pureguard.filter.data.BlockedAppEntity
import com.pureguard.filter.data.BlockedKeywordEntity
import com.pureguard.filter.data.PreferencesManager
import com.pureguard.filter.data.PureGuardDatabase
import com.pureguard.filter.data.TrustedSafeDomains
import com.pureguard.filter.ui.AccessibilityBlockOverlayManager
import com.pureguard.filter.util.BlockDiagnosticReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Kodeshield v5.0 Ultra-Light Zero-Battery Accessibility Service:
 * - ZERO screenshots, ZERO TFLite neural net, ZERO ML Kit OCR, ZERO background while(true) loops, ZERO local VPN busy-loop!
 * - Uses native Android Private DNS (Cloudflare Family / AdGuard Family) for network-level porn & ad blocking (0.0% battery).
 * - Locks the Android "Private DNS" settings screen so nobody can disable or bypass the DNS filter.
 * - Keeps ultra-lightweight event-driven Adult Domain & Google SafeSearch enforcement in browsers (<0.5% battery/day).
 */
class FilterAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var db: PureGuardDatabase
    private lateinit var prefs: PreferencesManager
    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private var overlayManager: AccessibilityBlockOverlayManager? = null

    @Volatile
    private var cachedBlockedApps: Set<String> = emptySet()

    @Volatile
    private var cachedKeywords: List<BlockedKeywordEntity> = emptyList()

    @Volatile
    private var lastTextScanElapsed: Long = 0L

    @Volatile
    private var lastBlockOverlayElapsed: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        db = PureGuardDatabase.getInstance(applicationContext)
        prefs = PreferencesManager(applicationContext)
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        overlayManager = AccessibilityBlockOverlayManager(this, serviceScope)

        // Ensure old battery-draining VPN service is stopped permanently
        AdGuardDnsVpnService.stop(applicationContext)

        // If Device Owner is enabled, natively hide/disable Incognito inside Chrome
        try {
            LockdownManager(applicationContext).syncChromeIncognitoPolicy(prefs.blockIncognitoMode)
        } catch (_: Throwable) {
        }

        // Observe Room DB changes into in-memory caches (zero polling, zero battery)
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

        Log.i(TAG, "FilterAccessibilityService v5.0 Ultra-Light connected (Zero-Battery mode).")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // Never inspect our own package
        if (pkg == packageName) return

        // Screen off or Lock Screen: zero CPU work
        if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) return

        // 1. ANTI-TAMPER & PRIVATE DNS LOCK GUARD (Settings / Xiaomi App Info / Package Installer)
        if (TAMPER_GUARD_PACKAGES.contains(pkg)) {
            if (!prefs.isFilterPausedForTesting && prefs.lockPrivateDnsSettings && SETTINGS_PACKAGES.contains(pkg)) {
                if (isAccessingPrivateDnsSettings(event)) {
                    triggerBlockOverlay(
                        targetAppOrUrl = "הגדרות DNS פרטי",
                        reason = "נחסם: מסך הגדרות ה-DNS הפרטי נעול לשינוי על ידי מגן הקודש 🔒",
                        pageTitle = "נעילת DNS פרטי",
                        textSnippet = "",
                        isTextBlock = false,
                        inferenceTimeMs = 0L,
                        navigateBack = true
                    )
                    return
                }
            }

            // Full Anti-Uninstall & Anti-Disable Guard (active ONLY when Permanent Lockdown is ON)
            if (prefs.isLockdownEnabled) {
                val tamperReason = detectKodeshieldTamperAttempt(event)
                if (tamperReason != null) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    triggerBlockOverlay(
                        targetAppOrUrl = "הגנת מניעת הסרה",
                        reason = tamperReason,
                        pageTitle = "נעילה קבועה פעילה",
                        textSnippet = "",
                        isTextBlock = false,
                        inferenceTimeMs = 0L,
                        navigateBack = false
                    )
                    return
                }
            }
            return
        }

        // Ignore keyboards & background system UI overlays
        if (isIgnoredSystemOrKeyboardPackage(pkg)) return

        // Quick 1-Click Sandbox Testing Pause Toggle (always false when isLockdownEnabled == true)
        if (prefs.isFilterPausedForTesting) return

        // 2. App Blocker check (user-selected blocked apps)
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

        // 3. Block alternative search engine apps (DuckDuckGo, Yandex, Bing) when SafeSearch is active
        if (prefs.enforceSafeSearchAndRestrictedMode && BLOCKED_SEARCH_ENGINE_PACKAGES.contains(pkg)) {
            triggerBlockOverlay(
                targetAppOrUrl = pkg,
                reason = "נחסם: מותר שימוש במנוע החיפוש של גוגל בלבד (עם חיפוש בטוח)",
                pageTitle = pkg,
                textSnippet = "",
                isTextBlock = false,
                inferenceTimeMs = 0L,
                navigateBack = true
            )
            return
        }

        // 4. Ultra-lightweight browser-only scan (zero scanning in WhatsApp, Telegram, Gallery, Trello, etc. -> ~0% battery!)
        if (!BROWSER_PACKAGES.contains(pkg)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                scheduleBrowserUrlGuard(pkg, forceImmediate = true)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                scheduleBrowserUrlGuard(pkg, forceImmediate = false)
            }

            else -> Unit
        }
    }

    /**
     * Detects when the user taps "DNS פרטי" / "Private DNS" OR is inside the Private DNS dialog/screen in Settings.
     * Allows all other Android Settings (Wi-Fi, Battery, Display, Accessibility) to work freely!
     */
    private fun isAccessingPrivateDnsSettings(event: AccessibilityEvent): Boolean {
        val evTextLower = buildString {
            event.text?.forEach { append(it).append(' ') }
            event.contentDescription?.let { append(it) }
        }.lowercase().trim()

        // A) User directly clicked the "DNS פרטי" / "Private DNS" row in Settings
        if ((event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) &&
            (evTextLower.contains("dns פרטי") || evTextLower.contains("private dns"))
        ) {
            return true
        }

        // B) The Private DNS dialog/screen itself is open on screen
        if (evTextLower.contains("שם מארח של ספק dns פרטי") ||
            evTextLower.contains("private dns provider hostname") ||
            evTextLower.contains("family.cloudflare-dns.com") ||
            evTextLower.contains("family.adguard-dns.com")
        ) {
            return true
        }

        // C) Quick shallow scan (max 50 nodes) of active Settings dialog to catch Xiaomi/Android Private DNS popup
        val root = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return false
        return try {
            var foundPrivateDnsTitle = false
            var foundHostnameOption = false
            var visited = 0

            fun scanSettingsNode(node: AccessibilityNodeInfo?) {
                if (node == null || visited > 50 || (foundPrivateDnsTitle && foundHostnameOption)) return
                visited++
                val t = node.text?.toString()?.lowercase()?.trim().orEmpty()
                if (t.isNotEmpty()) {
                    if (t == "dns פרטי" || t == "private dns" || t == "מצב dns פרטי" || t == "select private dns mode") {
                        foundPrivateDnsTitle = true
                    }
                    if (t.contains("שם מארח של ספק dns") ||
                        t.contains("private dns provider hostname") ||
                        t.contains("family.cloudflare-dns.com") ||
                        t.contains("family.adguard-dns.com") ||
                        t.contains("dns.adguard") ||
                        t.contains("אוטומטי") && foundPrivateDnsTitle ||
                        t.contains("automatic") && foundPrivateDnsTitle
                    ) {
                        foundHostnameOption = true
                    }
                }
                val count = node.childCount
                for (i in 0 until count) {
                    if (visited > 50) break
                    val child = node.getChild(i)
                    if (child != null) {
                        scanSettingsNode(child)
                        try { child.recycle() } catch (_: Exception) {}
                    }
                }
            }

            scanSettingsNode(root)
            foundHostnameOption
        } catch (_: Throwable) {
            false
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * 3-Layer Anti-Tamper Guard (ONLY runs when Permanent Lockdown is enabled):
     * Detects attempts inside Android Settings, Xiaomi Security Center (App Info), or Package Installer
     * to disable Device Admin, turn off Accessibility, Force Stop, Clear Data, or Uninstall Kodeshield.
     * Allows managing all OTHER apps and phone settings freely!
     */
    private fun detectKodeshieldTamperAttempt(event: AccessibilityEvent): String? {
        val collected = StringBuilder()
        event.text?.forEach { collected.append(it).append(' ') }
        event.contentDescription?.let { collected.append(it).append(' ') }

        val root = try { rootInActiveWindow } catch (_: Throwable) { null }
        if (root != null) {
            try {
                var visited = 0
                fun scanNode(node: AccessibilityNodeInfo?) {
                    if (node == null || visited > 80) return
                    visited++
                    node.text?.let { if (it.isNotBlank()) collected.append(it).append(' ') }
                    node.contentDescription?.let { if (it.isNotBlank()) collected.append(it).append(' ') }
                    val count = node.childCount
                    for (i in 0 until count) {
                        if (visited > 80) break
                        val child = node.getChild(i)
                        if (child != null) {
                            scanNode(child)
                            try { child.recycle() } catch (_: Exception) {}
                        }
                    }
                }
                scanNode(root)
            } catch (_: Throwable) {
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        }

        val screenLower = collected.toString().lowercase()
        val mentionsKodeshield = screenLower.contains("מגן הקודש") ||
            screenLower.contains("kodeshield") ||
            screenLower.contains("com.pureguard.filter")

        if (!mentionsKodeshield) return null

        // 1. Device Admin deactivation attempt
        if (screenLower.contains("מנהל מכשיר") ||
            screenLower.contains("מנהלי מכשיר") ||
            screenLower.contains("בטל הפעלה") ||
            screenLower.contains("השבת אפליקצ") ||
            screenLower.contains("deactivate") ||
            screenLower.contains("device admin")
        ) {
            return "🔒 לא ניתן לבטל את הרשאת מנהל המכשיר של מגן הקודש במצב נעילה קבועה.\nלשחרור מסודר, פתח את האפליקציה ובקש שחרור נעילה (45 דק' + תניא)."
        }

        // 2. App Info / Force Stop / Clear Data / Uninstall attempt
        if (screenLower.contains("הסר התקנה") ||
            screenLower.contains("הסרת התקנה") ||
            screenLower.contains("עצור בכוח") ||
            screenLower.contains("אלץ עצירה") ||
            screenLower.contains("נקה נתונים") ||
            screenLower.contains("מחק נתונים") ||
            screenLower.contains("נקה הכל") ||
            screenLower.contains("מידע על האפליקציה") ||
            screenLower.contains("פרטי האפליקציה") ||
            screenLower.contains("uninstall") ||
            screenLower.contains("force stop") ||
            screenLower.contains("clear data") ||
            screenLower.contains("clear storage") ||
            screenLower.contains("app info")
        ) {
            return "🔒 לא ניתן להסיר, לעצור או לאפס את מגן הקודש במצב נעילה קבועה.\nלשחרור מסודר, פתח את האפליקציה ובקש שחרור נעילה (45 דק' + תניא)."
        }

        // 3. Accessibility Service disable attempt
        if (screenLower.contains("השתמש ב-") ||
            screenLower.contains("קיצור דרך") ||
            screenLower.contains("נגישות") ||
            screenLower.contains("accessibility") ||
            screenLower.contains("use kodeshield") ||
            screenLower.contains("עצור את") ||
            screenLower.contains("stop kodeshield")
        ) {
            return "🔒 לא ניתן לכבות את שירות ההגנה של מגן הקודש במצב נעילה קבועה.\nלשחרור מסודר, פתח את האפליקציה ובקש שחרור נעילה (45 דק' + תניא)."
        }

        return null
    }

    /**
     * Ultra-lightweight Browser URL Guard (<1ms, zero DOM traversal, zero screenshots):
     * Reads ONLY the browser's URL bar node (`url_bar`) directly by View ID!
     */
    private fun scheduleBrowserUrlGuard(pkg: String, forceImmediate: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!forceImmediate && (now - lastTextScanElapsed) < URL_SCAN_THROTTLE_MS) {
            return
        }
        lastTextScanElapsed = now

        val rootNode = try { rootInActiveWindow } catch (_: Throwable) { null } ?: return
        val rootPkg = rootNode.packageName?.toString().orEmpty()
        if (rootPkg.isNotBlank() && rootPkg != pkg) {
            try { rootNode.recycle() } catch (_: Exception) {}
            return
        }

        serviceScope.launch(Dispatchers.Default) {
            val scanStart = SystemClock.elapsedRealtime()
            val detectedUrl = try {
                extractBrowserUrlFast(rootNode, pkg)
            } catch (_: Throwable) {
                ""
            } finally {
                try { rootNode.recycle() } catch (_: Exception) {}
            }

            if (detectedUrl.isBlank()) return@launch

            val urlLower = detectedUrl.lowercase().trim()

            // Safe whitelist domains -> 0 work
            if (TrustedSafeDomains.isTrustedSafeUrl(urlLower)) return@launch

            if (db.temporaryWhitelistDao().isWhitelisted(pkg) ||
                db.temporaryWhitelistDao().isWhitelisted(detectedUrl)
            ) {
                return@launch
            }

            // 1. Google-Only SafeSearch Enforcement & Block Alternative Unfiltered Search Engines
            if (prefs.enforceSafeSearchAndRestrictedMode) {
                val blockedSearch = findBlockedAlternativeSearchEngine(urlLower)
                if (blockedSearch != null) {
                    triggerBlockOverlay(
                        targetAppOrUrl = detectedUrl,
                        reason = "נחסם ($blockedSearch): מותר שימוש במנוע החיפוש של גוגל בלבד (עם חיפוש בטוח)",
                        pageTitle = blockedSearch,
                        textSnippet = "",
                        isTextBlock = false,
                        inferenceTimeMs = SystemClock.elapsedRealtime() - scanStart,
                        navigateBack = true
                    )
                    return@launch
                }

                if (isUnfilteredGoogleSearchUrl(urlLower)) {
                    enforceSafeSearchRedirectInBrowser(pkg, detectedUrl)
                    return@launch
                }
            }

            // 2. Explicit Adult / Porn Domain & Explicit URL Blocker (strictly porn sites, NO clothing/lingerie/news false positives!)
            val matchedDomainOrExplicit = findExplicitPornUrlMatch(urlLower)
            if (matchedDomainOrExplicit != null) {
                triggerBlockOverlay(
                    targetAppOrUrl = detectedUrl,
                    reason = "הגישה לאתר זה נחסמה לשמירת העיניים 🛡️",
                    pageTitle = detectedUrl,
                    textSnippet = "",
                    isTextBlock = false,
                    inferenceTimeMs = SystemClock.elapsedRealtime() - scanStart,
                    navigateBack = true
                )
            }
        }
    }

    /**
     * Direct View-ID URL lookup (O(1) — does NOT walk 650 DOM nodes, saves 99% CPU/battery!).
     */
    private fun extractBrowserUrlFast(root: AccessibilityNodeInfo, pkg: String): String {
        val urlViewIds = URL_BAR_VIEW_IDS[pkg] ?: DEFAULT_URL_BAR_IDS
        for (viewId in urlViewIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if (!nodes.isNullOrEmpty()) {
                var found = ""
                for (node in nodes) {
                    val text = node.text?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty() && !text.contains(" ") && !text.contains("@")) {
                        found = text
                        break
                    }
                }
                nodes.forEach {
                    try { it.recycle() } catch (_: Exception) {}
                }
                if (found.isNotEmpty()) return found
            }
        }
        return ""
    }

    private fun findExplicitPornUrlMatch(urlLower: String): String? {
        // Check user-added / seeded explicit domains first
        for (item in cachedKeywords) {
            val kw = item.keyword.lowercase().trim()
            if (kw.isEmpty()) continue
            if (item.isDomain && urlLower.contains(kw)) {
                return kw
            }
        }
        // Check explicit porn URL tokens (only unmistakable adult site patterns)
        for (token in EXPLICIT_PORN_URL_TOKENS) {
            if (urlLower.contains(token)) {
                return token
            }
        }
        return null
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

        BlockDiagnosticReporter.saveBlockSnapshot(
            context = applicationContext,
            bitmap = null,
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

        overlayManager?.showBlockOverlay(
            targetUrlOrApp = targetAppOrUrl,
            blockReason = reason,
            pageTitle = pageTitle,
            textSnippet = textSnippet,
            isTextBlock = isTextBlock
        )
    }

    private fun isIgnoredSystemOrKeyboardPackage(pkg: String): Boolean {
        return pkg == "com.android.systemui" ||
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
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "FilterAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayManager?.dismissOverlayImmediate()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "FilterAccessService"
        private const val URL_SCAN_THROTTLE_MS = 700L

        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.miui.settings"
        )

        private val TAMPER_GUARD_PACKAGES = setOf(
            "com.android.settings",
            "com.miui.settings",
            "com.miui.securitycenter",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller"
        )

        private val EXPLICIT_PORN_URL_TOKENS = listOf(
            "pornhub",
            "xvideos",
            "xnxx",
            "xhamster",
            "redtube",
            "youporn",
            "onlyfans",
            "chaturbate",
            "stripchat",
            "brazzers",
            "spankbang",
            "eporner",
            "tube8",
            "xtube",
            "beeg.com",
            "tnaflix",
            "porn.com",
            "sex.com",
            "livejasmin",
            "bongacams",
            "cam4.com",
            "myfreecams"
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
            "baidu.com"
        )

        val BLOCKED_SEARCH_ENGINE_PACKAGES = setOf(
            "com.duckduckgo.mobile.android",
            "com.yandex.browser",
            "ru.yandex.searchplugin",
            "com.microsoft.bing",
            "com.yahoo.mobile.client.android.search",
            "com.ecosia.android"
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
