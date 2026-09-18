package de.reibisch.hnaudio.summary

import de.reibisch.hnaudio.data.Extraction
import de.reibisch.hnaudio.data.Story

data class SummaryInput(
    val story: Story,
    val extraction: Extraction,
    /** Plain-text top-level comments; only filled when the article couldn't be extracted. */
    val comments: List<String> = emptyList(),
)

data class SummaryResult(
    val text: String,
    val model: String,
    val inputTokens: Int,
    val outputTokens: Int,
)

interface Summarizer {
    suspend fun summarize(input: SummaryInput): SummaryResult
}

class SummaryException(message: String, cause: Throwable? = null) : Exception(message, cause)
