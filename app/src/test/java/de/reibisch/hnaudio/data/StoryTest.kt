package de.reibisch.hnaudio.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `link story maps all fields`() {
        val item = json.decodeFromString<HnItem>(
            """{"by":"pg","descendants":71,"id":8863,"kids":[9224,8917],"score":104,
               "time":1175714200,"title":"My YC app: Dropbox","type":"story",
               "url":"http://www.getdropbox.com/u/2/screencast.html"}""",
        )
        val story = requireNotNull(item.toStory())
        assertEquals("My YC app: Dropbox", story.title)
        assertEquals(104, story.score)
        assertEquals(71, story.commentCount)
        assertEquals(listOf(9224L, 8917L), story.commentIds)
        assertEquals("getdropbox.com", story.domain)
        assertTrue(story.text.isEmpty())
    }

    @Test
    fun `ask hn text is decoded into plain paragraphs`() {
        val item = json.decodeFromString<HnItem>(
            """{"id":1,"type":"story","title":"Ask HN: How do you test?","score":5,
               "text":"First line with &quot;quotes&quot; &amp; <i>italics</i>.<p>Second <a href=\"https:&#x2F;&#x2F;x.io\">link</a> para."}""",
        )
        val story = requireNotNull(item.toStory())
        assertNull(story.url)
        assertNull(story.domain)
        assertEquals(listOf("First line with \"quotes\" & italics.", "Second link para."), story.text)
        assertEquals(0, story.commentCount)
    }

    @Test
    fun `dead, deleted and untitled items are dropped`() {
        assertNull(HnItem(id = 1, title = "x", dead = true).toStory())
        assertNull(HnItem(id = 2, title = "x", deleted = true).toStory())
        assertNull(HnItem(id = 3).toStory())
    }
}
