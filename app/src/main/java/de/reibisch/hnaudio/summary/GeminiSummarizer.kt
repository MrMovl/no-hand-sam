package de.reibisch.hnaudio.summary

import android.util.Log
import de.reibisch.hnaudio.data.fetch
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gemini `generateContent` over REST. The key goes in the `x-goog-api-key` header rather than
 * the URL so it can't leak through logged URLs or exception messages.
 */
class GeminiSummarizer(
    http: OkHttpClient,
    private val apiKey: suspend () -> String?,
    private val model: suspend () -> String,
    private val usage: UsageLog,
) : Summarizer {
    private val http = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()

    override suspend fun summarize(input: SummaryInput): SummaryResult {
        val key = apiKey() ?: throw SummaryException("No Gemini API key set")
        val model = model()
        val body = json.encodeToString(
            GeminiRequest(
                systemInstruction = Content(listOf(Part(SummaryPrompt.system))),
                contents = listOf(Content(listOf(Part(SummaryPrompt.user(input))), role = "user")),
                generationConfig = GenerationConfig(temperature = 0.4, maxOutputTokens = 1024),
            ),
        )
        val request = Request.Builder()
            .url("$BASE_URL/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        var attempt = 0
        while (true) {
            val (code, text) = try {
                http.fetch(request) { r -> r.code to r.body.string() }
            } catch (e: IOException) {
                throw SummaryException("Network error talking to Gemini: ${e.message}", e)
            }
            if (code == 200) {
                val result = parseResponse(text, model)
                usage.add(result.inputTokens, result.outputTokens)
                return result
            }
            val retryAfter = retryDelaySeconds(text)
            if ((code == 429 || code == 503) && attempt < MAX_RETRIES && retryAfter <= MAX_WAIT_SECONDS) {
                attempt++
                Log.i(TAG, "Gemini HTTP $code, retrying in ${retryAfter}s (attempt $attempt)")
                delay(retryAfter * 1000L)
                continue
            }
            throw SummaryException(describeError(code, text))
        }
    }

    companion object {
        private const val TAG = "Summarizer"
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val MAX_RETRIES = 2
        private const val MAX_WAIT_SECONDS = 60

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun parseResponse(body: String, model: String): SummaryResult {
            val response = json.decodeFromString<GeminiResponse>(body)
            response.promptFeedback?.blockReason?.let { throw SummaryException("Gemini blocked the prompt: $it") }
            val candidate = response.candidates.firstOrNull()
                ?: throw SummaryException("Gemini returned no answer")
            val text = candidate.content?.parts.orEmpty()
                .filter { it.thought != true }
                .mapNotNull { it.text }
                .joinToString("")
                .trim()
            if (text.isEmpty()) {
                throw SummaryException("Gemini returned an empty answer (${candidate.finishReason})")
            }
            return SummaryResult(
                text = text,
                model = model,
                inputTokens = response.usageMetadata?.promptTokenCount ?: 0,
                outputTokens = (response.usageMetadata?.candidatesTokenCount ?: 0) +
                    (response.usageMetadata?.thoughtsTokenCount ?: 0),
            )
        }

        /** Seconds from a `google.rpc.RetryInfo` detail, or a small default. */
        fun retryDelaySeconds(errorBody: String): Int =
            Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").find(errorBody)
                ?.groupValues?.get(1)?.toIntOrNull()?.plus(1)
                ?: 5

        fun describeError(code: Int, body: String): String {
            val message = runCatching { json.decodeFromString<GeminiError>(body).error.message }.getOrNull()
            return when {
                code == 400 && message?.contains("API key", ignoreCase = true) == true -> "Gemini API key is invalid"
                code == 403 -> "Gemini API key not allowed: ${message ?: "HTTP 403"}"
                code == 404 -> "Unknown Gemini model: ${message ?: "HTTP 404"}"
                code == 429 -> "Gemini free-tier quota used up, try again later"
                else -> "Gemini error HTTP $code: ${message ?: body.take(200)}"
            }
        }
    }
}

@Serializable
private data class GeminiRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null,
    val generationConfig: GenerationConfig? = null,
)

@Serializable
private data class Content(val parts: List<Part> = emptyList(), val role: String? = null)

@Serializable
private data class Part(val text: String? = null, val thought: Boolean? = null)

@Serializable
private data class GenerationConfig(val temperature: Double? = null, val maxOutputTokens: Int? = null)

@Serializable
private data class GeminiResponse(
    val candidates: List<Candidate> = emptyList(),
    val usageMetadata: UsageMetadata? = null,
    val promptFeedback: PromptFeedback? = null,
)

@Serializable
private data class Candidate(val content: Content? = null, val finishReason: String? = null)

@Serializable
private data class UsageMetadata(
    val promptTokenCount: Int? = null,
    val candidatesTokenCount: Int? = null,
    val thoughtsTokenCount: Int? = null,
)

@Serializable
private data class PromptFeedback(val blockReason: String? = null)

@Serializable
private data class GeminiError(val error: ErrorBody)

@Serializable
private data class ErrorBody(val code: Int? = null, val message: String? = null, val status: String? = null)
