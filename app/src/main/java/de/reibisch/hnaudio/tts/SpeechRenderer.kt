package de.reibisch.hnaudio.tts

import java.io.File

/** Turns text into audio files, one per chunk, in playback order. */
interface SpeechRenderer {
    suspend fun render(text: String): List<File>
}

class SpeechException(message: String) : Exception(message)
