package com.simon.harmonichackernews.feed

import com.simon.harmonichackernews.feed.data.FeedSubscriptionStore
import com.simon.harmonichackernews.feed.model.FeedFolder
import com.simon.harmonichackernews.feed.model.FeedItem
import com.simon.harmonichackernews.feed.model.FeedSource
import com.simon.harmonichackernews.feed.opml.OpmlCodec
import com.simon.harmonichackernews.feed.sync.FeedSyncCoordinator
import com.simon.harmonichackernews.feed.sync.FeedSyncResult
import com.simon.harmonichackernews.settings.TestKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedPhase2Test {

    @Test
    fun feedSubscriptionStoreManagesFeedsFoldersAndItems() = runTest {
        val store = FeedSubscriptionStore(TestKeyValueStore(), maxItemsRetained = 3)

        // Add folder
        val folder = store.createFolder("Technology")
        assertEquals("Technology", folder.name)
        assertEquals(1, store.state.value.folders.size)

        // Add feed
        val feed = store.addFeed(
            url = "https://example.com/rss.xml",
            title = "Tech Feed",
            folderId = folder.id,
        )
        assertEquals("Tech Feed", feed.title)
        assertEquals(folder.id, feed.folderId)
        assertEquals(1, store.state.value.feeds.size)

        // Duplicate add returns existing feed
        val duplicate = store.addFeed("https://example.com/rss.xml")
        assertEquals(feed.id, duplicate.id)
        assertEquals(1, store.state.value.feeds.size)

        // Upsert items
        val item1 = FeedItem(
            id = "item1", feedId = feed.id, title = "Post 1", url = "https://example.com/1",
            publishedEpochMs = 1000L,
        )
        val item2 = FeedItem(
            id = "item2", feedId = feed.id, title = "Post 2", url = "https://example.com/2",
            publishedEpochMs = 2000L,
        )
        store.upsertItems(listOf(item1, item2))
        assertEquals(2, store.state.value.items.size)
        assertEquals(2, store.state.value.unreadCount)

        // Mark read & bookmark
        store.markItemRead("item1", true)
        store.markItemBookmarked("item1", true)
        assertEquals(1, store.state.value.unreadCount)
        assertTrue(store.state.value.items.first { it.id == "item1" }.isRead)
        assertTrue(store.state.value.items.first { it.id == "item1" }.isBookmarked)

        // Upsert retention eviction: item1 is bookmarked, so oldest unbookmarked gets evicted
        val item3 = FeedItem(id = "item3", feedId = feed.id, title = "Post 3", url = "https://example.com/3", publishedEpochMs = 3000L)
        val item4 = FeedItem(id = "item4", feedId = feed.id, title = "Post 4", url = "https://example.com/4", publishedEpochMs = 4000L)
        store.upsertItems(listOf(item3, item4))

        // Max capacity is 3: item4 (4000), item3 (3000), item1 (1000, bookmarked). item2 (2000, unbookmarked) evicted.
        assertEquals(3, store.state.value.items.size)
        assertTrue(store.state.value.items.any { it.id == "item1" })
        assertFalse(store.state.value.items.any { it.id == "item2" })

        // Mark feed read
        store.markFeedRead(feed.id)
        assertEquals(0, store.state.value.unreadCount)

        // Remove folder preserves feeds as root
        store.removeFolder(folder.id)
        assertEquals(0, store.state.value.folders.size)
        assertNull(store.state.value.feeds.first().folderId)

        // Remove feed deletes items
        store.removeFeed(feed.id)
        assertEquals(0, store.state.value.feeds.size)
        assertEquals(0, store.state.value.items.size)
    }

    @Test
    fun opmlCodecRoundTripsFeedsAndFolders() {
        val opmlInput = """
            <?xml version="1.0" encoding="UTF-8"?>
            <opml version="2.0">
              <head>
                <title>My Subscriptions</title>
              </head>
              <body>
                <outline text="News" title="News">
                  <outline type="rss" text="Hacker News" title="Hacker News" xmlUrl="https://news.ycombinator.com/rss" htmlUrl="https://news.ycombinator.com"/>
                </outline>
                <outline type="rss" text="Lobsters" title="Lobsters" xmlUrl="https://lobste.rs/rss"/>
              </body>
            </opml>
        """.trimIndent()

        val decoded = OpmlCodec.decode(opmlInput)
        assertEquals("My Subscriptions", decoded.title)
        assertEquals(2, decoded.entries.size)

        val hn = decoded.entries[0]
        assertEquals("Hacker News", hn.title)
        assertEquals("https://news.ycombinator.com/rss", hn.xmlUrl)
        assertEquals("https://news.ycombinator.com", hn.htmlUrl)
        assertEquals("News", hn.folder)

        val lobsters = decoded.entries[1]
        assertEquals("Lobsters", lobsters.title)
        assertEquals("https://lobste.rs/rss", lobsters.xmlUrl)
        assertNull(lobsters.folder)

        // Test encode
        val folder = FeedFolder(id = "folder1", name = "News")
        val feed1 = FeedSource(id = "1", url = "https://news.ycombinator.com/rss", title = "Hacker News", siteUrl = "https://news.ycombinator.com", folderId = folder.id)
        val feed2 = FeedSource(id = "2", url = "https://lobste.rs/rss", title = "Lobsters")

        val encoded = OpmlCodec.encode(listOf(feed1, feed2), listOf(folder), "Exported Subscriptions")
        val redecoded = OpmlCodec.decode(encoded)

        assertEquals("Exported Subscriptions", redecoded.title)
        assertEquals(2, redecoded.entries.size)
        assertEquals("News", redecoded.entries.first { it.xmlUrl == feed1.url }.folder)
    }

    @Test
    fun feedSyncCoordinatorHandlesSuccessAndConditional304() = runTest {
        val store = FeedSubscriptionStore(TestKeyValueStore())
        val feed = store.addFeed("https://example.com/feed.xml")

        val rssPayload = """
            <rss version="2.0">
              <channel>
                <title>Example RSS</title>
                <link>https://example.com</link>
                <item>
                  <title>Article A</title>
                  <link>https://example.com/a</link>
                  <guid>a</guid>
                  <pubDate>Mon, 27 Oct 2026 10:00:00 GMT</pubDate>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        var requestedEtag: String? = null
        val client = HttpClient(MockEngine { request ->
            requestedEtag = request.headers[HttpHeaders.IfNoneMatch]
            if (requestedEtag == "\"v1\"") {
                respond("", HttpStatusCode.NotModified)
            } else {
                respond(
                    content = rssPayload,
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ETag to listOf("\"v1\""),
                        HttpHeaders.LastModified to listOf("Mon, 27 Oct 2026 10:00:00 GMT"),
                    ),
                )
            }
        })

        val coordinator = FeedSyncCoordinator(client, store, nowEpochMs = { 100_000L })

        // Initial fetch: 200 OK
        val result1 = coordinator.syncFeed(feed)
        assertTrue(result1 is FeedSyncResult.Success)
        assertEquals(1, result1.itemsCount)

        val updatedFeed = store.state.value.feeds.first()
        assertEquals("\"v1\"", updatedFeed.etag)
        assertEquals("Example RSS", updatedFeed.title)
        assertEquals(1, store.state.value.items.size)
        assertEquals(100_000L, updatedFeed.lastFetchedEpochMs)

        // Second fetch: sends If-None-Match and receives 304 Not Modified
        val result2 = coordinator.syncFeed(updatedFeed)
        assertTrue(result2 is FeedSyncResult.NotModified)
        assertEquals("\"v1\"", requestedEtag)
    }
}
