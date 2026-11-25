package com.pureguard.filter.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.pureguard.filter.data.PreferencesManager

/**
 * Dedicated LockdownManager for Phase 2 (Production Mode).
 *
 * CRITICAL PHASE 1 RULE:
 * When `prefs.isLockdownEnabled == false` (the default Sandbox Testing mode),
 * this manager NEVER applies UserManager restrictions or blocks uninstallation.
 */
class LockdownManager(private val context: Context) {

    private val prefs = PreferencesManager(context)
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, PureGuardDeviceAdminReceiver::class.java)

    fun isDeviceOwner(): Boolean {
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun isAdminActive(): Boolean {
        return dpm.isAdminActive(adminComponent)
    }

    fun getAdminComponent(): ComponentName = adminComponent

    /**
     * Activates Production Lockdown ONLY when explicitly requested by user after confirmation.
     */
    fun enableProductionLockdown(): Result<Unit> {
        return try {
            prefs.isLockdownEnabled = true
            prefs.lockPrivateDnsSettings = true
            prefs.isFilterPausedForTesting = false
            prefs.unlockRequestElapsedRealtime = 0L

            if (isDeviceOwner()) {
                dpm.setUninstallBlocked(adminComponent, context.packageName, true)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_VPN)
                applyPrivateDnsLock("family.adguard-dns.com")
                applyChromeManagedRestrictions(enable = true)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply production lockdown restrictions", e)
            Result.failure(e)
        }
    }

    /**
     * Starts the 45-minute monotonic cooldown timer using SystemClock.elapsedRealtime()
     * so changing the system clock cannot bypass the wait.
     */
    fun startUnlockCooldown() {
        prefs.unlockRequestElapsedRealtime = SystemClock.elapsedRealtime()
    }

    sealed class UnlockWindowState {
        object NotStartedOrExpired : UnlockWindowState()
        data class Waiting(val remainingWaitMs: Long) : UnlockWindowState()
        data class WindowOpen(val remainingWindowMs: Long) : UnlockWindowState()
    }

    /**
     * Evaluates the 45-minute wait + 15-minute open unlock window.
     * If more than 60 minutes (45m wait + 15m window) have elapsed without completing the challenge,
     * the request automatically expires and resets to NotStartedOrExpired!
     */
    fun getUnlockWindowState(): UnlockWindowState {
        val startedAt = prefs.unlockRequestElapsedRealtime
        val now = SystemClock.elapsedRealtime()
        if (startedAt <= 0L || now < startedAt) {
            return UnlockWindowState.NotStartedOrExpired
        }
        val elapsed = now - startedAt
        if (elapsed < COOLDOWN_DURATION_MS) {
            return UnlockWindowState.Waiting(COOLDOWN_DURATION_MS - elapsed)
        }
        val totalAllowedMs = COOLDOWN_DURATION_MS + UNLOCK_WINDOW_DURATION_MS
        if (elapsed <= totalAllowedMs) {
            return UnlockWindowState.WindowOpen(totalAllowedMs - elapsed)
        }
        // 15-minute window expired -> reset automatically so user must wait 45 minutes again
        prefs.unlockRequestElapsedRealtime = 0L
        return UnlockWindowState.NotStartedOrExpired
    }

    /**
     * Verifies if the user typed the exact Tanya Chapter 41 challenge passage.
     * Also returns false if the 15-minute unlock window has expired while typing.
     */
    fun verifyTanyaChallenge(userInput: String): Boolean {
        if (getUnlockWindowState() !is UnlockWindowState.WindowOpen) {
            return false
        }
        val normalizedUser = normalizeHebrewText(userInput)
        val normalizedTarget = normalizeHebrewText(TANYA_CHAPTER_41_CHALLENGE)
        return normalizedUser == normalizedTarget
    }

    /**
     * Disables Production Lockdown and removes all Device Owner restrictions.
     */
    fun disableProductionLockdown(): Result<Unit> {
        return try {
            prefs.isLockdownEnabled = false
            prefs.lockPrivateDnsSettings = false
            prefs.unlockRequestElapsedRealtime = 0L

            if (isDeviceOwner()) {
                dpm.setUninstallBlocked(adminComponent, context.packageName, false)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_VPN)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                applyChromeManagedRestrictions(enable = false)
            } else if (isAdminActive()) {
                try {
                    dpm.removeActiveAdmin(adminComponent)
                } catch (_: Throwable) {
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear production lockdown", e)
            Result.failure(e)
        }
    }

    fun syncChromeIncognitoPolicy(enable: Boolean) {
        applyChromeManagedRestrictions(enable)
    }

    private fun applyChromeManagedRestrictions(enable: Boolean) {
        if (!isDeviceOwner()) return
        try {
            val restrictions = if (enable) {
                Bundle().apply {
                    // 1 = Incognito mode completely disabled inside Chrome itself
                    putInt("IncognitoModeAvailability", 1)
                    // Force Google SafeSearch natively inside Chrome
                    putBoolean("ForceGoogleSafeSearch", true)
                    // 2 = Strict YouTube Restricted Mode enforced natively inside Chrome
                    putInt("ForceYouTubeRestrict", 2)
                }
            } else {
                Bundle()
            }
            dpm.setApplicationRestrictions(adminComponent, "com.android.chrome", restrictions)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply Chrome managed restrictions: ${e.message}")
        }
    }

    private fun applyPrivateDnsLock(dnsHostname: String) {
        if (!isDeviceOwner()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dpm.setGlobalSetting(adminComponent, "private_dns_mode", "hostname")
                dpm.setGlobalSetting(adminComponent, "private_dns_specifier", dnsHostname)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Private DNS setting lock not supported via GlobalSetting on this OS version: ${e.message}")
        }
    }

    private fun normalizeHebrewText(input: String): String {
        // 1. Normalize Hebrew gershayim/geresh & curly quotes to standard ASCII " and '
        //    so abbreviations (הקב"ה, כנ"ל, וכמ"ש, ה') match regardless of keyboard quote key!
        // 2. Remove nikud/cantillation, periods, commas, colons, semicolons, parentheses, and verse-surrounding quotes.
        val unifiedQuotes = input
            .replace('״', '"')
            .replace('”', '"')
            .replace('“', '"')
            .replace('׳', '\'')
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace(Regex("[\\u0591-\\u05C7]"), "")
            .replace(Regex("[.,:;!?()\\-–—]"), " ")
            // Strip surrounding quotation marks around words (like "הלא ... מלא") while keeping inside abbreviations (הקב"ה)
            .replace(Regex("(^|\\s)\""), "$1")
            .replace(Regex("\"(\\s|$)"), "$1")

        return unifiedQuotes
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private const val TAG = "LockdownManager"

        // 45 minutes wait + 15 minutes open unlock window
        const val COOLDOWN_DURATION_MS = 45L * 60L * 1000L
        const val UNLOCK_WINDOW_DURATION_MS = 15L * 60L * 1000L

        // Exact Tanya Chapter 41 text from "ברם" to "המלך" (exact printed abbreviations, normalized without periods/commas)
        const val TANYA_CHAPTER_41_CHALLENGE =
            "ברם צריך להיות לזכרון תמיד ראשית העבודה ועיקרה ושרשה. והוא כי אף שהיראה היא שרש לסור מרע והאהבה לועשה טוב. אף על פי כן לא די לעורר האהבה לבדה לועשה טוב ולפחות צריך לעורר תחלה היראה הטבעית המסותרת בלב כל ישראל שלא למרוד במלך מלכי המלכים הקב\"ה כנ\"ל שתהא בהתגלות לבו או מוחו על כל פנים דהיינו להתבונן במחשבתו על כל פנים גדולת אין סוף ברוך הוא ומלכותו אשר היא מלכות כל עולמים עליונים ותחתונים ואיהו ממלא כל עלמין וסובב כל עלמין וכמ\"ש \"הלא את השמים ואת הארץ אני מלא\" ומניח העליונים ותחתונים ומייחד מלכותו על עמו ישראל בכלל ועליו בפרט כי חייב אדם לומר בשבילי נברא העולם והוא גם הוא מקבל עליו מלכותו להיות מלך עליו ולעבדו ולעשות רצונו בכל מיני עבודת עבד. והנה ה' נצב עליו ומלא כל הארץ כבודו ומביט עליו ובוחן כליות ולב אם עובדו כראוי. ועל כן צריך לעבוד לפניו באימה וביראה כעומד לפני המלך"
    }
}
