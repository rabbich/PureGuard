package com.pureguard.filter.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pureguard.filter.ai.GeminiReCheckClient
import com.pureguard.filter.data.PreferencesManager
import com.pureguard.filter.data.PureGuardDatabase
import com.pureguard.filter.data.TemporaryWhitelistEntity
import com.pureguard.filter.databinding.ActivityBlockOverlayBinding
import com.pureguard.filter.util.BlockDiagnosticReporter
import kotlinx.coroutines.launch

class BlockOverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockOverlayBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var db: PureGuardDatabase
    private val geminiClient = GeminiReCheckClient()

    private var targetUrlOrApp: String = ""
    private var blockReason: String = ""
    private var pageTitle: String = ""
    private var textSnippet: String = ""
    private var isTextBlock: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)
        db = PureGuardDatabase.getInstance(this)

        readIntentExtras(intent)
        setupUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateToHomeScreen()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntentExtras(intent)
        setupUi()
    }

    private fun readIntentExtras(intent: Intent?) {
        targetUrlOrApp = intent?.getStringExtra(EXTRA_TARGET_URL_OR_APP).orEmpty()
        blockReason = intent?.getStringExtra(EXTRA_REASON) ?: "נחסם עקב מדיניות סינון תוכן"
        pageTitle = intent?.getStringExtra(EXTRA_PAGE_TITLE).orEmpty()
        textSnippet = intent?.getStringExtra(EXTRA_TEXT_SNIPPET).orEmpty()
        isTextBlock = intent?.getBooleanExtra(EXTRA_IS_TEXT_BLOCK, true) ?: true
    }

    private fun setupUi() {
        binding.tvBlockReason.text = blockReason
        binding.tvTargetInfo.text = "מקור: $targetUrlOrApp"

        // CRITICAL FIX: Only show text-based AI Re-Check button when blocked by text/keyword!
        binding.btnAiReCheck.visibility = if (isTextBlock) View.VISIBLE else View.GONE

        // CRITICAL REQUIREMENT: "אשר חד-פעמית (מצב בדיקות)" visible ONLY when isLockdownEnabled == false
        binding.btnSandboxBypass.visibility = if (!prefs.isLockdownEnabled) View.VISIBLE else View.GONE

        binding.btnAiReCheck.setOnClickListener {
            performFreeAiReCheck()
        }

        binding.btnSandboxBypass.setOnClickListener {
            lifecycleScope.launch {
                val expiresIn15Min = System.currentTimeMillis() + (15L * 60L * 1000L)
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
                Toast.makeText(
                    this@BlockOverlayActivity,
                    "אושר חד-פעמית ל-15 דקות (מצב בדיקות)",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }

        binding.btnGoHome.setOnClickListener {
            navigateToHomeScreen()
        }

        binding.btnShareBlockReport.setOnClickListener {
            BlockDiagnosticReporter.shareLastBlockReport(
                context = this,
                targetUrlOrApp = targetUrlOrApp,
                blockReason = blockReason,
                pageTitle = pageTitle,
                textSnippet = textSnippet
            )
            finish()
        }
    }

    private fun performFreeAiReCheck() {
        val apiKey = prefs.geminiApiKey

        binding.btnAiReCheck.isEnabled = false
        binding.progressAiRecheck.visibility = View.VISIBLE
        binding.tvAiRecheckStatus.visibility = View.VISIBLE
        binding.tvAiRecheckStatus.text = "מבצע בדיקת הקשר חכמה וחינמית (AI)..."

        lifecycleScope.launch {
            val eval = geminiClient.evaluateContext(
                apiKey = apiKey,
                pageTitle = pageTitle,
                url = targetUrlOrApp,
                textSnippet = textSnippet,
                triggerReason = blockReason
            )

            binding.progressAiRecheck.visibility = View.GONE
            binding.btnAiReCheck.isEnabled = true
            binding.tvAiRecheckStatus.text = eval.explanationHe

            if (eval.isSafe) {
                val valid24Hours = System.currentTimeMillis() + (24L * 60L * 60L * 1000L)
                if (targetUrlOrApp.isNotBlank()) {
                    db.temporaryWhitelistDao().insertWhitelist(
                        TemporaryWhitelistEntity(
                            urlOrKey = targetUrlOrApp,
                            expiresAt = valid24Hours
                        )
                    )
                    val pkgPrefix = targetUrlOrApp.substringBefore(" ").trim()
                    if (pkgPrefix.isNotBlank() && pkgPrefix != targetUrlOrApp) {
                        db.temporaryWhitelistDao().insertWhitelist(
                            TemporaryWhitelistEntity(
                                urlOrKey = pkgPrefix,
                                expiresAt = valid24Hours
                            )
                        )
                    }
                }
                Toast.makeText(
                    this@BlockOverlayActivity,
                    "אושר על ידי AI למשך 24 שעות: ${eval.explanationHe}",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun navigateToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }

    companion object {
        const val EXTRA_TARGET_URL_OR_APP = "extra_target_url_or_app"
        const val EXTRA_REASON = "extra_reason"
        const val EXTRA_PAGE_TITLE = "extra_page_title"
        const val EXTRA_TEXT_SNIPPET = "extra_text_snippet"
        const val EXTRA_IS_TEXT_BLOCK = "extra_is_text_block"
        const val EXTRA_INFERENCE_MS = "extra_inference_ms"
    }
}
