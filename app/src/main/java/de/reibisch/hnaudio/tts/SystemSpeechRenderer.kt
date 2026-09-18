package de.reibisch.hnaudio.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Android's system [TextToSpeech], rendering to WAV files so playback can go through the
 * media session instead of the TTS engine's own audio output.
 */
class SystemSpeechRenderer(
    private val context: Context,
    private val files: TtsFiles,
    private val speechRate: suspend () -> Float,
) : SpeechRenderer {
    private val mutex = Mutex()
    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    override suspend fun render(text: String): List<File> = mutex.withLock {
        val engine = engine()
        engine.setSpeechRate(speechRate())
        val maxChars = minOf(TextToSpeech.getMaxSpeechInputLength(), MAX_CHUNK_CHARS)
        TextChunker.chunk(text, maxChars).map { chunk -> synthesize(engine, chunk) }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    private suspend fun synthesize(engine: TextToSpeech, text: String): File {
        val file = files.newFile()
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        try {
            val queued = engine.synthesizeToFile(text, Bundle(), file, id)
            if (queued != TextToSpeech.SUCCESS) throw SpeechException("TTS refused the text")
            if (!done.await()) throw SpeechException("TTS failed to render")
        } catch (e: Throwable) {
            // Includes cancellation: stop the engine so it doesn't keep rendering for nobody.
            engine.stop()
            file.delete()
            throw e
        } finally {
            pending.remove(id)
        }
        return file
    }

    private suspend fun engine(): TextToSpeech {
        tts?.let { return it }
        val engine = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                lateinit var created: TextToSpeech
                created = TextToSpeech(context.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        cont.resume(created)
                    } else {
                        created.shutdown()
                        cont.cancel(SpeechException("Text-to-speech engine unavailable"))
                    }
                }
            }
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                pending[utteranceId]?.complete(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                pending[utteranceId]?.complete(false)
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                pending[utteranceId]?.complete(false)
            }
        })
        chooseEnglishVoice(engine)
        tts = engine
        return engine
    }

    /** Best installed offline English voice, preferring US English. */
    private fun chooseEnglishVoice(engine: TextToSpeech) {
        val voice = engine.voices.orEmpty()
            .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .maxWithOrNull(compareBy<Voice>({ it.locale.country == "US" }, { it.quality }, { -it.latency }))
        if (voice != null) {
            engine.voice = voice
            Log.i(TAG, "Using voice ${voice.name} (${voice.locale}, quality ${voice.quality})")
        } else {
            val result = engine.setLanguage(Locale.US)
            Log.w(TAG, "No offline English voice found, setLanguage(US) = $result")
        }
    }

    private companion object {
        const val TAG = "Speech"
        /** Smaller chunks mean the first audio is ready sooner. */
        const val MAX_CHUNK_CHARS = 1500
    }
}
