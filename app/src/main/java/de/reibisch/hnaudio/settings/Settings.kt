package de.reibisch.hnaudio.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.reibisch.hnaudio.summary.GeminiSummarizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "settings")

@Serializable
data class SavedStory(val id: Long, val title: String, val url: String?) {
    val hnUrl: String get() = "https://news.ycombinator.com/item?id=$id"
}

class Settings(context: Context) {
    private val store = context.applicationContext.dataStore
    private val box = SecretBox("gemini_api_key")
    private val json = Json { ignoreUnknownKeys = true }

    val hasApiKey: Flow<Boolean> = store.data.map { it[API_KEY] != null }
    val model: Flow<String> = store.data.map { it[MODEL] ?: GeminiSummarizer.DEFAULT_MODEL }
    val speechRate: Flow<Float> = store.data.map { it[SPEECH_RATE] ?: 1.0f }
    val storyCount: Flow<Int> = store.data.map { it[STORY_COUNT] ?: DEFAULT_STORY_COUNT }
    val includeAskShow: Flow<Boolean> = store.data.map { it[INCLUDE_ASK_SHOW] ?: true }
    val heardCount: Flow<Int> = store.data.map { parseIds(it[HEARD]).size }
    val saved: Flow<List<SavedStory>> = store.data.map { prefs ->
        prefs[SAVED]?.let { runCatching { json.decodeFromString<List<SavedStory>>(it) }.getOrNull() }.orEmpty()
    }

    suspend fun apiKey(): String? = store.data.first()[API_KEY]?.let(box::decrypt)

    suspend fun setApiKey(key: String) {
        val trimmed = key.trim()
        store.edit {
            if (trimmed.isEmpty()) it.remove(API_KEY) else it[API_KEY] = box.encrypt(trimmed)
        }
    }

    suspend fun setModel(model: String) {
        store.edit {
            if (model.isBlank()) it.remove(MODEL) else it[MODEL] = model.trim()
        }
    }

    suspend fun setSpeechRate(rate: Float) {
        store.edit { it[SPEECH_RATE] = rate }
    }

    suspend fun setStoryCount(count: Int) {
        store.edit { it[STORY_COUNT] = count }
    }

    suspend fun setIncludeAskShow(include: Boolean) {
        store.edit { it[INCLUDE_ASK_SHOW] = include }
    }

    suspend fun heardIds(): Set<Long> = parseIds(store.data.first()[HEARD]).toSet()

    /** Most recent last; capped so the list can't grow forever. */
    suspend fun markHeard(id: Long) {
        store.edit { prefs ->
            val ids = parseIds(prefs[HEARD]).filter { it != id } + id
            prefs[HEARD] = ids.takeLast(MAX_HEARD).joinToString(",")
        }
    }

    suspend fun clearHeard() {
        store.edit { it.remove(HEARD) }
    }

    suspend fun save(story: SavedStory) {
        store.edit { prefs ->
            val current = prefs[SAVED]?.let { runCatching { json.decodeFromString<List<SavedStory>>(it) }.getOrNull() }.orEmpty()
            prefs[SAVED] = json.encodeToString(listOf(story) + current.filter { it.id != story.id })
        }
    }

    suspend fun removeSaved(id: Long) {
        store.edit { prefs ->
            val current = prefs[SAVED]?.let { runCatching { json.decodeFromString<List<SavedStory>>(it) }.getOrNull() }.orEmpty()
            prefs[SAVED] = json.encodeToString(current.filter { it.id != id })
        }
    }

    private fun parseIds(raw: String?): List<Long> =
        raw?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()

    companion object {
        const val DEFAULT_STORY_COUNT = 30
        private const val MAX_HEARD = 2000
        private val API_KEY = stringPreferencesKey("gemini_api_key_encrypted")
        private val MODEL = stringPreferencesKey("gemini_model")
        private val SPEECH_RATE = floatPreferencesKey("speech_rate")
        private val STORY_COUNT = intPreferencesKey("story_count")
        private val INCLUDE_ASK_SHOW = booleanPreferencesKey("include_ask_show")
        private val HEARD = stringPreferencesKey("heard_story_ids")
        private val SAVED = stringPreferencesKey("saved_stories")
    }
}
