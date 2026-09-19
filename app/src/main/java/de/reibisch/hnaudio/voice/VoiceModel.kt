package de.reibisch.hnaudio.voice

import de.reibisch.hnaudio.data.fetch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * The offline Vosk speech model. Downloaded on demand rather than bundled, so the APK and
 * the repository stay small and the model only exists if voice commands are wanted.
 */
class VoiceModel(filesDir: File, private val cacheDir: File, http: OkHttpClient) {
    private val http = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    val dir = File(filesDir, "vosk/$NAME")

    private val _progress = MutableStateFlow<Float?>(null)
    /** 0..1 while downloading, null otherwise. */
    val progress: StateFlow<Float?> = _progress

    private val _installed = MutableStateFlow(isComplete())
    val installed: StateFlow<Boolean> = _installed

    private fun isComplete() = File(dir, "am/final.mdl").exists()

    /** Downloads and unpacks the model; throws [IOException] on failure. */
    suspend fun download() {
        if (isComplete()) return
        _progress.value = 0f
        val zip = File(cacheDir, "$NAME.zip")
        try {
            http.fetch(Request.Builder().url(URL).build()) { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val total = response.body.contentLength().takeIf { it > 0 } ?: APPROX_BYTES
                response.body.byteStream().use { input ->
                    zip.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            _progress.value = (done.toFloat() / total).coerceAtMost(0.99f)
                        }
                    }
                }
                unpack(zip)
            }
            _installed.value = isComplete()
        } finally {
            zip.delete()
            _progress.value = null
        }
    }

    fun delete() {
        dir.deleteRecursively()
        _installed.value = false
    }

    /** Extracts into a temp dir, dropping the zip's top-level folder, then swaps it in. */
    private fun unpack(zip: File) {
        val tmp = File(dir.parentFile, "$NAME.tmp").apply { deleteRecursively(); mkdirs() }
        val root = tmp.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty()) continue
                val out = File(tmp, relative)
                if (!out.canonicalPath.startsWith(root)) throw IOException("Bad zip entry ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw IOException("Couldn't move the model into place")
    }

    companion object {
        const val NAME = "vosk-model-small-en-us-0.15"
        private const val URL = "https://alphacephei.com/vosk/models/$NAME.zip"
        private const val APPROX_BYTES = 41_000_000L
        const val SIZE_LABEL = "39 MB"
    }
}
