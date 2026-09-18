package de.reibisch.hnaudio.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {
    @Test
    fun `short text stays one chunk with whitespace normalised`() {
        assertEquals(listOf("Story 1. Title. Summary here."), TextChunker.chunk("Story 1. Title.\n\n  Summary   here.", 100))
    }

    @Test
    fun `paragraphs are packed together while they fit`() {
        val text = "aaaa aaaa.\n\nbbbb bbbb.\n\ncccc cccc."
        assertEquals(listOf("aaaa aaaa. bbbb bbbb.", "cccc cccc."), TextChunker.chunk(text, 22))
    }

    @Test
    fun `long paragraph is split at sentence boundaries`() {
        val text = "First sentence here. Second one is here! Third? \"Quoted end.\" Last."
        val chunks = TextChunker.chunk(text, 25)
        assertEquals(listOf("First sentence here.", "Second one is here!", "Third? \"Quoted end.\"", "Last."), chunks)
    }

    @Test
    fun `sentence longer than the limit is split between words`() {
        val text = "one two three four five six seven eight nine ten"
        val chunks = TextChunker.chunk(text, 15)
        assertTrue(chunks.all { it.length <= 15 })
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun `no chunk exceeds the limit and no text is lost`() {
        val text = (1..200).joinToString(" ") { "Sentence number $it is here." } +
            "\n\n" + "x".repeat(70) + " tail"
        val chunks = TextChunker.chunk(text, 60)
        assertTrue(chunks.all { it.length <= 60 })
        assertEquals(text.replace(Regex("\\s+"), ""), chunks.joinToString("").replace(Regex("\\s+"), ""))
    }
}
