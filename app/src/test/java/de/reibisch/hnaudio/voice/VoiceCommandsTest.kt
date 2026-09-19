package de.reibisch.hnaudio.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandsTest {
    @Test
    fun `grammar lists every phrase plus unk as a json array`() {
        val grammar = VoiceCommands.grammar
        assertTrue(grammar.startsWith("[\"") && grammar.endsWith("\"[unk]\"]"))
        VoiceCommands.phrases.keys.forEach { assertTrue(it, grammar.contains("\"$it\"")) }
    }

    @Test
    fun `confident phrase maps to its command`() {
        val result = """{"result":[{"conf":0.98,"end":1.2,"start":0.9,"word":"next"},
            {"conf":0.91,"end":1.6,"start":1.2,"word":"story"}],"text":"next story"}"""
        val heard = VoiceCommands.parse(result)!!
        assertEquals(VoiceCommand.NEXT, heard.command)
        assertEquals(0.91, heard.confidence, 1e-9)
    }

    @Test
    fun `low confidence is heard but not acted on`() {
        val heard = VoiceCommands.parse("""{"result":[{"conf":0.42,"word":"pause"}],"text":"pause"}""")!!
        assertNull(heard.command)
    }

    @Test
    fun `silence and unknown speech are ignored`() {
        assertNull(VoiceCommands.parse("""{"text":""}"""))
        assertNull(VoiceCommands.parse("""{"text":"[unk]"}"""))
        assertNull(VoiceCommands.parse("""{"text":"[unk] next"}"""))
        assertNull(VoiceCommands.parse("not json"))
    }

    @Test
    fun `one hint phrase per command, in command order`() {
        assertEquals(
            listOf("next story", "previous story", "read article", "repeat", "save story", "pause", "play"),
            VoiceCommands.primaryPhrases,
        )
    }
}
