package de.reibisch.hnaudio.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.reibisch.hnaudio.summary.GeminiSummarizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class Settings(context: Context) {
    private val store = context.applicationContext.dataStore
    private val box = SecretBox("gemini_api_key")

    val hasApiKey: Flow<Boolean> = store.data.map { it[API_KEY] != null }
    val model: Flow<String> = store.data.map { it[MODEL] ?: GeminiSummarizer.DEFAULT_MODEL }

    val speechRate: Flow<Float> = store.data.map { it[SPEECH_RATE] ?: 1.0f }

    suspend fun setSpeechRate(rate: Float) {
        store.edit { it[SPEECH_RATE] = rate }
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

    private companion object {
        val API_KEY = stringPreferencesKey("gemini_api_key_encrypted")
        val MODEL = stringPreferencesKey("gemini_model")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
    }
}
