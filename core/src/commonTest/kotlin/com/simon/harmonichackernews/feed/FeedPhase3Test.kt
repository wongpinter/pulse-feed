package com.simon.harmonichackernews.feed

import com.simon.harmonichackernews.feed.model.FeedItem
import com.simon.harmonichackernews.feed.model.FeedSource
import com.simon.harmonichackernews.feed.model.FeedTimelineFilter
import com.simon.harmonichackernews.feed.model.filterFeedItems
import com.simon.harmonichackernews.feed.parser.FeedAutoDiscovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedPhase3Test {

    @Test
    fun autoDiscoveryFindsRssAndAtomLinksFromHtml() {
        val html = """
            <!DOCTYPE html>
            <html>
              <head>
                <title>Example Blog</title>
                <link rel="alternate" type="application/rss+xml" title="Blog RSS" href="/rss.xml" />
                <link rel="alternate" type="application/atom+xml" title="Blog Atom" href="https://example.com/atom.xml" />
              </head>
              <body></body>
            </html>
        """.trimIndent()

        val discovered = FeedAutoDiscovery.discoverFromHtml(html, "https://example.com/posts")
        assertEquals(2, discovered.size)
        assertEquals("https://example.com/rss.xml", discovered[0].feedUrl)
        assertEquals("Blog RSS", discovered[0].title)
        assertEquals("https://example.com/atom.xml", discovered[1].feedUrl)
        assertEquals("Blog Atom", discovered[1].title)
    }

    @Test
    fun autoDiscoveryFallsBackToAnchorLinks() {
        val html = """
            <html>
              <body>
                <p>Follow us via <a href="/feed">our RSS feed</a>!</p>
              </body>
            </html>
        """.trimIndent()

        val discovered = FeedAutoDiscovery.discoverFromHtml(html, "https://example.com")
        assertEquals(1, discovered.size)
        assertEquals("https://example.com/feed", discovered[0].feedUrl)
    }

    @Test
    fun filterFeedItemsAppliesChannelFolderFeedAndQuery() {
        val feedTech = FeedSource(id = "f1", url = "https://a.com/rss", title = "Tech News", folderId = "tech")
        val feedArt = FeedSource(id = "f2", url = "https://b.com/rss", title = "Art Blog", folderId = null)
        val feeds = listOf(feedTech, feedArt)

        val item1 = FeedItem(id = "1", feedId = "f1", title = "Kotlin 2.4 Released", url = "https://a.com/1", isRead = false, isBookmarked = true)
        val item2 = FeedItem(id = "2", feedId = "f1", title = "Rust Async Update", url = "https://a.com/2", isRead = true, isBookmarked = false)
        val item3 = FeedItem(id = "3", feedId = "f2", title = "Watercolor Tips", url = "https://b.com/1", isRead = false, isBookmarked = false)
        val items = listOf(item1, item2, item3)

        assertEquals(3, filterFeedItems(items, feeds, FeedTimelineFilter.All).size)
        assertEquals(2, filterFeedItems(items, feeds, FeedTimelineFilter.Unread).size)
        assertEquals(1, filterFeedItems(items, feeds, FeedTimelineFilter.Bookmarked).size)
        assertEquals(1, filterFeedItems(items, feeds, FeedTimelineFilter.ReadingQueue).size)
        assertEquals(2, filterFeedItems(items, feeds, FeedTimelineFilter.ByFolder("tech", "Tech")).size)
        assertEquals(1, filterFeedItems(items, feeds, FeedTimelineFilter.ByFeed("f2", "Art Blog")).size)

        // Query filtering
        val queryResult = filterFeedItems(items, feeds, FeedTimelineFilter.All, query = "kotlin")
        assertEquals(1, queryResult.size)
        assertEquals("1", queryResult.first().id)
        assertTrue(filterFeedItems(items, feeds, FeedTimelineFilter.All, query = "missing").isEmpty())
    }
}
