package com.pureguard.filter.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.pureguard.filter.data.PreferencesManager

/**
 * Disabled in v5.0 Ultra-Light Architecture in favor of native Android OS Private DNS
 * (Cloudflare Family / AdGuard Family) which uses 0.0% battery with zero local VPN overhead.
 */
class AdGuardDnsVpnService : VpnService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = false
        PreferencesManager(applicationContext).isAutoDnsVpnEnabled = false
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_STOP = "com.pureguard.filter.STOP_DNS_VPN"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun stop(context: Context) {
            isRunning = false
            PreferencesManager(context).isAutoDnsVpnEnabled = false
        }
    }
}
