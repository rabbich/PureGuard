package com.pureguard.filter.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope
import com.pureguard.filter.admin.LockdownManager
import com.pureguard.filter.data.BlockLogEntity
import com.pureguard.filter.data.BlockedAppEntity
import com.pureguard.filter.data.BlockedKeywordEntity
import com.pureguard.filter.data.PreferencesManager
import com.pureguard.filter.data.PureGuardDatabase
import com.pureguard.filter.databinding.ActivityMainBinding
import com.pureguard.filter.service.FilterAccessibilityService
import com.pureguard.filter.update.OtaUpdateManager
import com.pureguard.filter.util.BlockDiagnosticReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var cachedDomains: List<BlockedKeywordEntity> = emptyList()
    private var cachedBlockedApps: List<BlockedAppEntity> = emptyList()
    private var cachedLogsCount: Int = 0
    private var isLogsExpanded = false

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (lockdownManager.isAdminActive()) {
            lockdownManager.enableProductionLockdown()
            refreshStatusUi()
            Toast.makeText(
                this,
                "🔒 נעילה קבועה + הגנת מניעת הסרה הופעלו בהצלחה!",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(
                this,
                "יש לאשר 'הפעל אפליקציית מנהל מכשיר' כדי למנוע הסרה מהמסך הראשי",
                Toast.LENGTH_LONG
            ).show()
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
            currentWizardStep = if (serviceOn) 2 else 1
        }

        setupWizard()
        setupCard1ProtectionAndDns()
        setupCard2WebsitesAndApps()
        setupCard3LogsAndLockdown()
        renderWizardVisibilityAndStep()
    }

    override fun onResume() {
        super.onResume()
        refreshStatusUi()
    }

    private fun setupWizard() {
        // Step 1: Open Accessibility FIRST (Android 13/14 reveals "Allow restricted settings" in App Info only AFTER a blocked attempt!)
        binding.btnWizardOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnWizardOpenAppInfo.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
                Toast.makeText(
                    this,
                    "גלול מטה (או תפריט 3 נקודות) ולחץ 'אפשר הגדרות מוגבלות'",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        // Step 2: Private DNS setup (0% battery)
        binding.btnWizardDnsCloudflare.setOnClickListener {
            openPrivateDnsInstructionsDialog(
                CLOUDFLARE_FAMILY_DNS,
                "Cloudflare Family – סינון רשת נקי ומהיר במיוחד (0% סוללה)"
            )
        }

        binding.btnWizardDnsAdGuard.setOnClickListener {
            openPrivateDnsInstructionsDialog(
                ADGUARD_FAMILY_DNS,
                "AdGuard Family – סינון רשת נקי + חסימת פרסומות (0% סוללה)"
            )
        }

        binding.btnWizardPrev.setOnClickListener {
            if (currentWizardStep > 1) {
                currentWizardStep--
                renderWizardVisibilityAndStep()
            }
        }

        binding.btnWizardNext.setOnClickListener {
            if (currentWizardStep < 2) {
                currentWizardStep++
                renderWizardVisibilityAndStep()
            } else {
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
            currentWizardStep = 1
            renderWizardVisibilityAndStep()
            refreshStatusUi()
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

        binding.tvWizardStepIndicator.text = "שלב $currentWizardStep מתוך 2"
        binding.progressWizard.progress = currentWizardStep

        binding.layoutWizardStep1.visibility = if (currentWizardStep == 1) View.VISIBLE else View.GONE
        binding.layoutWizardStep2.visibility = if (currentWizardStep == 2) View.VISIBLE else View.GONE

        binding.btnWizardPrev.visibility = if (currentWizardStep > 1) View.VISIBLE else View.INVISIBLE
        binding.btnWizardNext.text = if (currentWizardStep == 2) "סיום וכניסה לאפליקציה ✅" else "הבא ⬅️"
    }

    private fun setupCard1ProtectionAndDns() {
        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnConfigureDns.setOnClickListener {
            showDnsProviderChooserDialog()
        }

        binding.switchLockPrivateDns.isChecked = prefs.lockPrivateDnsSettings
        binding.switchLockPrivateDns.setOnCheckedChangeListener { _, isChecked ->
            prefs.lockPrivateDnsSettings = isChecked
            val msg = if (isChecked) {
                "🔒 מסך הגדרות ה-DNS הפרטי נעול כעת"
            } else {
                "🔓 נעילת מסך ה-DNS הוסרה זמנית"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchEnforceSafeSearch.isChecked = prefs.enforceSafeSearchAndRestrictedMode
        binding.switchEnforceSafeSearch.setOnCheckedChangeListener { _, isChecked ->
            prefs.enforceSafeSearchAndRestrictedMode = isChecked
        }

        binding.switchPauseFilterTesting.isChecked = prefs.isFilterPausedForTesting
        binding.switchPauseFilterTesting.setOnCheckedChangeListener { _, isChecked ->
            if (prefs.isLockdownEnabled) {
                binding.switchPauseFilterTesting.isChecked = false
                Toast.makeText(this, "לא ניתן להשהות את ההגנה במצב נעילה קבועה", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            prefs.isFilterPausedForTesting = isChecked
            val statusMsg = if (isChecked) "ההגנה הושהתה זמנית" else "ההגנה פעילה ✅"
            Toast.makeText(this, statusMsg, Toast.LENGTH_SHORT).show()
        }

        binding.btnOtaUpdate.setOnClickListener {
            binding.btnOtaUpdate.isEnabled = false
            binding.progressOtaUpdate.visibility = View.VISIBLE
            binding.tvOtaStatus.visibility = View.VISIBLE
            binding.progressOtaUpdate.progress = 0
            binding.tvOtaStatus.text = "בודק עדכון גרסה..."

            lifecycleScope.launch {
                val res = otaUpdateManager.downloadAndInstallLatestApk { pct, statusText ->
                    binding.progressOtaUpdate.progress = pct
                    binding.tvOtaStatus.text = statusText
                }
                binding.btnOtaUpdate.isEnabled = true
                res.onFailure { err ->
                    binding.tvOtaStatus.text = "שגיאה בעדכון: ${err.localizedMessage}"
                }
            }
        }
    }

    private fun showDnsProviderChooserDialog() {
        val options = arrayOf(
            "🌐 Cloudflare Family (מומלץ ומהיר – סינון אתרים לא ראויים)",
            "🛡️ AdGuard Family (סינון אתרים לא ראויים + חסימת פרסומות)"
        )
        AlertDialog.Builder(this)
            .setTitle("בחר ספק סינון רשת נקי (0% סוללה)")
            .setItems(options) { _, which ->
                if (which == 0) {
                    openPrivateDnsInstructionsDialog(
                        CLOUDFLARE_FAMILY_DNS,
                        "Cloudflare Family – סינון רשת נקי ומהיר במיוחד (0% סוללה)"
                    )
                } else {
                    openPrivateDnsInstructionsDialog(
                        ADGUARD_FAMILY_DNS,
                        "AdGuard Family – סינון רשת נקי + חסימת פרסומות (0% סוללה)"
                    )
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun openPrivateDnsInstructionsDialog(dnsHostname: String, descriptionHe: String) {
        if (prefs.lockPrivateDnsSettings && !prefs.isLockdownEnabled) {
            prefs.lockPrivateDnsSettings = false
            binding.switchLockPrivateDns.isChecked = false
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Private DNS", dnsHostname))

        AlertDialog.Builder(this)
            .setTitle("הגדרת סינון רשת נקי (DNS פרטי)")
            .setMessage(
                "הכתובת $dnsHostname הועתקה ללוח!\n" +
                    "$descriptionHe\n\n" +
                    "1. לחץ על 'פתח הגדרות חיבורים'\n" +
                    "2. בחר ב-'DNS פרטי' (Private DNS)\n" +
                    "3. בחר 'שם מארח של ספק DNS פרטי' והדבק:\n$dnsHostname\n" +
                    "4. חזור לאפליקציה והפעל את '🔒 נעל את מסך הגדרות ה-DNS הפרטי'."
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

    private fun setupCard2WebsitesAndApps() {
        binding.btnAddDomain.setOnClickListener {
            val raw = binding.etNewDomain.text?.toString()?.trim()?.lowercase().orEmpty()
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
                .substringBefore("/")
            if (raw.isEmpty() || !raw.contains(".")) {
                Toast.makeText(this, "נא להזין כתובת אתר תקינה (למשל example.com)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                db.blockedKeywordsDao().insert(
                    BlockedKeywordEntity(keyword = raw, isDomain = true)
                )
                binding.etNewDomain.setText("")
                Toast.makeText(this@MainActivity, "האתר $raw נוסף לרשימת החסימה ✅", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnManageDomainsDialog.setOnClickListener {
            showManageDomainsDialog()
        }

        binding.btnPickAppToBlock.setOnClickListener {
            showInstalledAppsPickerDialog()
        }

        lifecycleScope.launch {
            db.blockedKeywordsDao().observeAll().collectLatest { list ->
                cachedDomains = list.filter { it.isDomain }
                binding.btnManageDomainsDialog.text =
                    "🔒 ניהול אתרים חסומים (${cachedDomains.size} אתרים)"
            }
        }

        lifecycleScope.launch {
            db.blockedAppsDao().observeAll().collectLatest { apps ->
                cachedBlockedApps = apps
                renderBlockedAppsList(apps)
            }
        }
    }

    private fun showManageDomainsDialog() {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 24)
        }
        scroll.addView(container)

        if (cachedDomains.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "אין אתרים מותאמים ברשימה עדיין."
                textSize = 14f
                setTextColor(0xFF546E7A.toInt())
            }
            container.addView(emptyTv)
        } else {
            val locked = prefs.isLockdownEnabled
            for (item in cachedDomains) {
                val tv = TextView(this).apply {
                    text = if (locked) "🌐 ${item.keyword}" else "🌐 ${item.keyword}   (לחץ להסרה)"
                    textSize = 14f
                    setPadding(12, 16, 12, 16)
                    setTextColor(0xFF263238.toInt())
                    setOnClickListener {
                        if (prefs.isLockdownEnabled) {
                            Toast.makeText(this@MainActivity, "לא ניתן להסיר אתר במצב נעילה קבועה", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        lifecycleScope.launch {
                            db.blockedKeywordsDao().deleteById(item.id)
                            Toast.makeText(this@MainActivity, "האתר הוסר מהרשימה", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                container.addView(tv)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("רשימת אתרים חסומים (${cachedDomains.size})")
            .setView(scroll)
            .setPositiveButton("סגור", null)
            .show()
    }

    /**
     * Friendly Installed-Apps Picker:
     * Shows real app names (Hebrew/English) instead of forcing users to type technical package names!
     */
    private fun showInstalledAppsPickerDialog() {
        lifecycleScope.launch {
            val installedApps = withContext(Dispatchers.IO) {
                val pm = packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
                val seenPackages = mutableSetOf<String>()
                val result = mutableListOf<Pair<String, String>>() // (AppName, PackageName)
                for (info in resolveInfos) {
                    val pkg = info.activityInfo?.packageName ?: continue
                    if (pkg == packageName || seenPackages.contains(pkg)) continue
                    seenPackages.add(pkg)
                    val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                    result.add(label to pkg)
                }
                result.sortBy { it.first.lowercase() }
                result
            }

            if (installedApps.isEmpty()) {
                Toast.makeText(this@MainActivity, "לא נמצאו אפליקציות לבחירה", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val blockedSet = cachedBlockedApps.map { it.packageName }.toSet()
            val names = installedApps.map { it.first }.toTypedArray()
            val checkedItems = installedApps.map { blockedSet.contains(it.second) }.toBooleanArray()

            AlertDialog.Builder(this@MainActivity)
                .setTitle("בחר אפליקציות לחסימה")
                .setMultiChoiceItems(names, checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton("שמור") { _, _ ->
                    if (prefs.isLockdownEnabled) {
                        Toast.makeText(this@MainActivity, "לא ניתן לשנות אפליקציות חסומות במצב נעילה קבועה", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    lifecycleScope.launch(Dispatchers.IO) {
                        for (i in installedApps.indices) {
                            val (label, pkg) = installedApps[i]
                            if (checkedItems[i] && !blockedSet.contains(pkg)) {
                                db.blockedAppsDao().insert(BlockedAppEntity(packageName = pkg, appLabel = label))
                            } else if (!checkedItems[i] && blockedSet.contains(pkg)) {
                                db.blockedAppsDao().deleteByPackage(pkg)
                            }
                        }
                    }
                    Toast.makeText(this@MainActivity, "רשימת האפליקציות החסומות עודכנה ✅", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("ביטול", null)
                .show()
        }
    }

    private fun renderBlockedAppsList(apps: List<BlockedAppEntity>) {
        binding.containerBlockedApps.removeAllViews()
        if (apps.isEmpty()) {
            val tv = TextView(this).apply {
                text = "אין אפליקציות חסומות כרגע."
                textSize = 13f
                setTextColor(0xFF78909C.toInt())
            }
            binding.containerBlockedApps.addView(tv)
            return
        }

        val locked = prefs.isLockdownEnabled
        for (app in apps) {
            val tv = TextView(this).apply {
                text = if (locked) "🚫 ${app.appLabel}" else "🚫 ${app.appLabel}  (לחץ להסרה)"
                textSize = 14f
                setPadding(12, 12, 12, 12)
                setTextColor(0xFFC62828.toInt())
                setOnClickListener {
                    if (prefs.isLockdownEnabled) {
                        Toast.makeText(this@MainActivity, "לא ניתן להסיר אפליקציה במצב נעילה קבועה", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    lifecycleScope.launch {
                        db.blockedAppsDao().deleteByPackage(app.packageName)
                    }
                }
            }
            binding.containerBlockedApps.addView(tv)
        }
    }

    private fun setupCard3LogsAndLockdown() {
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
                targetUrlOrApp = "דיווח מלוח הבקרה",
                blockReason = "דיווח על חסימה שגויה",
                pageTitle = "",
                textSnippet = ""
            )
        }

        lifecycleScope.launch {
            db.blockLogsDao().observeRecentLogs().collectLatest { logs ->
                renderBlockLogs(logs)
            }
        }

        binding.btnToggleLockdown.setOnClickListener {
            if (!prefs.isLockdownEnabled) {
                showEnableLockdownConfirmationDialog()
            } else {
                handleDisableLockdownFlow()
            }
        }
    }

    private fun updateLogsToggleLabel() {
        binding.btnToggleLogs.text = if (isLogsExpanded) {
            "🙈 הסתר יומן חסימות ($cachedLogsCount אירועים)"
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
                text = "[$timeStr]\nמקור: ${log.targetAppOrUrl}\nסיבה: ${log.triggerReason}"
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

    private fun showEnableLockdownConfirmationDialog() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "יש להפעיל תחילה את שירות ההגנה בנגישות", Toast.LENGTH_LONG).show()
            return
        }

        val adminNote = if (lockdownManager.isAdminActive()) {
            "הרשאת מנהל מכשיר כבר פעילה ✅\n\n"
        } else {
            "בלחיצה על אישור יפתח מסך לאישור 'מנהל מכשיר' – זה מה שמונע מחיקה של האפליקציה מהמסך הראשי.\n\n"
        }

        AlertDialog.Builder(this)
            .setTitle("הפעלת מצב נעילה קבועה ומניעת הסרה")
            .setMessage(
                adminNote +
                    "במצב נעילה קבועה:\n" +
                    "• לא ניתן להסיר את האפליקציה מהמסך הראשי\n" +
                    "• מסכי העקיפה (DNS פרטי, מנהל מכשיר, נגישות, פרטי אפליקציה) נעולים\n" +
                    "• שחרור הנעילה ידרוש המתנה של 45 דקות והקלדה ידנית של פרק מ\"א בתניא."
            )
            .setPositiveButton("כן, הפעל נעילה קבועה") { _, _ ->
                if (!lockdownManager.isAdminActive()) {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, lockdownManager.getAdminComponent())
                        putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "הרשאת מנהל מכשיר מונעת מחיקה והסרה של מגן הקודש מהמסך הראשי."
                        )
                    }
                    deviceAdminLauncher.launch(intent)
                } else {
                    lockdownManager.enableProductionLockdown()
                    refreshStatusUi()
                    Toast.makeText(this, "🔒 מצב נעילה קבועה הופעל!", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun handleDisableLockdownFlow() {
        val remainingMs = lockdownManager.getRemainingCooldownMs()
        if (remainingMs == -1L) {
            AlertDialog.Builder(this)
                .setTitle("בקשה לשחרור נעילה")
                .setMessage("כדי לשחרר את הנעילה הקבועה, יש להפעיל תחילה טיימר המתנה של 45 דקות.")
                .setPositiveButton("התחל ספירה של 45 דקות") { _, _ ->
                    lockdownManager.startUnlockCooldown()
                    refreshStatusUi()
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
                .setMessage("נותרו עוד $minutesLeft דקות לסיום זמן ההמתנה לפני הקלדת אתגר התניא.")
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
                    refreshStatusUi()
                    Toast.makeText(this, "מצב הנעילה כובה. חזרת למצב ניסיון.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "הטקסט אינו תואם. נסה שוב במדויק.", Toast.LENGTH_LONG).show()
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

    private fun refreshStatusUi() {
        val isServiceEnabled = isAccessibilityServiceEnabled()

        if (isWizardActive && currentWizardStep == 1 && isServiceEnabled && !wasServiceEnabledLastCheck) {
            if (prefs.isSetupWizardCompleted && getConfiguredPrivateDnsHost() != null) {
                isWizardActive = false
                Toast.makeText(this, "שירות ההגנה הופעל מחדש! ההגדרות שלך שמורות ✅", Toast.LENGTH_LONG).show()
            } else {
                currentWizardStep = 2
                Toast.makeText(this, "מעולה! שירות ההגנה הופעל ✅", Toast.LENGTH_LONG).show()
            }
            renderWizardVisibilityAndStep()
        }
        wasServiceEnabledLastCheck = isServiceEnabled

        if (isServiceEnabled) {
            binding.tvAccessibilityStatus.text = "שירות ההגנה (מגן הקודש): פעיל ✅"
            binding.tvAccessibilityStatus.setTextColor(0xFF2E7D32.toInt())
            binding.btnOpenAccessibility.visibility = View.GONE
            binding.tvWizardStep1Status.text = "סטטוס נוכחי: שירות ההגנה פעיל ✅"
            binding.tvWizardStep1Status.setTextColor(0xFF2E7D32.toInt())
            binding.tvWizardStep1Status.setBackgroundColor(0xFFE8F5E9.toInt())
        } else {
            binding.tvAccessibilityStatus.text = "שירות ההגנה (מגן הקודש): כבוי ⚠️"
            binding.tvAccessibilityStatus.setTextColor(0xFFC62828.toInt())
            binding.btnOpenAccessibility.visibility = View.VISIBLE
            binding.tvWizardStep1Status.text = "סטטוס נוכחי: שירות ההגנה כבוי ⏳"
            binding.tvWizardStep1Status.setTextColor(0xFFC62828.toInt())
            binding.tvWizardStep1Status.setBackgroundColor(0xFFFFEBEE.toInt())
        }

        val dnsHost = getConfiguredPrivateDnsHost()
        if (dnsHost != null) {
            binding.tvPrivateDnsStatus.text = "סינון רשת נקי (DNS פרטי): פעיל ($dnsHost) ✅"
            binding.tvPrivateDnsStatus.setTextColor(0xFF2E7D32.toInt())
            binding.tvWizardStep2Status.text = "סטטוס DNS פרטי: מוגדר ($dnsHost) ✅"
            binding.tvWizardStep2Status.setTextColor(0xFF2E7D32.toInt())
            binding.tvWizardStep2Status.setBackgroundColor(0xFFE8F5E9.toInt())
        } else {
            binding.tvPrivateDnsStatus.text = "סינון רשת נקי (DNS פרטי): לא מוגדר ⚠️"
            binding.tvPrivateDnsStatus.setTextColor(0xFFC62828.toInt())
            binding.tvWizardStep2Status.text = "סטטוס DNS פרטי: ממתין להגדרה ⏳"
            binding.tvWizardStep2Status.setTextColor(0xFF546E7A.toInt())
            binding.tvWizardStep2Status.setBackgroundColor(0xFFF5F7FA.toInt())
        }

        binding.switchLockPrivateDns.isChecked = prefs.lockPrivateDnsSettings
        binding.switchLockPrivateDns.isEnabled = !prefs.isLockdownEnabled

        if (!prefs.isLockdownEnabled) {
            binding.switchPauseFilterTesting.visibility = View.VISIBLE
            binding.tvModeBanner.text = "מצב פעולה: מצב ניסיון (ניתן לעריכה והסרה בחופשיות)"
            binding.tvLockdownStatus.text = "כבוי כעת (מצב ניסיון – ניתן לשנות הגדרות או להסיר בחופשיות)"
            binding.btnToggleLockdown.text = "🔒 הפעל מצב נעילה קבועה"
        } else {
            binding.switchPauseFilterTesting.visibility = View.GONE
            binding.tvModeBanner.text = "מצב פעולה: נעילה קבועה פעילה 🔒"
            val rem = lockdownManager.getRemainingCooldownMs()
            val remText = when {
                rem > 0L -> " (ספירה לאחור לשחרור: ${(rem / 60000L) + 1} דק')"
                rem == 0L -> " (זמן ההמתנה הסתיים – לחץ להקלדת אתגר התניא)"
                else -> ""
            }
            val adminStatusText = when {
                lockdownManager.isDeviceOwner() -> " | בעלות מלאה פעילה"
                lockdownManager.isAdminActive() -> " | מניעת הסרה פעילה 🛡️"
                else -> ""
            }
            binding.tvLockdownStatus.text = "מצב נעילה קבועה פעיל 🔒$adminStatusText$remText"
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
        private const val CLOUDFLARE_FAMILY_DNS = "family.cloudflare-dns.com"
        private const val ADGUARD_FAMILY_DNS = "family.adguard-dns.com"
    }
}
