package com.pureguard.filter.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * CRITICAL PHASE 1 REQUIREMENT:
     * Default MUST be false (Testing / Sandbox mode).
     * Never applies UserManager restrictions or blocks uninstallation while false.
     */
    var isLockdownEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCKDOWN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCKDOWN_ENABLED, value).apply()

    var blockYoutubeShortsAndReels: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_SHORTS_REELS, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_SHORTS_REELS, value).apply()

    var enforceSafeSearchAndRestrictedMode: Boolean
        get() = prefs.getBoolean(KEY_ENFORCE_SAFESEARCH, true)
        set(value) = prefs.edit().putBoolean(KEY_ENFORCE_SAFESEARCH, value).apply()

    var showDebugToast: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DEBUG_TOAST, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_DEBUG_TOAST, value).apply()

    var pornThreshold: Float
        get() = prefs.getFloat(KEY_PORN_THRESHOLD, 0.55f)
        set(value) = prefs.edit().putFloat(KEY_PORN_THRESHOLD, value.coerceIn(0.30f, 0.90f)).apply()

    var sexyThreshold: Float
        get() = prefs.getFloat(KEY_SEXY_THRESHOLD, 0.65f)
        set(value) = prefs.edit().putFloat(KEY_SEXY_THRESHOLD, value.coerceIn(0.30f, 0.90f)).apply()

    var hentaiThreshold: Float
        get() = prefs.getFloat(KEY_HENTAI_THRESHOLD, 0.60f)
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
        private const val KEY_BLOCK_SHORTS_REELS = "block_youtube_shorts_and_reels"
        private const val KEY_ENFORCE_SAFESEARCH = "enforce_safesearch_restricted"
        private const val KEY_SHOW_DEBUG_TOAST = "show_debug_toast"
        private const val KEY_PORN_THRESHOLD = "porn_threshold"
        private const val KEY_SEXY_THRESHOLD = "sexy_threshold"
        private const val KEY_HENTAI_THRESHOLD = "hentai_threshold"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_UNLOCK_REQUEST_ELAPSED = "unlock_request_elapsed_realtime"
    }
}
