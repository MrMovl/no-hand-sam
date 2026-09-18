package de.reibisch.hnaudio.summary

import de.reibisch.hnaudio.data.Extraction
import de.reibisch.hnaudio.data.FailureReason
import de.reibisch.hnaudio.data.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryPromptTest {
    private val story = Story(
        id = 1, title = "A title", url = "https://www.example.com/post", score = 412,
        commentCount = 230, text = emptyList(), commentIds = emptyList(), type = "story",
    )

    @Test
    fun `article prompt contains metadata and text`() {
        val prompt = SummaryPrompt.user(
            SummaryInput(story, Extraction.Article("A title", listOf("First.", "Second."), Extraction.Source.Web)),
        )
        assertTrue(prompt.contains("Title: A title"))
        assertTrue(prompt.contains("Site: example.com"))
        assertTrue(prompt.contains("Points: 412, comments: 230"))
        assertTrue(prompt.contains("Article text:\nFirst.\n\nSecond."))
    }

    @Test
    fun `failed extraction falls back to post text and comments`() {
        val prompt = SummaryPrompt.user(
            SummaryInput(
                story.copy(text = listOf("Body of the post.")),
                Extraction.Failed(FailureReason.Blocked, "HTTP 403"),
                comments = listOf("Great point.", "I disagree."),
            ),
        )
        assertTrue(prompt.contains("could not be loaded"))
        assertTrue(prompt.contains("Post text:\nBody of the post."))
        assertTrue(prompt.contains("1. Great point.\n2. I disagree."))
        assertFalse(prompt.contains("Article text:"))
    }

    @Test
    fun `text post does not claim an article failed to load`() {
        val prompt = SummaryPrompt.user(
            SummaryInput(story.copy(url = null, text = listOf("Ask HN body")), Extraction.Failed(FailureReason.NoUrl)),
        )
        assertFalse(prompt.contains("could not be loaded"))
        assertFalse(prompt.contains("Site:"))
    }

    @Test
    fun `long text is truncated at a word boundary`() {
        val text = "word ".repeat(10_000)
        val cut = SummaryPrompt.truncate(text, 1000)
        assertTrue(cut.length <= 1000 + " [truncated]".length)
        assertTrue(cut.endsWith("word [truncated]"))
        assertEquals("short", SummaryPrompt.truncate("short", 1000))
    }
}
