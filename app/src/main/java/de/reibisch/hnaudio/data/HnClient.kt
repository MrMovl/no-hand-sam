package de.reibisch.hnaudio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class HnClient(
    private val http: OkHttpClient,
    private val baseUrl: String = "https://hacker-news.firebaseio.com/v0",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun topStoryIds(): List<Long> = json.decodeFromString(get("$baseUrl/topstories.json"))

    /** Returns null for missing items (the API answers `null`). */
    suspend fun item(id: Long): HnItem? {
        val body = get("$baseUrl/item/$id.json")
        return if (body.trim() == "null") null else json.decodeFromString<HnItem>(body)
    }

    /**
     * Front page stories in rank order. Items that fail to load or are dead/deleted are
     * dropped rather than failing the whole list.
     */
    suspend fun topStories(limit: Int, concurrency: Int = 8): List<Story> = coroutineScope {
        val permits = Semaphore(concurrency)
        topStoryIds().take(limit)
            .map { id -> async { permits.withPermit { itemOrNull(id) } } }
            .awaitAll()
            .mapNotNull { it?.toStory() }
    }

    /** Plain text of the first [limit] live top-level comments, in HN's ranking order. */
    suspend fun comments(ids: List<Long>, limit: Int, concurrency: Int = 5): List<String> = coroutineScope {
        val permits = Semaphore(concurrency)
        ids.take(limit * 2) // some will be dead or deleted
            .map { id -> async { permits.withPermit { itemOrNull(id) } } }
            .awaitAll()
            .filterNotNull()
            .filter { !it.dead && !it.deleted && !it.text.isNullOrBlank() }
            .take(limit)
            .map { hnHtmlToParagraphs(it.text!!).joinToString(" ") }
    }

    private suspend fun itemOrNull(id: Long): HnItem? = try {
        item(id)
    } catch (e: IOException) {
        null
    } catch (e: SerializationException) {
        null
    }

    private suspend fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        return http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            withContext(Dispatchers.IO) { response.body.string() }
        }
    }
}
