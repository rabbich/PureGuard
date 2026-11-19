package com.pureguard.filter.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
import com.pureguard.filter.service.Android10ScreenCapturer
import com.pureguard.filter.service.FilterAccessibilityService
import com.pureguard.filter.service.ScreenCaptureKeepAliveService
import com.pureguard.filter.update.OtaUpdateManager
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

        setupCard1FilterStatus()
        setupCard2KeywordsAppsAndSliders()
        setupCard3BlockLogs()
        setupCard4LockdownControl()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceAndLockdownStatus()
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
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }

        binding.btnPrivateDnsGuide.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Private DNS", "family.adguard-dns.com"))

            AlertDialog.Builder(this)
                .setTitle("חסימת פרסומות + סינון משפחתי (0% סוללה)")
                .setMessage(
                    "הכתובת family.adguard-dns.com הועתקה ללוח!\n" +
                        "כתובת זו חוסמת את כל הפרסומות באתרים ובאפליקציות (כולל באנרים של ספרים/טאבולה/גוגל) + אתרי מבוגרים + כופה חיפוש בטוח.\n\n" +
                        "1. לחץ על 'פתח הגדרות חיבורים'\n" +
                        "2. בחר ב-'DNS פרטי' (Private DNS)\n" +
                        "3. בחר 'שם מארח של ספק DNS פרטי' והדבק:\nfamily.adguard-dns.com"
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
    }

    private fun setupCard2KeywordsAppsAndSliders() {
        var isUpdatingSlidersProgrammatically = false

        fun syncProfileRadioSelection() {
            when (prefs.filterProfileLevel) {
                PreferencesManager.PROFILE_STRICT -> binding.rbProfileStrict.isChecked = true
                PreferencesManager.PROFILE_MODERATE_SHOPPING -> binding.rbProfileModerateShopping.isChecked = true
                else -> binding.rbProfileCustom.isChecked = true
            }
        }

        syncProfileRadioSelection()

        val initialPornPercent = (prefs.pornThreshold * 100).toInt().coerceIn(30, 90)
        val initialSexyPercent = (prefs.sexyThreshold * 100).toInt().coerceIn(30, 90)

        binding.sliderPornThreshold.value = initialPornPercent.toFloat()
        binding.tvPornThresholdLabel.text = "רגישות סף Porn: ${initialPornPercent}%"

        binding.sliderSexyThreshold.value = initialSexyPercent.toFloat()
        binding.tvSexyThresholdLabel.text = "רגישות סף Sexy: ${initialSexyPercent}%"

        binding.rgFilterProfile.setOnCheckedChangeListener { _, checkedId ->
            if (isUpdatingSlidersProgrammatically) return@setOnCheckedChangeListener
            isUpdatingSlidersProgrammatically = true
            when (checkedId) {
                binding.rbProfileStrict.id -> {
                    prefs.filterProfileLevel = PreferencesManager.PROFILE_STRICT
                    prefs.pornThreshold = 0.30f
                    prefs.sexyThreshold = 0.35f
                    binding.sliderPornThreshold.value = 30f
                    binding.sliderSexyThreshold.value = 35f
                    binding.tvPornThresholdLabel.text = "רגישות סף Porn: 30%"
                    binding.tvSexyThresholdLabel.text = "רגישות סף Sexy: 35%"
                    Toast.makeText(this, "הופעל פרופיל: שמירת עיניים מלאה (מחמיר) 🛡️", Toast.LENGTH_SHORT).show()
                }
                binding.rbProfileModerateShopping.id -> {
                    prefs.filterProfileLevel = PreferencesManager.PROFILE_MODERATE_SHOPPING
                    prefs.pornThreshold = 0.55f
                    prefs.sexyThreshold = 0.85f
                    binding.sliderPornThreshold.value = 55f
                    binding.sliderSexyThreshold.value = 85f
                    binding.tvPornThresholdLabel.text = "רגישות סף Porn: 55%"
                    binding.tvSexyThresholdLabel.text = "רגישות סף Sexy: 85% (קניות פתוחות)"
                    Toast.makeText(this, "הופעל פרופיל: מותאם לנשים (קניות ואופנה פתוחים) 🛍️", Toast.LENGTH_SHORT).show()
                }
                binding.rbProfileCustom.id -> {
                    prefs.filterProfileLevel = PreferencesManager.PROFILE_CUSTOM
                }
            }
            isUpdatingSlidersProgrammatically = false
        }

        binding.sliderPornThreshold.addOnChangeListener { _, value, fromUser ->
            val pct = value.toInt()
            binding.tvPornThresholdLabel.text = "רגישות סף Porn: ${pct}%"
            prefs.pornThreshold = pct / 100f
            if (fromUser && !isUpdatingSlidersProgrammatically) {
                isUpdatingSlidersProgrammatically = true
                prefs.filterProfileLevel = PreferencesManager.PROFILE_CUSTOM
                binding.rbProfileCustom.isChecked = true
                isUpdatingSlidersProgrammatically = false
            }
        }

        binding.sliderSexyThreshold.addOnChangeListener { _, value, fromUser ->
            val pct = value.toInt()
            binding.tvSexyThresholdLabel.text = "רגישות סף Sexy: ${pct}%"
            prefs.sexyThreshold = pct / 100f
            if (fromUser && !isUpdatingSlidersProgrammatically) {
                isUpdatingSlidersProgrammatically = true
                prefs.filterProfileLevel = PreferencesManager.PROFILE_CUSTOM
                binding.rbProfileCustom.isChecked = true
                isUpdatingSlidersProgrammatically = false
            }
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
                renderKeywordsList(keywords)
            }
        }

        lifecycleScope.launch {
            db.blockedAppsDao().observeAll().collectLatest { apps ->
                renderBlockedAppsList(apps)
            }
        }
    }

    private fun renderKeywordsList(keywords: List<BlockedKeywordEntity>) {
        binding.containerKeywords.removeAllViews()
        for (item in keywords) {
            val tv = TextView(this).apply {
                val badge = if (item.isDomain) "[דומיין]" else "[מילה]"
                text = "✕  $badge ${item.keyword}"
                textSize = 14f
                setTextColor(0xFF1E293B.toInt())
                setPadding(16, 10, 16, 10)
                setOnClickListener {
                    lifecycleScope.launch {
                        db.blockedKeywordsDao().deleteById(item.id)
                    }
                }
            }
            binding.containerKeywords.addView(tv)
        }
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
        binding.btnClearLogs.setOnClickListener {
            lifecycleScope.launch {
                db.blockLogsDao().clearAll()
            }
        }

        lifecycleScope.launch {
            db.blockLogsDao().observeRecentLogs().collectLatest { logs ->
                renderBlockLogs(logs)
            }
        }
    }

    private fun renderBlockLogs(logs: List<BlockLogEntity>) {
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

        // Cooldown completed (remainingMs == 0L) -> Show Tanya Chapter 41 manual typing challenge (no copy/paste)
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

    private fun refreshServiceAndLockdownStatus() {
        val isServiceEnabled = isAccessibilityServiceEnabled()
        if (isServiceEnabled) {
            binding.tvAccessibilityStatus.text = "שירות נגישות (FilterAccessibilityService): פעיל ✅"
            binding.tvAccessibilityStatus.setTextColor(0xFF2E7D32.toInt())
        } else {
            binding.tvAccessibilityStatus.text = "שירות נגישות: כבוי ⚠️ (לחץ על הכפתור למטה להפעלה)"
            binding.tvAccessibilityStatus.setTextColor(0xFFC62828.toInt())
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
                    "מודל AI + OCR (אנדרואיד 10): לחץ על הכפתור למטה להפעלת לכידת מסך ⚠️"
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
}
