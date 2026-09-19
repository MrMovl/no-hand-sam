package de.reibisch.hnaudio.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class VoiceCommand { NEXT, PREVIOUS_STORY, READ_ARTICLE, REPEAT, SAVE, PAUSE, PLAY }

/** The fixed phrases the recognizer listens for. Anything else comes back as "[unk]". */
object VoiceCommands {
    val phrases: Map<String, VoiceCommand> = mapOf(
        "next story" to VoiceCommand.NEXT,
        "next" to VoiceCommand.NEXT,
        "skip" to VoiceCommand.NEXT,
        "previous story" to VoiceCommand.PREVIOUS_STORY,
        "go back" to VoiceCommand.PREVIOUS_STORY,
        "read article" to VoiceCommand.READ_ARTICLE,
        "read more" to VoiceCommand.READ_ARTICLE,
        "full article" to VoiceCommand.READ_ARTICLE,
        "repeat" to VoiceCommand.REPEAT,
        "save story" to VoiceCommand.SAVE,
        "save this" to VoiceCommand.SAVE,
        "bookmark" to VoiceCommand.SAVE,
        "pause" to VoiceCommand.PAUSE,
        "stop" to VoiceCommand.PAUSE,
        "play" to VoiceCommand.PLAY,
        "continue" to VoiceCommand.PLAY,
        "resume" to VoiceCommand.PLAY,
    )

    /** The first phrase listed for each command, in command order, for on-screen hints. */
    val primaryPhrases: List<String> =
        phrases.entries.distinctBy { it.value }.sortedBy { it.value.ordinal }.map { it.key }

    /** Vosk grammar: the phrases plus "[unk]" so other speech doesn't get forced onto them. */
    val grammar: String = (phrases.keys + "[unk]").joinToString(",", "[", "]") { "\"$it\"" }

    /** Words below this recognizer confidence are ignored. */
    const val MIN_CONFIDENCE = 0.6

    data class Heard(val text: String, val command: VoiceCommand?, val confidence: Double)

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses a Vosk result (`setWords(true)`), or null for silence or unknown speech. */
    fun parse(result: String): Heard? {
        val obj = runCatching { json.parseToJsonElement(result).jsonObject }.getOrNull() ?: return null
        val text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (text.isEmpty() || text.contains("[unk]")) return null
        val words = (obj["result"] as? JsonArray).orEmpty().map { it.jsonObject }
        val confidence = words.mapNotNull { it["conf"]?.jsonPrimitive?.doubleOrNull }.minOrNull() ?: 1.0
        val command = phrases[text]?.takeIf { confidence >= MIN_CONFIDENCE }
        return Heard(text, command, confidence)
    }
}
