package com.pureguard.filter.data

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads the open-source Université Toulouse Capitole (UT1) `lingerie` domain list
 * (`assets/ut1_lingerie_domains.txt`) plus dedicated Israeli/global lingerie stores into a fast HashSet.
 *
 * Active in Strict ("שמירת עיניים מלאה") and Custom profiles (0ms lookup, 0% battery).
 * Bypassed automatically in Level 2 ("מותאם לנשים - קניות ואופנה פתוחים").
 */
class Ut1LingerieDomainFilter(context: Context) {

    private val domains: Set<String>

    init {
        val loaded = HashSet<String>(256)
        loaded.addAll(EXTRA_LINGERIE_DOMAINS)
        try {
            context.assets.open(ASSET_FILE).use { stream ->
                BufferedReader(InputStreamReader(stream)).useLines { lines ->
                    lines.forEach { raw ->
                        val cleaned = raw.trim().lowercase()
                        if (cleaned.isNotEmpty() && !cleaned.startsWith("#") && cleaned.contains(".")) {
                            loaded.add(cleaned)
                        }
                    }
                }
            }
            Log.i(TAG, "Loaded ${loaded.size} UT1 lingerie/swimwear domains into RAM.")
        } catch (e: Exception) {
            Log.w(TAG, "Could not load $ASSET_FILE, using built-in set (${loaded.size}): ${e.message}")
        }
        domains = loaded
    }

    fun findBlockedDomain(rawUrl: String): String? {
        if (rawUrl.isBlank()) return null
        val host = extractHost(rawUrl) ?: return null
        if (domains.contains(host)) return host

        // Check parent domain suffix (e.g. il.victoriassecret.com -> victoriassecret.com)
        var dotIdx = host.indexOf('.')
        while (dotIdx != -1 && dotIdx + 1 < host.length) {
            val parent = host.substring(dotIdx + 1)
            if (domains.contains(parent)) return parent
            dotIdx = host.indexOf('.', dotIdx + 1)
        }
        return null
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

    companion object {
        private const val TAG = "Ut1LingerieFilter"
        private const val ASSET_FILE = "ut1_lingerie_domains.txt"

        private val EXTRA_LINGERIE_DOMAINS = setOf(
            "frolov.fr",
            "victoriassecret.com",
            "jack-kuba.co.il",
            "intima-il.co.il",
            "femina.co.il",
            "intimissimi.com",
            "calzedonia.com",
            "agentprovocateur.com",
            "savagex.com",
            "honeybirdette.com",
            "loungeunderwear.com",
            "triumph.com",
            "blushlingerie.com",
            "gooseberryintimates.com",
            "bluebella.com",
            "forloveandlemons.com"
        )
    }
}
