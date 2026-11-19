package com.pureguard.filter.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
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
            prefs.unlockRequestElapsedRealtime = 0L

            if (isDeviceOwner()) {
                dpm.setUninstallBlocked(adminComponent, context.packageName, true)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
                applyPrivateDnsLock("family.adguard-dns.com")
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

    /**
     * Returns remaining cooldown time in milliseconds, or 0L if cooldown has finished.
     * Returns -1L if cooldown has not been started or device was rebooted.
     */
    fun getRemainingCooldownMs(): Long {
        val startedAt = prefs.unlockRequestElapsedRealtime
        val now = SystemClock.elapsedRealtime()
        if (startedAt <= 0L || now < startedAt) {
            return -1L
        }
        val elapsed = now - startedAt
        val remaining = COOLDOWN_DURATION_MS - elapsed
        return if (remaining > 0L) remaining else 0L
    }

    /**
     * Verifies if the user typed the exact Tanya Chapter 41 challenge passage.
     */
    fun verifyTanyaChallenge(userInput: String): Boolean {
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
            prefs.unlockRequestElapsedRealtime = 0L

            if (isDeviceOwner()) {
                dpm.setUninstallBlocked(adminComponent, context.packageName, false)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear production lockdown", e)
            Result.failure(e)
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
        // Remove Hebrew nikud/cantillation marks if any and collapse whitespace
        return input
            .replace(Regex("[\\u0591-\\u05C7]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    companion object {
        private const val TAG = "LockdownManager"

        // 45 minutes in milliseconds
        const val COOLDOWN_DURATION_MS = 45L * 60L * 1000L

        // Tanya Chapter 41 manual typing challenge (no copy/paste allowed)
        const val TANYA_CHAPTER_41_CHALLENGE =
            "ברם צריך להיות לזכרון תמיד ראשית כל העבודה ועיקרה ושרשה לקבוע בלבו ומוחו תחלה אמיתת ייחודו יתברך הנה ה' נצב עליו ומלא כל הארץ כבודו ומשגיח עליו ובוחן כליות ולב אם עובדו כראוי"
    }
}
