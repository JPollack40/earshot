package com.earshot.app.data.articles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArticlesTest {
    private val long1 = "The first paragraph of the story is long enough to count as real text for reading aloud."
    private val long2 = "A second paragraph follows it, also long enough, with an &amp; and a &#8217; in it too."

    private val page = """
        <html><head>
          <title>Ignored when og:title exists</title>
          <meta property="og:title" content="Big News &amp; More">
          <meta property="og:site_name" content="The Daily">
          <meta property="og:image" content="https://example.com/a.jpg">
          <script>var x = "<p>not this paragraph, which is inside a script and long enough</p>";</script>
        </head><body>
          <nav><p>Home · World · A menu paragraph that is long enough to be counted if kept</p></nav>
          <article>
            <h1>Big News &amp; More</h1>
            <p>By someone</p>
            <p>$long1</p>
            <h2>What happened next</h2>
            <figure><p>A caption inside a figure, long enough to count if it were kept</p></figure>
            <p>$long2</p>
          </article>
          <footer><p>Copyright and other footer text that is long enough to be counted</p></footer>
        </body></html>
    """.trimIndent()

    @Test fun extractsTheArticleOnly() {
        val a = ArticleText.extract(page, "https://www.example.com/news/1")
        assertEquals("Big News & More", a.title)
        assertEquals("The Daily", a.site)
        assertEquals("https://example.com/a.jpg", a.imageUrl)
        assertEquals(
            listOf(long1, "What happened next.", "A second paragraph follows it, also long enough, with an & and a ’ in it too."),
            a.paragraphs,
        )
    }

    @Test fun fallsBackToTitleAndHost() {
        val a = ArticleText.extract("<html><head><title>Plain page</title></head><body><p>$long1</p></body></html>", "https://www.blog.org/x")
        assertEquals("Plain page", a.title)
        assertEquals("blog.org", a.site)
        assertNull(a.imageUrl)
        assertEquals(listOf(long1), a.paragraphs)
    }

    @Test fun piecesStayUnderTheLimit() {
        val para = (1..30).joinToString(" ") { "Sentence number $it is here." }
        val pieces = ArticleText.pieces(listOf("Short one.", para), 120)
        assertEquals("Short one.", pieces.first())
        assertTrue(pieces.all { it.length <= 120 })
        assertEquals(para, pieces.drop(1).joinToString(" "))
    }

    @Test fun idsAndLinks() {
        assertEquals(Articles.episodeId("https://a.com/x/"), Articles.episodeId("https://a.com/x#top"))
        assertTrue(Articles.isArticle(Articles.episodeId("https://a.com/x")))
        assertFalse(Articles.isArticle("3f2a9c"))
        assertTrue(Articles.isLocalFeed(Articles.FEED_URL))
        assertFalse(Articles.isLocalFeed("https://feeds.example.com/rss"))
        assertEquals("https://a.com/story?id=2", Articles.firstUrl("Read this: https://a.com/story?id=2."))
        assertNull(Articles.firstUrl("no link here"))
    }

    @Test fun wavPiecesJoin() {
        val one = Wav.Pcm(1, 1000, 16, ByteArray(2000) { 1 }) // 1 s
        val two = Wav.Pcm(1, 1000, 16, ByteArray(1000) { 2 }) // 0.5 s
        // Reading a file the voice made, with an extra chunk before the data.
        val raw = Wav.header(1, 1000, 16, 2000).let { h ->
            val list = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put("LIST".toByteArray()).putInt(4).put("INFO".toByteArray()).array()
            h.copyOfRange(0, 36) + list + h.copyOfRange(36, 44) + one.data
        }
        val read = Wav.read(raw)
        assertTrue(read.sameFormat(one))
        assertEquals(1000L, read.durationMs)

        val f = File.createTempFile("join", ".wav")
        try {
            val total = Wav.Writer(f).use { w ->
                assertEquals(1000L, w.add(read))
                assertEquals(500L, w.add(two))
                w.finish()
            }
            assertEquals(1500L, total)
            val joined = Wav.read(f.readBytes())
            assertEquals(3000, joined.data.size)
            assertEquals(1500L, joined.durationMs)
        } finally {
            f.delete()
        }
    }
}
