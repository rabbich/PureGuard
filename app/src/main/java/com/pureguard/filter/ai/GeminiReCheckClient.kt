package com.pureguard.filter.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class GeminiEvaluationResult(
    val isSafe: Boolean,
    val explanationHe: String,
    val error: String? = null
)

/**
 * Layer 3 False-Positive Re-Check Client.
 * Works 100% FREE with ZERO API KEY by default using:
 * 1. Free Pollinations OpenAI-compatible text endpoint (no key, no registration, $0 cost)
 * 2. Smart on-device contextual heuristic fallback (Torah/Medical/News/Technical verifier)
 * 3. Optional Gemini Flash API if user happens to enter an API key.
 */
class GeminiReCheckClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun evaluateContext(
        apiKey: String,
        pageTitle: String,
        url: String,
        textSnippet: String,
        triggerReason: String
    ): GeminiEvaluationResult = withContext(Dispatchers.IO) {
        // 1. If user provided a Gemini API key, use Gemini Flash first
        if (apiKey.isNotBlank()) {
            val geminiRes = callGeminiFlash(apiKey, pageTitle, url, textSnippet, triggerReason)
            if (geminiRes != null) return@withContext geminiRes
        }

        // 2. Otherwise use 100% FREE zero-key AI service (Pollinations Text AI)
        val freeAiRes = callFreeZeroKeyAi(pageTitle, url, textSnippet, triggerReason)
        if (freeAiRes != null) return@withContext freeAiRes

        // 3. Offline instant heuristic fallback (zero internet dependency)
        return@withContext evaluateLocalContextHeuristics(pageTitle, url, textSnippet, triggerReason)
    }

    private fun callFreeZeroKeyAi(
        pageTitle: String,
        url: String,
        textSnippet: String,
        triggerReason: String
    ): GeminiEvaluationResult? {
        return try {
            val prompt = """
                You are a strict family-safe filter verifier.
                A page was blocked for: "$triggerReason".
                URL: $url
                Title: $pageTitle
                Text: ${textSnippet.take(700)}
                Is this page strictly legitimate educational, medical, news, Torah/Halacha, or technical content (SAFE), or is it adult/immodest/sexual/kissing/bikini/porn content (UNSAFE)?
                Reply ONLY with valid JSON: {"safe": true or false, "reason_he": "הסבר קצר בעברית"}
            """.trimIndent()

            val encodedPrompt = URLEncoder.encode(prompt, "UTF-8")
            val request = Request.Builder()
                .url("https://text.pollinations.ai/$encodedPrompt?json=true")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.string().orEmpty().trim()
                val startIdx = bodyStr.indexOf('{')
                val endIdx = bodyStr.lastIndexOf('}')
                if (startIdx == -1 || endIdx <= startIdx) return null

                val jsonSlice = bodyStr.substring(startIdx, endIdx + 1)
                val obj = JSONObject(jsonSlice)
                val isSafe = obj.optBoolean("safe", false)
                val reasonHe = obj.optString(
                    "reason_he",
                    if (isSafe) "התוכן נבדק על ידי AI חינמי ונמצא בטוח (הקשר לימודי/חדשותי/רפואי)."
                    else "התוכן נבדק על ידי AI ונמצא לא ראוי."
                )
                GeminiEvaluationResult(isSafe = isSafe, explanationHe = reasonHe)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun evaluateLocalContextHeuristics(
        pageTitle: String,
        url: String,
        textSnippet: String,
        triggerReason: String
    ): GeminiEvaluationResult {
        val combined = "$url $pageTitle $textSnippet".lowercase()

        val hardBlockTerms = listOf(
            "pornhub", "xvideos", "xnxx", "onlyfans", "חוטיני", "נשיקה צרפתית",
            "מתנשקות", "french kiss", "thong", "bikini try on", "פורנו", "xxx"
        )
        for (term in hardBlockTerms) {
            if (combined.contains(term)) {
                return GeminiEvaluationResult(
                    isSafe = false,
                    explanationHe = "בדיקת הקשר: נמצא ביטוי מפורש לא ראוי ('$term') ולכן החסימה נשארת בעינה."
                )
            }
        }

        val legitimateIndicators = listOf(
            "רפואה", "בריאות", "בית חולים", "הלכה", "תלמוד", "תורה", "שו\"ת",
            "ויקיפדיה", "wikipedia", "חדשות", "medical", "health", "clinical", "documentation"
        )
        val legitMatches = legitimateIndicators.count { combined.contains(it) }
        return if (legitMatches >= 2) {
            GeminiEvaluationResult(
                isSafe = true,
                explanationHe = "בדיקת הקשר חינמית: זוהה תוכן לגיטימי (רפואי / תורני / אנציקלופדי)."
            )
        } else {
            GeminiEvaluationResult(
                isSafe = false,
                explanationHe = "בדיקת הקשר חינמית: לא נמצאה הצדקה רפואית/תורנית/לימודית לעקיפת החסימה."
            )
        }
    }

    private fun callGeminiFlash(
        apiKey: String,
        pageTitle: String,
        url: String,
        textSnippet: String,
        triggerReason: String
    ): GeminiEvaluationResult? {
        return try {
            val prompt = """
                You are a strict content-filter false-positive evaluator for PureGuard.
                A page was blocked due to: "$triggerReason".
                URL: $url | Title: $pageTitle | Text: ${textSnippet.take(1000)}
                Respond ONLY in JSON: {"safe": true or false, "reason_he": "הסבר קצר בעברית"}
            """.trimIndent()

            val payload = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                ))
            }

            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
            val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(endpoint).post(requestBody).build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.string().orEmpty()
                val root = JSONObject(bodyStr)
                val rawText = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .orEmpty()

                val startIdx = rawText.indexOf('{')
                val endIdx = rawText.lastIndexOf('}')
                if (startIdx == -1 || endIdx <= startIdx) return null
                val resultObj = JSONObject(rawText.substring(startIdx, endIdx + 1))
                val isSafe = resultObj.optBoolean("safe", false)
                val reasonHe = resultObj.optString("reason_he", "")
                GeminiEvaluationResult(isSafe = isSafe, explanationHe = reasonHe)
            }
        } catch (_: Exception) {
            null
        }
    }
}
