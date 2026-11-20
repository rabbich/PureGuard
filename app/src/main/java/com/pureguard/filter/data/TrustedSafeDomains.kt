package com.pureguard.filter.data

/**
 * Curated Trusted Kosher, Government, Banking, Health & Productivity Domains and Apps.
 * Pages on these domains skip Visual AI, OCR, and Keyword Scanning completely:
 * -> 0% battery overhead
 * -> 100% user privacy (banking, medical, emails, private docs never scanned)
 * -> Zero false positives
 */
object TrustedSafeDomains {

    private val TRUSTED_SUFFIXES = listOf(
        ".gov.il",
        ".muni.il",
        ".ac.il",
        ".edu"
    )

    private val TRUSTED_DOMAINS = setOf(
        // Torah, Judaism & Chabad (100% Kosher Editorial Standards)
        "chabad.org",
        "chabad.info",
        "col.org.il",
        "shturem.net",
        "anash.org",
        "sefaria.org",
        "sefaria.org.il",
        "hebrewbooks.org",
        "otzar.org",
        "daat.ac.il",
        "yeshiva.org.il",
        "hidabroot.org",
        "dirshu.co.il",
        "kolhalashon.com",
        "torahanytime.com",
        "din.org.il",
        "halachayomit.co.il",
        "pninei.org.il",
        "jdn.co.il",
        "kore.co.il",
        "emess.co.il",
        "93fm.co.il",
        "hm-news.co.il",
        "chabadpedia.co.il",
        "lahak.org",
        "kehot.com",
        "kehot.co.il",

        // Israeli Banking, Credit Cards & Payments (100% Privacy)
        "leumi.co.il",
        "bankhapoalim.co.il",
        "discountbank.co.il",
        "mizrahi-tefahot.co.il",
        "fibi.co.il",
        "yahav.co.il",
        "bankjerusalem.co.il",
        "onezero.co.il",
        "mercantile.co.il",
        "bankmassad.co.il",
        "otsar.co.il",
        "max.co.il",
        "isracard.co.il",
        "cal-online.co.il",
        "bitpay.co.il",
        "payboxapp.com",
        "paypal.com",
        "boi.org.il",

        // Israeli Health Funds (Kupot Holim) & Hospitals (100% Privacy)
        "clalit.co.il",
        "maccabi4u.co.il",
        "meuhedet.co.il",
        "leumit.co.il",
        "sheba.co.il",
        "hadassah.org.il",
        "tasmc.org.il",
        "rambam.org.il",
        "szmc.org.il",
        "assuta.co.il",
        "mdais.org",

        // Israeli Transit, Utilities & Essential Services
        "rail.co.il",
        "egged.co.il",
        "dan.co.il",
        "moovitapp.com",
        "ravpass.co.il",
        "ravkavonline.co.il",
        "iec.co.il",
        "bezeq.co.il",
        "partner.co.il",
        "cellcom.co.il",
        "pelephone.co.il",
        "israelpost.co.il",
        "waze.com",
        "shufersal.co.il",
        "rami-levy.co.il",
        "osherad.co.il",
        "zap.co.il",

        // Work, Email, Private Docs & Developer Tools
        "docs.google.com",
        "drive.google.com",
        "mail.google.com",
        "calendar.google.com",
        "sheets.google.com",
        "slides.google.com",
        "meet.google.com",
        "translate.google.com",
        "maps.google.com",
        "antigravity.google.com",
        "aistudio.google.com",
        "gemini.google.com",
        "github.com",
        "stackoverflow.com",
        "chatgpt.com",
        "claude.ai"
    )

    val SAFE_UTILITY_PACKAGES = setOf(
        // Messaging, Maps, System
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.waze",
        "com.tranzmate", // Moovit
        "com.android.settings",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.android.launcher3",
        "com.google.android.gm", // Gmail
        "com.google.android.calendar",
        "com.google.android.apps.maps",
        "com.google.android.keep",
        "com.google.android.deskclock",
        "com.google.android.calculator",
        "com.miui.calculator",
        "com.sec.android.app.popupcalculator",
        "com.android.contacts",
        "com.google.android.contacts",

        // Israeli Banks, Payments & Health Apps (100% Privacy)
        "com.bnhp.payments.app", // Bit
        "com.poalim.mobile",
        "com.leumi.leumiwallet",
        "il.co.leumi.android",
        "com.ideomobile.hapoalim",
        "com.discount.mobile",
        "com.mizrahi.mobile",
        "com.payboxapp",
        "il.co.isracard.isracard",
        "com.leumicard.lc", // Max
        "com.cal.calmobile",
        "il.co.clalit.android",
        "com.maccabi.maccabi",
        "il.co.meuhedet.app",
        "com.leumit.leumitapp"
    )

    fun isTrustedSafeUrl(rawUrl: String): Boolean {
        if (rawUrl.isBlank()) return false
        val host = extractHost(rawUrl) ?: return false

        for (suffix in TRUSTED_SUFFIXES) {
            if (host.endsWith(suffix)) return true
        }

        if (TRUSTED_DOMAINS.contains(host)) return true

        // Check subdomain matches (e.g. online.leumi.co.il -> matches leumi.co.il)
        for (domain in TRUSTED_DOMAINS) {
            if (host.endsWith(".$domain")) return true
        }
        return false
    }

    private fun extractHost(rawUrl: String): String? {
        val cleaned = rawUrl.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore("#")
            .substringBefore(":")
            .removePrefix("www.")
            .removePrefix("m.")
            .trim()
        return if (cleaned.contains(".")) cleaned else null
    }
}
