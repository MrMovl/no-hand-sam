package de.reibisch.hnaudio.data

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.net.URI

/** Raw item as returned by the HN Firebase API. */
@Serializable
data class HnItem(
    val id: Long,
    val type: String? = null,
    val by: String? = null,
    val time: Long? = null,
    val title: String? = null,
    val url: String? = null,
    val score: Int = 0,
    val descendants: Int = 0,
    val text: String? = null,
    val kids: List<Long> = emptyList(),
    val dead: Boolean = false,
    val deleted: Boolean = false,
)

data class Story(
    val id: Long,
    val title: String,
    val url: String?,
    val score: Int,
    val commentCount: Int,
    /** Plain-text paragraphs of the post body (Ask/Show HN), empty for link posts. */
    val text: List<String>,
    val commentIds: List<Long>,
    val type: String,
) {
    val domain: String? get() = url?.let(::domainOf)
    val hnUrl: String get() = "https://news.ycombinator.com/item?id=$id"
    val isAskOrShow: Boolean get() = ASK_SHOW.any { title.startsWith(it) }
}

private val ASK_SHOW = listOf("Ask HN", "Show HN", "Tell HN")

fun HnItem.toStory(): Story? {
    if (dead || deleted || title == null) return null
    return Story(
        id = id,
        title = title,
        url = url?.takeIf { it.isNotBlank() },
        score = score,
        commentCount = descendants,
        text = text?.let(::hnHtmlToParagraphs).orEmpty(),
        commentIds = kids,
        type = type ?: "story",
    )
}

fun domainOf(url: String): String? =
    runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()

/** HN separates paragraphs with bare `<p>` tags and escapes everything else as HTML. */
fun hnHtmlToParagraphs(html: String): List<String> =
    html.split(Regex("(?i)<p>"))
        .map { Jsoup.parse(it).text().trim() }
        .filter { it.isNotEmpty() }
