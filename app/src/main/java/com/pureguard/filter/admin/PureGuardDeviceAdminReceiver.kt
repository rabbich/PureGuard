package com.pureguard.filter.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class PureGuardDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "מגן הקודש: הגנת מניעת הסרה (מנהל מכשיר) הופעלה 🛡️", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "מגן הקודש פועל לשמירת העיניים. לשחרור מסודר של הנעילה, פתח את אפליקציית מגן הקודש ובקש שחרור נעילה."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "מגן הקודש: הרשאת מנהל מכשיר הוסרה", Toast.LENGTH_SHORT).show()
    }
}
