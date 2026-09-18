package de.reibisch.hnaudio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleParserTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { "missing fixture $name" }.readText()

    @Test
    fun `extracts article body without navigation, sidebar, comments or code`() {
        val result = ArticleParser.parse("https://example.com/blog/build", fixture("blog_post.html"))
        assertTrue("expected article, got $result", result is Extraction.Article)
        result as Extraction.Article
        val text = result.paragraphs.joinToString("\n")

        assertTrue(text.contains("forty minutes on a good day"))
        assertTrue(text.contains("people trust the build again"))
        assertTrue(text.contains("The fastest build step is the one you never run."))
        assertTrue(text.contains("Content hashing for every input file"))
        assertFalse("nav leaked", text.contains("We're hiring"))
        assertFalse("sidebar leaked", text.contains("No spam"))
        assertFalse("footer leaked", text.contains("All rights reserved"))
        assertFalse("code leaked", text.contains("--cache=remote"))
        assertEquals(Extraction.Source.Web, result.source)
    }

    @Test
    fun `blockquote with nested paragraph is not duplicated`() {
        val result = ArticleParser.parse("https://example.com/blog/build", fixture("blog_post.html"))
            as Extraction.Article
        assertEquals(1, result.paragraphs.count { it.contains("fastest build step") })
    }

    @Test
    fun `paywalled teaser is reported as too short`() {
        val result = ArticleParser.parse("https://example.com/news/containers", fixture("paywall.html"))
        assertEquals(FailureReason.TooShort, (result as Extraction.Failed).reason)
    }

    @Test
    fun `javascript-only page is reported as too short`() {
        val result = ArticleParser.parse("https://app.example.com/", fixture("js_app.html"))
        assertEquals(FailureReason.TooShort, (result as Extraction.Failed).reason)
    }

    @Test
    fun `readme is split into paragraphs, skipping code`() {
        val result = ArticleParser.parseReadme("someone/tinyqueue", fixture("github_readme.html"))
            as Extraction.Article
        assertEquals("someone/tinyqueue", result.title)
        assertEquals(Extraction.Source.GitHubReadme, result.source)
        assertTrue(result.paragraphs.contains("A small, dependency-free job queue backed by a single SQLite file."))
        assertTrue(result.paragraphs.contains("Scheduled and recurring jobs"))
        assertFalse(result.paragraphs.any { it.contains("pip install") })
    }

    @Test
    fun `video hosts and pdfs are classified from the url`() {
        assertEquals(FailureReason.Video, ArticleParser.classifyUrl("https://www.youtube.com/watch?v=abc"))
        assertEquals(FailureReason.Video, ArticleParser.classifyUrl("https://youtu.be/abc"))
        assertEquals(FailureReason.Video, ArticleParser.classifyUrl("https://vimeo.com/12345"))
        assertEquals(FailureReason.Pdf, ArticleParser.classifyUrl("https://arxiv.org/pdf/2401.00001v1.PDF"))
        assertNull(ArticleParser.classifyUrl("https://example.com/post.html"))
        assertNull(ArticleParser.classifyUrl("https://example.com/pdf-tools"))
    }

    @Test
    fun `only github repo roots are treated as repos`() {
        assertEquals("rust-lang" to "rust", ArticleParser.githubRepo("https://github.com/rust-lang/rust"))
        assertEquals("a" to "b", ArticleParser.githubRepo("https://www.github.com/a/b.git"))
        assertEquals("a" to "b", ArticleParser.githubRepo("https://github.com/a/b/"))
        assertNull(ArticleParser.githubRepo("https://github.com/a/b/issues/1"))
        assertNull(ArticleParser.githubRepo("https://github.com/a"))
        assertNull(ArticleParser.githubRepo("https://gitlab.com/a/b"))
    }
}
