package de.reibisch.hnaudio.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GeminiSummarizerTest {
    @Test
    fun `parses text and usage, skipping thought parts`() {
        val body = """
            {"candidates":[{"content":{"role":"model","parts":[
                {"text":"thinking...","thought":true},
                {"text":"A new tunnel service. "},{"text":"People like it."}
             ]},"finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":1200,"candidatesTokenCount":60,"thoughtsTokenCount":20,"totalTokenCount":1280},
             "modelVersion":"x"}
        """.trimIndent()
        val result = GeminiSummarizer.parseResponse(body, "m")
        assertEquals("A new tunnel service. People like it.", result.text)
        assertEquals(1200, result.inputTokens)
        assertEquals(80, result.outputTokens)
    }

    @Test
    fun `empty or blocked answers are errors`() {
        assertThrows(SummaryException::class.java) {
            GeminiSummarizer.parseResponse("""{"candidates":[{"finishReason":"SAFETY"}]}""", "m")
        }
        assertThrows(SummaryException::class.java) {
            GeminiSummarizer.parseResponse("""{"promptFeedback":{"blockReason":"OTHER"}}""", "m")
        }
    }

    @Test
    fun `retry delay is read from RetryInfo`() {
        val body = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}"""
        assertEquals(38, GeminiSummarizer.retryDelaySeconds(body))
        assertEquals(13, GeminiSummarizer.retryDelaySeconds("""{"retryDelay": "12.5s"}"""))
        assertEquals(5, GeminiSummarizer.retryDelaySeconds("{}"))
    }

    @Test
    fun `invalid key gets a readable message`() {
        val body = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        assertEquals("Gemini API key is invalid", GeminiSummarizer.describeError(400, body))
    }
}
