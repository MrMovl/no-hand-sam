package de.reibisch.hnaudio.summary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CachedSummary(
    val storyId: Long,
    val text: String,
    val fromArticle: Boolean,
    val model: String,
    val createdAt: Long,
    val promptVersion: Int = 1,
)

/** One small JSON file per story id. */
class SummaryCache(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(storyId: Long): CachedSummary? = withContext(Dispatchers.IO) {
        val file = fileFor(storyId)
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString<CachedSummary>(file.readText()) }.getOrNull()
            ?.takeIf { it.promptVersion == SummaryPrompt.VERSION }
    }

    suspend fun put(summary: CachedSummary) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "${summary.storyId}.json.tmp")
        tmp.writeText(json.encodeToString(summary))
        tmp.renameTo(fileFor(summary.storyId))
    }

    private fun fileFor(storyId: Long) = File(dir, "$storyId.json")
}
