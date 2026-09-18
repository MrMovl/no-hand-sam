package de.reibisch.hnaudio.tts

import java.io.File

/** Rendered audio lives in `cacheDir/tts`; played files are deleted, the rest is size-capped. */
class TtsFiles(cacheDir: File, private val maxBytes: Long = 200L * 1024 * 1024) {
    val dir = File(cacheDir, "tts").apply { mkdirs() }

    fun newFile(): File = File(dir, "${System.currentTimeMillis()}-${counter++}.wav")

    fun delete(file: File) {
        if (file.parentFile == dir) file.delete()
    }

    /** Deletes the oldest files until the directory is under the size limit. */
    fun trim() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    private var counter = 0
}
