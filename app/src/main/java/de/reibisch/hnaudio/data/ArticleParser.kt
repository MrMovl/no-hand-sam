package de.reibisch.hnaudio.data

import net.dankito.readability4j.extended.Readability4JExtended
import org.jsoup.nodes.Element
import java.net.URI

/** Pure HTML -> text logic, kept free of networking so it can be tested with fixtures. */
object ArticleParser {
    const val MIN_CHARS = 500

    private const val BLOCKS = "p, h1, h2, h3, h4, h5, h6, li, blockquote, figcaption, dd, dt"
    private val VIDEO_HOSTS = setOf(
        "youtube.com", "m.youtube.com", "youtu.be", "vimeo.com", "twitch.tv",
        "tiktok.com", "dailymotion.com",
    )

    /** Failure known from the URL alone, without fetching it. */
    fun classifyUrl(url: String): FailureReason? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        return when {
            host in VIDEO_HOSTS -> FailureReason.Video
            uri.path.orEmpty().lowercase().endsWith(".pdf") -> FailureReason.Pdf
            else -> null
        }
    }

    /** `owner` and `repo` if the URL points at a repository root, not an issue or file. */
    fun githubRepo(url: String): Pair<String, String>? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host?.lowercase()?.removePrefix("www.") != "github.com") return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (segments.size != 2) return null
        return segments[0] to segments[1].removeSuffix(".git")
    }

    fun parse(url: String, html: String): Extraction {
        val article = Readability4JExtended(url, html).parse()
        val structured = article.articleContent?.let(::paragraphsFrom).orEmpty()
        val plain = article.textContent.orEmpty()
        // Readability keeps text that isn't wrapped in block elements; if the structured
        // pass lost most of it, fall back to splitting the plain text.
        val paragraphs = if (structured.sumOf { it.length } >= plain.trim().length / 2) {
            structured
        } else {
            splitPlainText(plain)
        }
        val chars = paragraphs.sumOf { it.length }
        if (chars < MIN_CHARS) return Extraction.Failed(FailureReason.TooShort, "$chars chars")
        return Extraction.Article(article.title?.trim().orEmpty(), paragraphs, Extraction.Source.Web)
    }

    fun parseReadme(repo: String, readmeHtml: String): Extraction {
        val body = org.jsoup.Jsoup.parse(readmeHtml).body()
        val paragraphs = paragraphsFrom(body)
        if (paragraphs.isEmpty()) return Extraction.Failed(FailureReason.TooShort, "empty README")
        return Extraction.Article(repo, paragraphs, Extraction.Source.GitHubReadme)
    }

    /** Text of the innermost block elements, in document order. Code blocks are skipped. */
    fun paragraphsFrom(root: Element): List<String> =
        root.select(BLOCKS)
            .filter { it.select(BLOCKS).size == 1 } // only itself: no nested blocks
            .filter { el -> el.parents().none { it.tagName() == "pre" } }
            .map { it.text().trim() }
            .filter { it.length > 1 }

    private fun splitPlainText(text: String): List<String> =
        text.split(Regex("\\n\\s*\\n"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length > 1 }
}
