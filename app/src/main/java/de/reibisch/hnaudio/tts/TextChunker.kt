package de.reibisch.hnaudio.tts

/**
 * Splits text into chunks of at most [maxChars], breaking at paragraph boundaries first,
 * then sentences, then words, so the speech engine never cuts a word in half.
 */
object TextChunker {
    private val SENTENCE_END = Regex("(?<=[.!?…][\"')\\]]{0,2})\\s+")

    fun chunk(text: String, maxChars: Int): List<String> {
        require(maxChars > 0)
        val pieces = text.split(Regex("\\n\\s*\\n"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .flatMap { paragraph -> if (paragraph.length <= maxChars) listOf(paragraph) else splitLong(paragraph, maxChars) }
        return pack(pieces, maxChars)
    }

    /** Splits one over-long paragraph into sentences, and sentences into word runs if needed. */
    private fun splitLong(paragraph: String, maxChars: Int): List<String> =
        paragraph.split(SENTENCE_END)
            .filter { it.isNotBlank() }
            .flatMap { sentence -> if (sentence.length <= maxChars) listOf(sentence.trim()) else splitWords(sentence, maxChars) }

    private fun splitWords(sentence: String, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (word in sentence.split(' ').filter { it.isNotEmpty() }) {
            // A single word longer than the limit (a URL, say) is hard-cut.
            word.chunked(maxChars).forEach { part ->
                if (current.isNotEmpty() && current.length + 1 + part.length > maxChars) {
                    out += current.toString()
                    current.clear()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(part)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** Joins consecutive pieces while they fit, so short sentences don't each become a file. */
    private fun pack(pieces: List<String>, maxChars: Int): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty() && current.length + 1 + piece.length > maxChars) {
                out += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}
