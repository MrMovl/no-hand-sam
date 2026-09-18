package de.reibisch.hnaudio.summary

import android.util.Log
import de.reibisch.hnaudio.data.Extraction
import de.reibisch.hnaudio.data.HnClient
import de.reibisch.hnaudio.data.Story

/** Cached summary lookup plus the comments fallback for stories without a readable article. */
class StorySummaries(
    private val hn: HnClient,
    private val summarizer: Summarizer,
    private val cache: SummaryCache,
) {
    suspend fun summaryFor(story: Story, extraction: Extraction): CachedSummary {
        cache.get(story.id)?.let { return it }
        val comments = if (extraction is Extraction.Failed) hn.comments(story.commentIds, limit = 5) else emptyList()
        val result = summarizer.summarize(SummaryInput(story, extraction, comments))
        val summary = CachedSummary(
            storyId = story.id,
            text = result.text,
            fromArticle = extraction is Extraction.Article,
            model = result.model,
            createdAt = System.currentTimeMillis(),
            promptVersion = SummaryPrompt.VERSION,
        )
        cache.put(summary)
        Log.i(TAG, "${story.title}: ${summary.text}")
        return summary
    }

    private companion object {
        const val TAG = "Summary"
    }
}
