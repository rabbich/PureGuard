package com.pureguard.filter.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * Covers Chrome's "גלישה פרטית" homepage pill button and "כרטיסיית גלישה בסתר חדשה" menu row
 * with an exact-sized grayed-out unclickable Accessibility Overlay shield — NO USB / Device Owner needed!
 * Swallows clicks directly on the Incognito button while leaving the rest of Chrome 100% touchable.
 */
class IncognitoButtonShieldManager(private val service: AccessibilityService) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var shieldView: TextView? = null
    private var lastBounds: Rect? = null

    fun showOrUpdateShield(bounds: Rect, labelText: String = "🔒 גלישה פרטית (חסום)") {
        if (bounds.width() < 40 || bounds.height() < 30) {
            hideShield()
            return
        }
        val copyRect = Rect(bounds)
        mainHandler.post {
            try {
                if (shieldView != null && lastBounds == copyRect) {
                    return@post
                }
                val params = WindowManager.LayoutParams(
                    copyRect.width(),
                    copyRect.height(),
                    copyRect.left,
                    copyRect.top,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }

                val existing = shieldView
                if (existing != null) {
                    existing.text = labelText
                    windowManager.updateViewLayout(existing, params)
                    lastBounds = copyRect
                    return@post
                }

                val density = service.resources.displayMetrics.density
                val bg = GradientDrawable().apply {
                    setColor(Color.parseColor("#E0E0E0"))
                    cornerRadius = (copyRect.height() / 2f).coerceAtMost(24f * density)
                    setStroke((1.5f * density).toInt(), Color.parseColor("#BDBDBD"))
                }

                val tv = TextView(service).apply {
                    text = labelText
                    setTextColor(Color.parseColor("#757575"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    gravity = Gravity.CENTER
                    background = bg
                    setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                    isClickable = true
                    setOnClickListener {
                        Toast.makeText(
                            service,
                            "גלישה בסתר נעולה במגן הקודש 🔒",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                windowManager.addView(tv, params)
                shieldView = tv
                lastBounds = copyRect
            } catch (_: Throwable) {
            }
        }
    }

    fun hideShield() {
        mainHandler.post {
            val view = shieldView ?: return@post
            try {
                windowManager.removeViewImmediate(view)
            } catch (_: Throwable) {
            } finally {
                shieldView = null
                lastBounds = null
            }
        }
    }
}
