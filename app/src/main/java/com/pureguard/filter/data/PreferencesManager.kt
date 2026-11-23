package com.pureguard.filter.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Auto-migrate overly sensitive v4.0-v4.6 strict thresholds (30%/35%) to balanced v4.7 values (65%/55%)
        val calVer = prefs.getInt(KEY_CALIBRATION_VERSION, 0)
        if (calVer < 2) {
            val editor = prefs.edit().putInt(KEY_CALIBRATION_VERSION, 2)
            if (prefs.getInt(KEY_FILTER_PROFILE_LEVEL, PROFILE_STRICT) == PROFILE_STRICT) {
                editor.putFloat(KEY_PORN_THRESHOLD, 0.65f)
                editor.putFloat(KEY_SEXY_THRESHOLD, 0.55f)
            }
            editor.apply()
        }
    }

    /**
     * CRITICAL PHASE 1 REQUIREMENT:
     * Default MUST be false (Testing / Sandbox mode).
     * Never applies UserManager restrictions or blocks uninstallation while false.
     */
    var isLockdownEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCKDOWN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCKDOWN_ENABLED, value).apply()

    /**
     * Quick 1-click pause toggle for Sandbox testing mode.
     * Can NEVER be active when isLockdownEnabled == true.
     */
    var isFilterPausedForTesting: Boolean
        get() = !isLockdownEnabled && prefs.getBoolean(KEY_FILTER_PAUSED_TESTING, false)
        set(value) = prefs.edit().putBoolean(KEY_FILTER_PAUSED_TESTING, value).apply()

    var isSetupWizardCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_WIZARD_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_WIZARD_COMPLETED, value).apply()

    /**
     * Filtration Profile Level:
     * 0 = STRICT ("שמירת עיניים מלאה - מחמיר")
     * 1 = MODERATE_SHOPPING ("מותאם לנשים - קניות ואופנה פתוחים")
     * 2 = CUSTOM ("מותאם אישית")
     */
    var filterProfileLevel: Int
        get() = prefs.getInt(KEY_FILTER_PROFILE_LEVEL, PROFILE_STRICT)
        set(value) = prefs.edit().putInt(KEY_FILTER_PROFILE_LEVEL, value).apply()

    var blockYoutubeShortsAndReels: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_SHORTS_REELS, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_SHORTS_REELS, value).apply()

    var blockIncognitoMode: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_INCOGNITO_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_INCOGNITO_MODE, value).apply()

    var isAutoDnsVpnEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DNS_VPN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DNS_VPN_ENABLED, value).apply()

    /**
     * v5.0 Ultra-Light Architecture:
     * Visual AI / OCR / Screenshot scanning is OFF by default for <1% battery usage.
     */
    var isVisualAiEnabled: Boolean
        get() = prefs.getBoolean(KEY_VISUAL_AI_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_VISUAL_AI_ENABLED, value).apply()

    /**
     * Locks Android's Private DNS settings screen so the user cannot turn off Cloudflare/AdGuard DNS.
     */
    var lockPrivateDnsSettings: Boolean
        get() = isLockdownEnabled || prefs.getBoolean(KEY_LOCK_PRIVATE_DNS_SETTINGS, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_PRIVATE_DNS_SETTINGS, value).apply()

    var enforceSafeSearchAndRestrictedMode: Boolean
        get() = prefs.getBoolean(KEY_ENFORCE_SAFESEARCH, true)
        set(value) = prefs.edit().putBoolean(KEY_ENFORCE_SAFESEARCH, value).apply()

    var blockUncensoredAiAndGrok: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_GROK_AI, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_GROK_AI, value).apply()

    var showDebugToast: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DEBUG_TOAST, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_DEBUG_TOAST, value).apply()

    var pornThreshold: Float
        get() = prefs.getFloat(KEY_PORN_THRESHOLD, 0.65f)
        set(value) = prefs.edit().putFloat(KEY_PORN_THRESHOLD, value.coerceIn(0.30f, 0.90f)).apply()

    var sexyThreshold: Float
        get() = prefs.getFloat(KEY_SEXY_THRESHOLD, 0.55f)
        set(value) = prefs.edit().putFloat(KEY_SEXY_THRESHOLD, value.coerceIn(0.30f, 0.90f)).apply()

    var hentaiThreshold: Float
        get() = prefs.getFloat(KEY_HENTAI_THRESHOLD, 0.70f)
        set(value) = prefs.edit().putFloat(KEY_HENTAI_THRESHOLD, value.coerceIn(0.30f, 0.90f)).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value.trim()).apply()

    var unlockRequestElapsedRealtime: Long
        get() = prefs.getLong(KEY_UNLOCK_REQUEST_ELAPSED, 0L)
        set(value) = prefs.edit().putLong(KEY_UNLOCK_REQUEST_ELAPSED, value).apply()

    companion object {
        private const val PREFS_NAME = "pureguard_prefs"
        private const val KEY_LOCKDOWN_ENABLED = "is_lockdown_enabled"
        private const val KEY_FILTER_PAUSED_TESTING = "is_filter_paused_for_testing"
        private const val KEY_SETUP_WIZARD_COMPLETED = "is_setup_wizard_completed"
        private const val KEY_FILTER_PROFILE_LEVEL = "filter_profile_level"
        private const val KEY_BLOCK_SHORTS_REELS = "block_youtube_shorts_and_reels"
        private const val KEY_BLOCK_INCOGNITO_MODE = "block_incognito_mode"
        private const val KEY_AUTO_DNS_VPN_ENABLED = "auto_dns_vpn_enabled"
        private const val KEY_VISUAL_AI_ENABLED = "visual_ai_enabled_v5"
        private const val KEY_LOCK_PRIVATE_DNS_SETTINGS = "lock_private_dns_settings"

        const val PROFILE_STRICT = 0
        const val PROFILE_MODERATE_SHOPPING = 1
        const val PROFILE_CUSTOM = 2
        private const val KEY_ENFORCE_SAFESEARCH = "enforce_safesearch_restricted"
        private const val KEY_BLOCK_GROK_AI = "block_uncensored_ai_and_grok"
        private const val KEY_SHOW_DEBUG_TOAST = "show_debug_toast"
        private const val KEY_PORN_THRESHOLD = "porn_threshold"
        private const val KEY_SEXY_THRESHOLD = "sexy_threshold"
        private const val KEY_HENTAI_THRESHOLD = "hentai_threshold"
        private const val KEY_CALIBRATION_VERSION = "ai_calibration_version"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_UNLOCK_REQUEST_ELAPSED = "unlock_request_elapsed_realtime"
    }
}
