package de.reibisch.hnaudio.summary

import de.reibisch.hnaudio.data.Extraction

/** Provider-independent prompt, so every [Summarizer] produces the same kind of text. */
object SummaryPrompt {
    /** Bump when the prompt changes so cached summaries are regenerated. */
    const val VERSION = 2

    /** Roughly 6k tokens of English text. */
    const val MAX_ARTICLE_CHARS = 24_000
    const val MAX_COMMENT_CHARS = 1_500

    val system = """
        You write short spoken summaries of Hacker News stories for someone who is listening
        while doing chores and cannot look at a screen.

        Write two to four sentences of plain prose, always in English. Say what the thing
        actually is and why it is interesting to a technical audience. The title is read out
        right before your summary, so don't repeat it and don't start with "This article".

        Only describe what commenters or Hacker News readers think if you were actually given
        comments. Never guess or invent the discussion.

        Your text is read aloud by a speech engine, so: no markdown, no lists, no URLs, no emoji,
        no parentheses. Spell out symbols and abbreviations a speech engine would stumble over,
        for example "C plus plus" instead of "C++", "versus" instead of "vs.", "for example"
        instead of "e.g.".

        If you are given the post text and comments instead of the article, say briefly that
        the article itself couldn't be loaded, then summarize what the post and the commenters
        say.

        Output only the summary.
    """.trimIndent()

    fun user(input: SummaryInput): String = buildString {
        val story = input.story
        appendLine("Title: ${story.title}")
        story.domain?.let { appendLine("Site: $it") }
        appendLine("Points: ${story.score}, comments: ${story.commentCount}")
        appendLine()
        when (val e = input.extraction) {
            is Extraction.Article -> {
                appendLine(if (e.source == Extraction.Source.GitHubReadme) "README:" else "Article text:")
                appendLine(truncate(e.paragraphs.joinToString("\n\n"), MAX_ARTICLE_CHARS))
            }
            is Extraction.Failed -> {
                if (story.url != null) {
                    appendLine("The article could not be loaded (${e.reason.description}).")
                }
                if (story.text.isNotEmpty()) {
                    appendLine()
                    appendLine("Post text:")
                    appendLine(truncate(story.text.joinToString("\n\n"), MAX_ARTICLE_CHARS))
                }
                if (input.comments.isNotEmpty()) {
                    appendLine()
                    appendLine("Top comments:")
                    input.comments.forEachIndexed { i, c ->
                        appendLine("${i + 1}. ${truncate(c, MAX_COMMENT_CHARS)}")
                    }
                }
            }
        }
    }.trim()

    fun truncate(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
        return text.substring(0, cut) + " [truncated]"
    }
}
