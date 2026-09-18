package de.reibisch.hnaudio.data

sealed interface Extraction {
    data class Article(
        val title: String,
        val paragraphs: List<String>,
        val source: Source,
    ) : Extraction {
        val charCount: Int get() = paragraphs.sumOf { it.length }
        val wordCount: Int get() = paragraphs.sumOf { p -> p.split(' ').count { it.isNotEmpty() } }
    }

    data class Failed(val reason: FailureReason, val detail: String? = null) : Extraction

    enum class Source { Web, GitHubReadme }
}

enum class FailureReason(val description: String) {
    NoUrl("text post, no link"),
    Pdf("PDF"),
    Video("video"),
    Blocked("access denied, probably a paywall or bot block"),
    TooShort("little or no text, probably a paywall or JavaScript-only page"),
    NotHtml("not a web page"),
    HttpError("server error"),
    Timeout("timed out"),
    Network("network error"),
}
