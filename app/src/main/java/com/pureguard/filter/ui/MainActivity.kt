package com.pureguard.filter.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pureguard.filter.admin.LockdownManager
import com.pureguard.filter.ai.NsfwClassifier
import com.pureguard.filter.data.BlockLogEntity
import com.pureguard.filter.data.BlockedAppEntity
import com.pureguard.filter.data.BlockedKeywordEntity
import com.pureguard.filter.data.PreferencesManager
import com.pureguard.filter.data.PureGuardDatabase
import com.pureguard.filter.databinding.ActivityMainBinding
import com.pureguard.filter.service.AdGuardDnsVpnService
import com.pureguard.filter.service.Android10ScreenCapturer
import com.pureguard.filter.service.FilterAccessibilityService
import com.pureguard.filter.service.ScreenCaptureKeepAliveService
import com.pureguard.filter.update.OtaUpdateManager
import com.pureguard.filter.util.BlockDiagnosticReporter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var db: PureGuardDatabase
    private lateinit var lockdownManager: LockdownManager
    private lateinit var otaUpdateManager: OtaUpdateManager
    private val timeFormatter = SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault())

    private var isWizardActive = false
    private var currentWizardStep = 1
    private var wasServiceEnabledLastCheck = false
    private var cachedKeywords: List<BlockedKeywordEntity> = emptyList()
    private var cachedLogsCount: Int = 0
    private var isLogsExpanded = false
    private var isApiKeyExpanded = false
    private var isUpdatingProfileUi = false

    private val dnsVpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startService(Intent(this, AdGuardDnsVpnService::class.java))
            Toast.makeText(this, "סינון פרסומות ופורנו (AdGuard Family DNS) הופעל אוטומטית! 🛡️", Toast.LENGTH_LONG).show()
            if (isWizardActive && currentWizardStep == 3) {
                currentWizardStep = 4
                renderWizardVisibilityAndStep()
            }
            refreshServiceAndLockdownStatus()
        } else {
            Toast.makeText(this, "יש לאשר חיבור DNS כדי להפעיל סינון פרסומות אוטומטי", Toast.LENGTH_LONG).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureKeepAliveService::class.java).apply {
                putExtra(ScreenCaptureKeepAliveService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureKeepAliveService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "סריקת תמונות AI ו-OCR הופעלה בהצלחה לאנדרואיד 10! ✅", Toast.LENGTH_LONG).show()
            refreshServiceAndLockdownStatus()
        } else {
            Toast.makeText(this, "יש לאשר לכידת מסך כדי ש-AI יזהה תמונות באנדרואיד 10", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)
        db = PureGuardDatabase.getInstance(this)
        lockdownManager = LockdownManager(this)
        otaUpdateManager = OtaUpdateManager(this)

        val serviceOn = isAccessibilityServiceEnabled()
        wasServiceEnabledLastCheck = serviceOn
        if (!prefs.isSetupWizardCompleted || !serviceOn) {
            isWizardActive = true
            currentWizardStep = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) 2 else 1
        }

        setupWizard()
        setupCard1FilterStatus()
        setupCard2KeywordsAppsAndSliders()
        setupCard3BlockLogs()
        setupCard4LockdownControl()
        renderWizardVisibilityAndStep()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceAndLockdownStatus()
    }

    private fun setupWizard() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            binding.tvWizardStep1Note.text =
                "במכשיר זה (מתחת לאנדרואיד 13) אין חסימת 'הגדרות מוגבלות' – ניתן לעבור ישירות לשלב הבא."
        }

        val openAppInfoClickListener = View.OnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
                Toast.makeText(
                    this,
                    "גלול למטה (או תפריט 3 נקודות) ולחץ 'אפשר הגדרות מוגבלות'",
                    Toast.LENGTH_LONG
                ).show()
                if (isWizardActive && currentWizardStep == 1) {
                    currentWizardStep = 2
                    renderWizardVisibilityAndStep()
                }
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        binding.btnWizardOpenAppInfo.setOnClickListener(openAppInfoClickListener)
        binding.btnWizardStep2OpenAppInfo.setOnClickListener(openAppInfoClickListener)

        binding.btnWizardOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            binding.btnWizardAndroid10Vision.visibility = View.VISIBLE
            binding.btnWizardAndroid10Vision.setOnClickListener {
                launchAndroid10ScreenCapture()
            }
        }

        binding.btnWizardAutoDns.setOnClickListener {
            activateOneClickAutoDns()
        }

        binding.btnWizardPrivateDns.setOnClickListener {
            openPrivateDnsDialog()
        }

        binding.rgWizardProfile.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingProfileUi) return@setOnCheckedChangeListener
            val level = if (checkedId == binding.rbWizardProfileModerate.id) {
                PreferencesManager.PROFILE_MODERATE_SHOPPING
            } else {
                PreferencesManager.PROFILE_STRICT
            }
            applyProfileSelection(level, showToast = false)
        }

        binding.btnWizardPrev.setOnClickListener {
            if (currentWizardStep > 1) {
                currentWizardStep--
                renderWizardVisibilityAndStep()
            }
        }

        binding.btnWizardNext.setOnClickListener {
            if (currentWizardStep < 4) {
                currentWizardStep++
                renderWizardVisibilityAndStep()
            } else {
                val chosenLevel = if (binding.rbWizardProfileModerate.isChecked) {
                    PreferencesManager.PROFILE_MODERATE_SHOPPING
                } else {
                    PreferencesManager.PROFILE_STRICT
                }
                applyProfileSelection(chosenLevel, showToast = false)
                prefs.isSetupWizardCompleted = true
                isWizardActive = false
                renderWizardVisibilityAndStep()
                Toast.makeText(this, "הגדרות מגן הקודש נשמרו בהצלחה! 🛡️", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnWizardSkip.setOnClickListener {
            isWizardActive = false
            prefs.isSetupWizardCompleted = true
            renderWizardVisibilityAndStep()
        }

        binding.btnOpenWizard.setOnClickListener {
            isWizardActive = true
            currentWizardStep = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) 2 else 1
            renderWizardVisibilityAndStep()
            refreshServiceAndLockdownStatus()
        }
    }

    private fun renderWizardVisibilityAndStep() {
        if (!isWizardActive) {
            binding.layoutWizardContainer.visibility = View.GONE
            binding.layoutDashboardContainer.visibility = View.VISIBLE
            return
        }

        binding.layoutWizardContainer.visibility = View.VISIBLE
        binding.layoutDashboardContainer.visibility = View.GONE

        binding.tvWizardStepIndicator.text = "שלב $currentWizardStep מתוך 4"
        binding.progressWizard.progress = currentWizardStep

        binding.wizardStep1.visibility = if (currentWizardStep == 1) View.VISIBLE else View.GONE
        binding.wizardStep2.visibility = if (currentWizardStep == 2) View.VISIBLE else View.GONE
        binding.wizardStep3.visibility = if (currentWizardStep == 3) View.VISIBLE else View.GONE
        binding.wizardStep4.visibility = if (currentWizardStep == 4) View.VISIBLE else View.GONE

        binding.btnWizardPrev.visibility = if (currentWizardStep > 1) View.VISIBLE else View.INVISIBLE
        binding.btnWizardNext.text = if (currentWizardStep == 4) "סיום והפעלת הגנה ✓" else "הבא ←"

        if (currentWizardStep == 4) {
            isUpdatingProfileUi = true
            if (prefs.filterProfileLevel == PreferencesManager.PROFILE_MODERATE_SHOPPING) {
                binding.rbWizardProfileModerate.isChecked = true
            } else {
                binding.rbWizardProfileStrict.isChecked = true
            }
            isUpdatingProfileUi = false
        }
    }

    private fun setupCard1FilterStatus() {
        binding.switchPauseFilterTesting.isChecked = prefs.isFilterPausedForTesting
        binding.switchPauseFilterTesting.setOnCheckedChangeListener { _, isChecked ->
            prefs.isFilterPausedForTesting = isChecked
            val msg = if (isChecked) "הסינון הושהה זמנית (מצב בדיקות)" else "הסינון חזר לפעול! 🛡️"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            refreshServiceAndLockdownStatus()
        }

        binding.btnOtaUpdate.setOnClickListener {
            binding.btnOtaUpdate.isEnabled = false
            binding.tvOtaStatus.visibility = View.VISIBLE
            lifecycleScope.launch {
                val res = otaUpdateManager.downloadAndInstallLatestApk { _, statusText ->
                    binding.tvOtaStatus.text = statusText
                }
                binding.btnOtaUpdate.isEnabled = true
                res.onFailure { err ->
                    binding.tvOtaStatus.text = "שגיאה בעדכון: ${err.localizedMessage}"
                }
            }
        }

        binding.switchBlockShorts.isChecked = prefs.blockYoutubeShortsAndReels
        binding.switchBlockShorts.setOnCheckedChangeListener { _, isChecked ->
            prefs.blockYoutubeShortsAndReels = isChecked
        }

        binding.switchBlockIncognito.isChecked = prefs.blockIncognitoMode
        binding.switchBlockIncognito.setOnCheckedChangeListener { _, isChecked ->
            prefs.blockIncognitoMode = isChecked
        }

        binding.switchEnforceSafeSearch.isChecked = prefs.enforceSafeSearchAndRestrictedMode
        binding.switchEnforceSafeSearch.setOnCheckedChangeListener { _, isChecked ->
            prefs.enforceSafeSearchAndRestrictedMode = isChecked
        }

        binding.switchBlockGrokAi.isChecked = prefs.blockUncensoredAiAndGrok
        binding.switchBlockGrokAi.setOnCheckedChangeListener { _, isChecked ->
            prefs.blockUncensoredAiAndGrok = isChecked
        }

        binding.switchDebugToast.isChecked = prefs.showDebugToast
        binding.switchDebugToast.setOnCheckedChangeListener { _, isChecked ->
            prefs.showDebugToast = isChecked
        }

        binding.btnToggleApiKeySection.setOnClickListener {
            isApiKeyExpanded = !isApiKeyExpanded
            binding.containerApiKey.visibility = if (isApiKeyExpanded) View.VISIBLE else View.GONE
        }

        binding.etGeminiApiKey.setText(prefs.geminiApiKey)
        binding.btnSaveApiKey.setOnClickListener {
            prefs.geminiApiKey = binding.etGeminiApiKey.text?.toString().orEmpty()
            Toast.makeText(this, "מפתח Gemini API נשמר בהצלחה", Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            binding.btnEnableAndroid10Vision.visibility = View.VISIBLE
            binding.btnEnableAndroid10Vision.setOnClickListener {
                launchAndroid10ScreenCapture()
            }
        }

        binding.btnAutoDnsToggle.setOnClickListener {
            if (AdGuardDnsVpnService.isRunning) {
                AdGuardDnsVpnService.stop(this)
                Toast.makeText(this, "סינון ה-DNS האוטומטי כובה", Toast.LENGTH_SHORT).show()
                refreshServiceAndLockdownStatus()
            } else {
                activateOneClickAutoDns()
            }
        }

        binding.btnPrivateDnsGuide.setOnClickListener {
            openPrivateDnsDialog()
        }
    }

    private fun activateOneClickAutoDns() {
        try {
            val prepIntent = VpnService.prepare(this)
            if (prepIntent != null) {
                dnsVpnLauncher.launch(prepIntent)
            } else {
                startService(Intent(this, AdGuardDnsVpnService::class.java))
                Toast.makeText(this, "סינון פרסומות ופורנו (AdGuard Family DNS) הופעל אוטומטית! 🛡️", Toast.LENGTH_LONG).show()
                if (isWizardActive && currentWizardStep == 3) {
                    currentWizardStep = 4
                    renderWizardVisibilityAndStep()
                }
                refreshServiceAndLockdownStatus()
            }
        } catch (e: Exception) {
            openPrivateDnsDialog()
        }
    }

    private fun launchAndroid10ScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun openPrivateDnsDialog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Private DNS", ADGUARD_FAMILY_DNS))

        AlertDialog.Builder(this)
            .setTitle("חסימת פרסומות + סינון משפחתי (0% סוללה)")
            .setMessage(
                "הכתובת $ADGUARD_FAMILY_DNS הועתקה ללוח!\n" +
                    "כתובת זו חוסמת את כל הפרסומות באתרים ובאפליקציות (כולל באנרים של ספרים/טאבולה/גוגל) + אתרי מבוגרים + כופה חיפוש בטוח.\n\n" +
                    "1. לחץ על 'פתח הגדרות חיבורים'\n" +
                    "2. בחר ב-'DNS פרטי' (Private DNS)\n" +
                    "3. בחר 'שם מארח של ספק DNS פרטי' והדבק:\n$ADGUARD_FAMILY_DNS"
            )
            .setPositiveButton("פתח הגדרות חיבורים") { _, _ ->
                try {
                    startActivity(Intent("android.settings.WIRELESS_SETTINGS"))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("סגור", null)
            .show()
    }

    private fun setupCard2KeywordsAppsAndSliders() {
        syncProfileAndSlidersUi()

        binding.rgFilterProfile.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingProfileUi) return@setOnCheckedChangeListener
            val level = when (checkedId) {
                binding.rbProfileStrict.id -> PreferencesManager.PROFILE_STRICT
                binding.rbProfileModerateShopping.id -> PreferencesManager.PROFILE_MODERATE_SHOPPING
                else -> PreferencesManager.PROFILE_CUSTOM
            }
            applyProfileSelection(level, showToast = true)
        }

        binding.sliderPornThreshold.addOnChangeListener { _, value, fromUser ->
            val pct = value.toInt()
            binding.tvPornThresholdLabel.text = "רגישות סף Porn: ${pct}%"
            prefs.pornThreshold = pct / 100f
            if (fromUser && !isUpdatingProfileUi) {
                isUpdatingProfileUi = true
                prefs.filterProfileLevel = PreferencesManager.PROFILE_CUSTOM
                binding.rbProfileCustom.isChecked = true
                isUpdatingProfileUi = false
            }
        }

        binding.sliderSexyThreshold.addOnChangeListener { _, value, fromUser ->
            val pct = value.toInt()
            binding.tvSexyThresholdLabel.text = "רגישות סף Sexy: ${pct}%"
            prefs.sexyThreshold = pct / 100f
            if (fromUser && !isUpdatingProfileUi) {
                isUpdatingProfileUi = true
                prefs.filterProfileLevel = PreferencesManager.PROFILE_CUSTOM
                binding.rbProfileCustom.isChecked = true
                isUpdatingProfileUi = false
            }
        }

        binding.btnManageKeywordsDialog.setOnClickListener {
            showManageKeywordsDialog()
        }

        binding.btnAddKeyword.setOnClickListener {
            val raw = binding.etNewKeyword.text?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) return@setOnClickListener
            val isDomain = raw.contains(".") && !raw.contains(" ")
            lifecycleScope.launch {
                db.blockedKeywordsDao().insert(
                    BlockedKeywordEntity(keyword = raw, isDomain = isDomain)
                )
                binding.etNewKeyword.setText("")
                Toast.makeText(this@MainActivity, "נוסף בהצלחה למאגר הסינון ✅", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAddBlockedApp.setOnClickListener {
            val pkg = binding.etNewBlockedPackage.text?.toString()?.trim().orEmpty()
            if (pkg.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                db.blockedAppsDao().insert(
                    BlockedAppEntity(packageName = pkg, appLabel = pkg)
                )
                binding.etNewBlockedPackage.setText("")
            }
        }

        lifecycleScope.launch {
            db.blockedKeywordsDao().observeAll().collectLatest { keywords ->
                cachedKeywords = keywords
                updateKeywordsButtonLabel()
            }
        }

        lifecycleScope.launch {
            db.blockedAppsDao().observeAll().collectLatest { apps ->
                renderBlockedAppsList(apps)
            }
        }
    }

    private fun applyProfileSelection(level: Int, showToast: Boolean) {
        prefs.filterProfileLevel = level
        when (level) {
            PreferencesManager.PROFILE_STRICT -> {
                prefs.pornThreshold = 0.65f
                prefs.sexyThreshold = 0.55f
                if (showToast) {
                    Toast.makeText(this, "הופעל פרופיל: שמירת עיניים מלאה (מחמיר) 🛡️", Toast.LENGTH_SHORT).show()
                }
            }
            PreferencesManager.PROFILE_MODERATE_SHOPPING -> {
                prefs.pornThreshold = 0.72f
                prefs.sexyThreshold = 0.85f
                if (showToast) {
                    Toast.makeText(this, "הופעל פרופיל: מותאם לנשים (קניות ואופנה פתוחים) 🛍️", Toast.LENGTH_SHORT).show()
                }
            }
        }
        syncProfileAndSlidersUi()
    }

    private fun syncProfileAndSlidersUi() {
        isUpdatingProfileUi = true
        when (prefs.filterProfileLevel) {
            PreferencesManager.PROFILE_STRICT -> {
                binding.rbProfileStrict.isChecked = true
                binding.rbWizardProfileStrict.isChecked = true
            }
            PreferencesManager.PROFILE_MODERATE_SHOPPING -> {
                binding.rbProfileModerateShopping.isChecked = true
                binding.rbWizardProfileModerate.isChecked = true
            }
            else -> binding.rbProfileCustom.isChecked = true
        }

        val pornPct = (prefs.pornThreshold * 100).toInt().coerceIn(30, 90)
        val sexyPct = (prefs.sexyThreshold * 100).toInt().coerceIn(30, 90)

        binding.sliderPornThreshold.value = pornPct.toFloat()
        binding.tvPornThresholdLabel.text = "רגישות סף Porn: ${pornPct}%"

        binding.sliderSexyThreshold.value = sexyPct.toFloat()
        binding.tvSexyThresholdLabel.text = if (prefs.filterProfileLevel == PreferencesManager.PROFILE_MODERATE_SHOPPING) {
            "רגישות סף Sexy: ${sexyPct}% (קניות פתוחות)"
        } else {
            "רגישות סף Sexy: ${sexyPct}%"
        }
        isUpdatingProfileUi = false
    }

    private fun updateKeywordsButtonLabel() {
        binding.btnManageKeywordsDialog.text =
            "🔒 מאגר מילים ואתרים חסומים (${cachedKeywords.size} פריטים) – לחץ לפתיחה ועריכה"
    }

    private fun showManageKeywordsDialog() {
        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("מאגר מילים ואתרים חסומים (${cachedKeywords.size})")
            .setNegativeButton("סגור", null)
            .create()

        if (cachedKeywords.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "אין פריטים במאגר."
                textSize = 14f
                setTextColor(0xFF546E7A.toInt())
                setPadding(12, 16, 12, 16)
            }
            listLayout.addView(emptyTv)
        } else {
            for (item in cachedKeywords) {
                val row = TextView(this).apply {
                    val badge = if (item.isDomain) "[דומיין]" else "[מילה]"
                    text = "✕  $badge ${item.keyword}"
                    textSize = 14f
                    setTextColor(0xFF1E293B.toInt())
                    setPadding(12, 14, 12, 14)
                    setOnClickListener { clickedView ->
                        lifecycleScope.launch {
                            db.blockedKeywordsDao().deleteById(item.id)
                            listLayout.removeView(clickedView)
                            dialog.setTitle("מאגר מילים ואתרים חסומים (${listLayout.childCount})")
                            Toast.makeText(this@MainActivity, "הוסר מהמאגר", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                listLayout.addView(row)
            }
        }

        val scrollView = ScrollView(this).apply {
            addView(listLayout)
        }
        dialog.setView(scrollView)
        dialog.show()
    }

    private fun renderBlockedAppsList(apps: List<BlockedAppEntity>) {
        binding.containerBlockedApps.removeAllViews()
        for (app in apps) {
            val tv = TextView(this).apply {
                text = "✕  [אפליקציה חסומה] ${app.packageName}"
                textSize = 13f
                setTextColor(0xFFB71C1C.toInt())
                setPadding(16, 8, 16, 8)
                setOnClickListener {
                    lifecycleScope.launch {
                        db.blockedAppsDao().deleteByPackage(app.packageName)
                    }
                }
            }
            binding.containerBlockedApps.addView(tv)
        }
    }

    private fun setupCard3BlockLogs() {
        binding.btnToggleLogs.setOnClickListener {
            isLogsExpanded = !isLogsExpanded
            binding.containerBlockLogs.visibility = if (isLogsExpanded) View.VISIBLE else View.GONE
            updateLogsToggleLabel()
        }

        binding.btnClearLogs.setOnClickListener {
            lifecycleScope.launch {
                db.blockLogsDao().clearAll()
            }
        }

        binding.btnShareLastBlockFromDashboard.setOnClickListener {
            BlockDiagnosticReporter.shareLastBlockReport(
                context = this,
                targetUrlOrApp = "חסימה אחרונה מלוח הבקרה",
                blockReason = "דו\"ח אבחון אחרון",
                pageTitle = "",
                textSnippet = ""
            )
        }

        lifecycleScope.launch {
            db.blockLogsDao().observeRecentLogs().collectLatest { logs ->
                renderBlockLogs(logs)
            }
        }
    }

    private fun updateLogsToggleLabel() {
        binding.btnToggleLogs.text = if (isLogsExpanded) {
            "🙈 הסתר יומן חסימות אחרונות ($cachedLogsCount אירועים)"
        } else {
            "📋 הצג יומן חסימות אחרונות ($cachedLogsCount אירועים)"
        }
    }

    private fun renderBlockLogs(logs: List<BlockLogEntity>) {
        cachedLogsCount = logs.size
        updateLogsToggleLabel()
        binding.containerBlockLogs.removeAllViews()
        if (logs.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "אין חסימות מתועדות עדיין."
                textSize = 13f
                setTextColor(0xFF78909C.toInt())
            }
            binding.containerBlockLogs.addView(emptyTv)
            return
        }

        for (log in logs) {
            val timeStr = timeFormatter.format(Date(log.timestamp))
            val row = TextView(this).apply {
                text = "[$timeStr] (${log.inferenceTimeMs}ms)\nמקור: ${log.targetAppOrUrl}\nסיבה: ${log.triggerReason}"
                textSize = 13f
                setTextColor(0xFF263238.toInt())
                setPadding(12, 12, 12, 12)
            }
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1
                )
                setBackgroundColor(0xFFECEFF1.toInt())
            }
            binding.containerBlockLogs.addView(row)
            binding.containerBlockLogs.addView(divider)
        }
    }

    private fun setupCard4LockdownControl() {
        binding.btnToggleLockdown.setOnClickListener {
            if (!prefs.isLockdownEnabled) {
                showEnableLockdownConfirmationDialog()
            } else {
                handleDisableLockdownFlow()
            }
        }
    }

    private fun showEnableLockdownConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("אזהרה: הפעלת מצב נעילה מלא (Production Mode)")
            .setMessage(
                "במצב בדיקות (Sandbox) הנוכחי, האפליקציה אינה נועלת את המכשיר וניתנת להסרה בכל רגע.\n\n" +
                    "האם סיימת את שלב הבדיקות וברצונך להפעיל את מצב הנעילה המלא?\n" +
                    "(שחרור הנעילה ידרוש המתנה של 45 דקות והקלדה ידנית של פרק מ\"א בתניא)."
            )
            .setPositiveButton("כן, הפעל מצב נעילה מלא") { _, _ ->
                lockdownManager.enableProductionLockdown()
                refreshServiceAndLockdownStatus()
                Toast.makeText(this, "מצב נעילה מלא הופעל", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("ביטול (הישאר במצב בדיקות)", null)
            .show()
    }

    private fun handleDisableLockdownFlow() {
        val remainingMs = lockdownManager.getRemainingCooldownMs()
        if (remainingMs == -1L) {
            AlertDialog.Builder(this)
                .setTitle("בקשה לכיבוי מצב נעילה")
                .setMessage("כדי לכבות את מצב הנעילה המלא, יש להפעיל תחילה טיימר המתנה של 45 דקות (מוגן מפני שינוי שעון).")
                .setPositiveButton("התחל ספירה של 45 דקות") { _, _ ->
                    lockdownManager.startUnlockCooldown()
                    refreshServiceAndLockdownStatus()
                    Toast.makeText(this, "הספירה לאחור (45 דקות) החלה", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("ביטול", null)
                .show()
            return
        }

        if (remainingMs > 0L) {
            val minutesLeft = (remainingMs / 60000L) + 1
            AlertDialog.Builder(this)
                .setTitle("זמן המתנה פעיל")
                .setMessage("נותרו עוד $minutesLeft דקות לסיום זמן ההמתנה לפני שניתן להקליד את אתגר התניא.")
                .setPositiveButton("הבנתי", null)
                .show()
            return
        }

        showTanyaTypingChallengeDialog()
    }

    private fun showTanyaTypingChallengeDialog() {
        val inputEditText = EditText(this).apply {
            hint = "הקלד כאן ידנית (ללא העתק-הדבק)..."
            isLongClickable = false
            setTextIsSelectable(false)
            customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
                override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
                override fun onDestroyActionMode(mode: ActionMode?) {}
            }
        }

        AlertDialog.Builder(this)
            .setTitle("אתגר שחרור נעילה – תניא פרק מ\"א")
            .setMessage(
                "הקלד ידנית (ללא העתק-הדבק) את הקטע הבא:\n\n" +
                    LockdownManager.TANYA_CHAPTER_41_CHALLENGE
            )
            .setView(inputEditText)
            .setPositiveButton("אמת ושחרר נעילה") { _, _ ->
                val typed = inputEditText.text?.toString().orEmpty()
                if (lockdownManager.verifyTanyaChallenge(typed)) {
                    lockdownManager.disableProductionLockdown()
                    refreshServiceAndLockdownStatus()
                    Toast.makeText(this, "מצב הנעילה כובה. חזרת למצב בדיקות רגיל.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "הטקסט שהוקלד אינו תואם במדויק. נסה שנית.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun getConfiguredPrivateDnsHost(): String? {
        return try {
            val mode = Settings.Global.getString(contentResolver, "private_dns_mode")
            val specifier = Settings.Global.getString(contentResolver, "private_dns_specifier")
            if (mode == "hostname" && !specifier.isNullOrBlank()) specifier else null
        } catch (_: Exception) {
            null
        }
    }

    private fun refreshServiceAndLockdownStatus() {
        val isServiceEnabled = isAccessibilityServiceEnabled()

        if (isWizardActive && (currentWizardStep == 1 || currentWizardStep == 2) && isServiceEnabled && !wasServiceEnabledLastCheck) {
            if (prefs.isSetupWizardCompleted) {
                isWizardActive = false
                Toast.makeText(this, "שירות הנגישות הופעל מחדש! ההגדרות הקודמות שלך נשמרו ✅", Toast.LENGTH_LONG).show()
            } else {
                currentWizardStep = 3
                Toast.makeText(this, "מעולה! שירות הנגישות של מגן הקודש הופעל ✅", Toast.LENGTH_LONG).show()
            }
            renderWizardVisibilityAndStep()
        }
        wasServiceEnabledLastCheck = isServiceEnabled

        if (isServiceEnabled) {
            binding.tvAccessibilityStatus.text = "שירות נגישות (Kodeshield – מגן הקודש): פעיל ✅"
            binding.tvAccessibilityStatus.setTextColor(0xFF2E7D32.toInt())
            binding.btnOpenAccessibility.visibility = View.GONE
            binding.tvWizardStep2Status.text = "סטטוס נוכחי: שירות הנגישות פעיל ✅"
            binding.tvWizardStep2Status.setTextColor(0xFF2E7D32.toInt())
            binding.tvWizardStep2Status.setBackgroundColor(0xFFE8F5E9.toInt())
        } else {
            binding.tvAccessibilityStatus.text = "שירות נגישות (Kodeshield – מגן הקודש): כבוי ⚠️"
            binding.tvAccessibilityStatus.setTextColor(0xFFC62828.toInt())
            binding.btnOpenAccessibility.visibility = View.VISIBLE
            binding.tvWizardStep2Status.text = "סטטוס נוכחי: שירות הנגישות כבוי ⏳"
            binding.tvWizardStep2Status.setTextColor(0xFFC62828.toInt())
            binding.tvWizardStep2Status.setBackgroundColor(0xFFFFEBEE.toInt())
        }

        val dnsHost = getConfiguredPrivateDnsHost()
        val isAutoDnsVpnActive = AdGuardDnsVpnService.isRunning
        if (isAutoDnsVpnActive) {
            binding.tvPrivateDnsStatus.text = "סינון פרסומות ופורנו (AdGuard Family DNS): פעיל אוטומטית ✅"
            binding.tvPrivateDnsStatus.setTextColor(0xFF2E7D32.toInt())
            binding.btnAutoDnsToggle.text = "🛡️ חסימת פרסומות ו-DNS אוטומטית פעילה ✅ (לחץ לכיבוי)"
            binding.tvWizardStep3Status.text = "סטטוס DNS: חסימת פרסומות ופורנו פעילה אוטומטית ✅"
            binding.tvWizardStep3Status.setTextColor(0xFF2E7D32.toInt())
        } else if (dnsHost != null) {
            val isAdGuard = dnsHost.equals(ADGUARD_FAMILY_DNS, ignoreCase = true)
            val dnsMsg = if (isAdGuard) {
                "DNS פרטי: מוגדר ($dnsHost) ✅"
            } else {
                "DNS פרטי: מוגדר ($dnsHost)"
            }
            binding.tvPrivateDnsStatus.text = dnsMsg
            binding.tvPrivateDnsStatus.setTextColor(0xFF2E7D32.toInt())
            binding.btnAutoDnsToggle.text = "🛡️ הפעל חסימת פרסומות ו-DNS אוטומטית (לחיצה אחת)"
            binding.tvWizardStep3Status.text = "סטטוס DNS פרטי: מוגדר ($dnsHost) ✅"
            binding.tvWizardStep3Status.setTextColor(0xFF2E7D32.toInt())
        } else {
            binding.tvPrivateDnsStatus.text = "DNS (חוסם פרסומות ופורנו): לא פעיל ⚠️ (לחץ להפעלה בלחיצה אחת)"
            binding.tvPrivateDnsStatus.setTextColor(0xFF546E7A.toInt())
            binding.btnAutoDnsToggle.text = "🛡️ הפעל חסימת פרסומות ו-DNS אוטומטית (לחיצה אחת)"
            binding.tvWizardStep3Status.text = "סטטוס DNS: לחץ על הכפתור למטה להפעלה אוטומטית בלחיצה אחת ⏳"
            binding.tvWizardStep3Status.setTextColor(0xFF546E7A.toInt())
        }

        val hasModelFile = try {
            val assetsList = assets.list("") ?: emptyArray()
            assetsList.contains(NsfwClassifier.MODEL_FILE)
        } catch (_: Exception) {
            false
        }

        binding.tvModelStatus.text = if (hasModelFile) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                if (Android10ScreenCapturer.isReady) {
                    "מודל AI + OCR (אנדרואיד 10): פעיל ודוגם בזיכרון ✅"
                } else {
                    "מודל AI + OCR (אנדרואיד 10): לחץ להפעלת לכידת מסך ⚠️"
                }
            } else {
                "מודל AI מקומי (nsfw.tflite) + OCR: מותקן וזמין בזיכרון ✅"
            }
        } else {
            "מודל AI מקומי (nsfw.tflite): ממתין לקובץ מודל (הגנת טקסט ודומיינים פעילה)"
        }

        if (!prefs.isLockdownEnabled) {
            binding.switchPauseFilterTesting.visibility = View.VISIBLE
            binding.rbProfileStrict.isEnabled = true
            binding.rbProfileModerateShopping.isEnabled = true
            binding.rbProfileCustom.isEnabled = true
            binding.tvModeBanner.text =
                "מצב פעולה: בדיקות ארגז חול (Sandbox Mode – ניתן להסרה בכל עת)"
            binding.tvLockdownStatus.text =
                "כבוי כברירת מחדל (מצב בדיקות בטוח – ללא חסימת ADB וללא מניעת הסרה)"
            binding.btnToggleLockdown.text = "הפעל מצב נעילה מלא (לאחר סיום הבדיקות)"
        } else {
            binding.switchPauseFilterTesting.visibility = View.GONE
            binding.rbProfileStrict.isEnabled = false
            binding.rbProfileModerateShopping.isEnabled = false
            binding.rbProfileCustom.isEnabled = false
            binding.tvModeBanner.text =
                "מצב פעולה: נעילה מלאה פעילה (Production Lockdown)"
            val rem = lockdownManager.getRemainingCooldownMs()
            val remText = when {
                rem > 0L -> " (ספירה לאחור לשחרור: ${(rem / 60000L) + 1} דק')"
                rem == 0L -> " (זמן ההמתנה הסתיים – לחץ להקלדת אתגר התניא)"
                else -> ""
            }
            binding.tvLockdownStatus.text = "מצב נעילה מלא פעיל 🔒$remText"
            binding.btnToggleLockdown.text = "בקש שחרור נעילה (45 דק' + אתגר תניא)"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            val resolveInfo = service.resolveInfo?.serviceInfo ?: continue
            if (resolveInfo.packageName == packageName &&
                resolveInfo.name == FilterAccessibilityService::class.java.name
            ) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val ADGUARD_FAMILY_DNS = "family.adguard-dns.com"
    }
}
