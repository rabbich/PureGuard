package com.pureguard.filter.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.pureguard.filter.R
import com.pureguard.filter.ai.GeminiReCheckClient
import com.pureguard.filter.data.PreferencesManager
import com.pureguard.filter.data.PureGuardDatabase
import com.pureguard.filter.data.TemporaryWhitelistEntity
import com.pureguard.filter.databinding.ActivityBlockOverlayBinding
import com.pureguard.filter.util.BlockDiagnosticReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Universal Direct Accessibility Overlay (`TYPE_ACCESSIBILITY_OVERLAY`).
 * Works instantaneously (0ms) on ALL Android devices (Samsung, Xiaomi, Pixel, OnePlus, Motorola, etc.)
 * without requiring `SYSTEM_ALERT_WINDOW` permission and without ever being blocked by OEM background-activity restrictions!
 */
class AccessibilityBlockOverlayManager(
    private val service: AccessibilityService,
    private val serviceScope: CoroutineScope
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = PreferencesManager(service)
    private val db = PureGuardDatabase.getInstance(service)
    private val geminiClient = GeminiReCheckClient()

    private var activeOverlayView: View? = null

    fun showBlockOverlay(
        targetUrlOrApp: String,
        blockReason: String,
        pageTitle: String,
        textSnippet: String,
        isTextBlock: Boolean
    ) {
        mainHandler.post {
            try {
                dismissOverlayImmediate()

                val themedContext = ContextThemeWrapper(service, R.style.Theme_PureGuard)
                val inflater = LayoutInflater.from(themedContext)
                val binding = ActivityBlockOverlayBinding.inflate(inflater)

                binding.tvBlockReason.text = blockReason
                binding.tvTargetInfo.text = "מקור: $targetUrlOrApp"

                // CRITICAL FIX: Only allow text-based AI Re-Check when the block was triggered by text/keyword!
                // Never allow a text-only AI model to override a visual nudity/lingerie screenshot block!
                binding.btnAiReCheck.visibility = if (isTextBlock) View.VISIBLE else View.GONE

                // Show 1-time Sandbox testing bypass ONLY when Lockdown Mode is OFF
                binding.btnSandboxBypass.visibility = if (!prefs.isLockdownEnabled) View.VISIBLE else View.GONE

                binding.btnAiReCheck.setOnClickListener {
                    binding.btnAiReCheck.isEnabled = false
                    binding.progressAiRecheck.visibility = View.VISIBLE
                    binding.tvAiRecheckStatus.visibility = View.VISIBLE
                    binding.tvAiRecheckStatus.text = "מבצע בדיקת הקשר חכמה (AI)..."

                    val isShoppingAllowed = (prefs.filterProfileLevel == PreferencesManager.PROFILE_MODERATE_SHOPPING)
                    val extraProfileNote = if (isShoppingAllowed) {
                        "\n[פרופיל קניות פתוח: אם מדובר באתר קניות אופנה/ביגוד לגיטימי ללא פורנוגרפיה, אשר כבטוח]"
                    } else {
                        "\n[פרופיל שמירת עיניים מחמיר: אל תאשר דפי הלבשה תחתונה או דוגמנות חושפנית]"
                    }

                    serviceScope.launch {
                        val eval = geminiClient.evaluateContext(
                            apiKey = prefs.geminiApiKey,
                            pageTitle = pageTitle,
                            url = targetUrlOrApp,
                            textSnippet = textSnippet + extraProfileNote,
                            triggerReason = blockReason
                        )

                        withContext(Dispatchers.Main) {
                            binding.progressAiRecheck.visibility = View.GONE
                            binding.btnAiReCheck.isEnabled = true
                            binding.tvAiRecheckStatus.text = eval.explanationHe

                            if (eval.isSafe) {
                                val valid24Hours = System.currentTimeMillis() + (24L * 60L * 60L * 1000L)
                                serviceScope.launch(Dispatchers.IO) {
                                    // Whitelist only the specific page/identifier, not an entire file manager or app forever
                                    if (targetUrlOrApp.isNotBlank()) {
                                        db.temporaryWhitelistDao().insertWhitelist(
                                            TemporaryWhitelistEntity(
                                                urlOrKey = targetUrlOrApp,
                                                expiresAt = valid24Hours
                                            )
                                        )
                                    }
                                }
                                Toast.makeText(
                                    service,
                                    "אושר על ידי AI ל-24 שעות: ${eval.explanationHe}",
                                    Toast.LENGTH_LONG
                                ).show()
                                dismissOverlayImmediate()
                            }
                        }
                    }
                }

                binding.btnSandboxBypass.setOnClickListener {
                    val expiresIn15Min = System.currentTimeMillis() + (15L * 60L * 1000L)
                    serviceScope.launch(Dispatchers.IO) {
                        if (targetUrlOrApp.isNotBlank()) {
                            db.temporaryWhitelistDao().insertWhitelist(
                                TemporaryWhitelistEntity(
                                    urlOrKey = targetUrlOrApp,
                                    expiresAt = expiresIn15Min
                                )
                            )
                            val pkgPrefix = targetUrlOrApp.substringBefore(" ").trim()
                            if (pkgPrefix.isNotBlank() && pkgPrefix != targetUrlOrApp) {
                                db.temporaryWhitelistDao().insertWhitelist(
                                    TemporaryWhitelistEntity(
                                        urlOrKey = pkgPrefix,
                                        expiresAt = expiresIn15Min
                                    )
                                )
                            }
                        }
                    }
                    Toast.makeText(
                        service,
                        "אושר חד-פעמית ל-15 דקות (מצב בדיקות)",
                        Toast.LENGTH_SHORT
                    ).show()
                    dismissOverlayImmediate()
                }

                binding.btnGoHome.setOnClickListener {
                    dismissOverlayImmediate()
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                }

                binding.btnShareBlockReport.setOnClickListener {
                    binding.btnShareBlockReport.isEnabled = false
                    binding.btnShareBlockReport.text = "✅ נשלח אוטומטית לבדיקה!"
                    BlockDiagnosticReporter.shareLastBlockReport(
                        context = service,
                        targetUrlOrApp = targetUrlOrApp,
                        blockReason = blockReason,
                        pageTitle = pageTitle,
                        textSnippet = textSnippet,
                        openShareSheetToo = false
                    )
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                windowManager.addView(binding.root, params)
                activeOverlayView = binding.root
            } catch (e: Throwable) {
                // Fallback to Activity if WindowManager fails for any reason
                val intent = Intent(service, BlockOverlayActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(BlockOverlayActivity.EXTRA_TARGET_URL_OR_APP, targetUrlOrApp)
                    putExtra(BlockOverlayActivity.EXTRA_REASON, blockReason)
                    putExtra(BlockOverlayActivity.EXTRA_PAGE_TITLE, pageTitle)
                    putExtra(BlockOverlayActivity.EXTRA_TEXT_SNIPPET, textSnippet)
                    putExtra(BlockOverlayActivity.EXTRA_IS_TEXT_BLOCK, isTextBlock)
                }
                service.startActivity(intent)
            }
        }
    }

    fun dismissOverlayImmediate() {
        val view = activeOverlayView ?: return
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: Throwable) {
        } finally {
            activeOverlayView = null
        }
    }
}
