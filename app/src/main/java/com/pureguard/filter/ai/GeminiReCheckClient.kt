package com.pureguard.filter.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GeminiEvaluationResult(
    val isSafe: Boolean,
    val explanationHe: String,
    val error: String? = null
)

class GeminiReCheckClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun evaluateContext(
        apiKey: String,
        pageTitle: String,
        url: String,
        textSnippet: String,
        triggerReason: String
    ): GeminiEvaluationResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext GeminiEvaluationResult(
                isSafe = false,
                explanationHe = "לא הוגדר מפתח Gemini API בהגדרות האפליקציה.",
                error = "MISSING_API_KEY"
            )
        }

        try {
            val prompt = """
                You are a strict but fair content-filter false-positive evaluator for PureGuard.
                A web page was blocked due to: "$triggerReason".
                Evaluate whether the context below is LEGITIMATE (such as medical/health information, legitimate news, Torah/Halacha study, software/technical documentation, or general benign content) OR INAPPROPRIATE (pornography, explicit sexual content, adult dating/hookups, erotica).

                Page URL: $url
                Page Title: $pageTitle
                Visible Text Snippet: ${textSnippet.take(1200)}

                Respond ONLY in valid JSON with two fields:
                {"safe": true or false, "reason_he": "הסבר קצר בעברית מדוע התוכן בטוח או לא ראוי"}
            """.trimIndent()

            val payload = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(
                        JSONObject().put("text", prompt)
                    ))
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("responseMimeType", "application/json")
                })
            }

            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
            val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(endpoint)
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext GeminiEvaluationResult(
                        isSafe = false,
                        explanationHe = "שגיאת שרת (${response.code}) בעת פנייה ל-Gemini API.",
                        error = bodyStr
                    )
                }

                val root = JSONObject(bodyStr)
                val candidates = root.optJSONArray("candidates")
                val rawText = candidates
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .orEmpty()
                    .trim()

                val cleanedJson = rawText
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                val resultObj = JSONObject(cleanedJson)
                val isSafe = resultObj.optBoolean("safe", false)
                val reasonHe = resultObj.optString("reason_he", if (isSafe) "התוכן נמצא תקין ובטוח." else "התוכן זוהה כלא ראוי.")

                GeminiEvaluationResult(
                    isSafe = isSafe,
                    explanationHe = reasonHe
                )
            }
        } catch (e: Exception) {
            GeminiEvaluationResult(
                isSafe = false,
                explanationHe = "שגיאה בבדיקת ה-AI: ${e.localizedMessage ?: "שגיאת רשת"}",
                error = e.message
            )
        }
    }
}
