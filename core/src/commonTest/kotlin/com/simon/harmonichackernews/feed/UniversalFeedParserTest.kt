package com.simon.harmonichackernews.feed

import com.simon.harmonichackernews.feed.parser.FeedDateParser
import com.simon.harmonichackernews.feed.parser.UniversalFeedParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UniversalFeedParserTest {
    private val parser = UniversalFeedParser()

    @Test
    fun parsesRss2Feed() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel>
                <title>Tech News RSS</title>
                <link>https://example.com</link>
                <description>Daily tech headlines</description>
                <item>
                  <title>Article One</title>
                  <link>https://example.com/posts/1</link>
                  <guid>https://example.com/posts/1</guid>
                  <pubDate>Mon, 27 Oct 2026 14:30:00 GMT</pubDate>
                  <dc:creator>Alice Developer</dc:creator>
                  <description>&lt;p&gt;First article description with an &lt;img src="https://example.com/img1.png"/&gt;&lt;/p&gt;</description>
                </item>
                <item>
                  <title>Article Two</title>
                  <link>/posts/2</link>
                  <pubDate>28 Oct 2026 09:00:00 +0200</pubDate>
                  <enclosure url="https://example.com/cover2.jpg" type="image/jpeg"/>
                  <description>Plain summary text</description>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val parsed = parser.parse(xml, "https://example.com/feed.xml")

        assertEquals("Tech News RSS", parsed.title)
        assertEquals("https://example.com", parsed.siteUrl)
        assertEquals("Daily tech headlines", parsed.description)
        assertEquals(2, parsed.items.size)

        val item1 = parsed.items[0]
        assertEquals("Article One", item1.title)
        assertEquals("https://example.com/posts/1", item1.url)
        assertEquals("Alice Developer", item1.author)
        assertEquals("https://example.com/img1.png", item1.leadImageUrl)
        assertTrue(item1.publishedEpochMs > 0L)

        val item2 = parsed.items[1]
        assertEquals("Article Two", item2.title)
        assertEquals("https://example.com/posts/2", item2.url) // Relative URL resolved
        assertEquals("https://example.com/cover2.jpg", item2.leadImageUrl)
        assertTrue(item2.publishedEpochMs > 0L)
    }

    @Test
    fun parsesAtomFeed() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Developer Blog Atom</title>
              <link rel="alternate" href="https://blog.example.com"/>
              <subtitle>Engineering updates</subtitle>
              <entry>
                <title>Kotlin Multiplatform in 2026</title>
                <id>urn:uuid:12345</id>
                <link rel="alternate" href="/posts/kmp-2026"/>
                <published>2026-10-27T10:00:00Z</published>
                <author>
                  <name>Bob Engineer</name>
                </author>
                <content type="html">&lt;p&gt;Overview of KMP tooling.&lt;/p&gt;</content>
              </entry>
            </feed>
        """.trimIndent()

        val parsed = parser.parse(xml, "https://blog.example.com/atom.xml")

        assertEquals("Developer Blog Atom", parsed.title)
        assertEquals("https://blog.example.com", parsed.siteUrl)
        assertEquals("Engineering updates", parsed.description)
        assertEquals(1, parsed.items.size)

        val entry = parsed.items[0]
        assertEquals("Kotlin Multiplatform in 2026", entry.title)
        assertEquals("https://blog.example.com/posts/kmp-2026", entry.url)
        assertEquals("urn:uuid:12345", entry.guid)
        assertEquals("Bob Engineer", entry.author)
        assertTrue(entry.publishedEpochMs > 0L)
        assertNotNull(entry.contentHtml)
        assertEquals("Overview of KMP tooling.", entry.contentText)
    }

    @Test
    fun parsesJsonFeed() {
        val json = """
            {
              "version": "https://jsonfeed.org/version/1.1",
              "title": "JSON Feed Sample",
              "home_page_url": "https://jsonfeed.example.com",
              "feed_url": "https://jsonfeed.example.com/feed.json",
              "description": "Feed description",
              "icon": "https://jsonfeed.example.com/icon.png",
              "items": [
                {
                  "id": "post-42",
                  "url": "https://jsonfeed.example.com/posts/42",
                  "title": "JSON Feed Item",
                  "content_html": "<p>Hello world</p>",
                  "date_published": "2026-10-27T12:00:00+00:00",
                  "image": "https://jsonfeed.example.com/hero.png",
                  "authors": [
                    { "name": "Charlie" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = parser.parse(json, "https://jsonfeed.example.com/feed.json")

        assertEquals("JSON Feed Sample", parsed.title)
        assertEquals("https://jsonfeed.example.com", parsed.siteUrl)
        assertEquals("https://jsonfeed.example.com/icon.png", parsed.iconUrl)
        assertEquals(1, parsed.items.size)

        val item = parsed.items[0]
        assertEquals("post-42", item.guid)
        assertEquals("https://jsonfeed.example.com/posts/42", item.url)
        assertEquals("JSON Feed Item", item.title)
        assertEquals("Charlie", item.author)
        assertEquals("https://jsonfeed.example.com/hero.png", item.leadImageUrl)
        assertTrue(item.publishedEpochMs > 0L)
    }

    @Test
    fun feedDateParserHandlesVariousFormats() {
        // RFC 822 GMT
        val rfcGmt = FeedDateParser.parseEpochMs("Mon, 27 Oct 2026 14:30:00 GMT")
        assertTrue(rfcGmt > 0L)

        // RFC 822 numeric offset
        val rfcOffset = FeedDateParser.parseEpochMs("27 Oct 2026 14:30:00 +0200")
        assertTrue(rfcOffset > 0L)

        // RFC 822 named timezone
        val rfcEst = FeedDateParser.parseEpochMs("Mon, 27 Oct 2026 09:30:00 EST")
        assertTrue(rfcEst > 0L)

        // ISO 8601 UTC
        val isoUtc = FeedDateParser.parseEpochMs("2026-10-27T14:30:00Z")
        assertTrue(isoUtc > 0L)

        // ISO 8601 with offset
        val isoOffset = FeedDateParser.parseEpochMs("2026-10-27T16:30:00+02:00")
        assertTrue(isoOffset > 0L)

        // Fallbacks
        assertEquals(0L, FeedDateParser.parseEpochMs(null))
        assertEquals(0L, FeedDateParser.parseEpochMs(""))
        assertEquals(0L, FeedDateParser.parseEpochMs("invalid date string"))
    }
}
