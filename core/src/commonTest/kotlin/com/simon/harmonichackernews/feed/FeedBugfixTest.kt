package com.simon.harmonichackernews.feed

import com.simon.harmonichackernews.feed.data.FeedSubscriptionStore
import com.simon.harmonichackernews.feed.model.FeedItem
import com.simon.harmonichackernews.feed.model.FeedTimelineFilter
import com.simon.harmonichackernews.feed.model.filterFeedItems
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
import kotlin.test.assertTrue

/** Regression coverage for the feed MVP bug pass. */
class FeedBugfixTest {

    private val rssPayload = """
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

    @Test
    fun retentionDropsReadItemsBeforeUnreadOnes() = runTest {
        val store = FeedSubscriptionStore(TestKeyValueStore(), maxItemsRetained = 3)
        val feed = store.addFeed("https://example.com/rss.xml")

        store.upsertItems(
            listOf(
                FeedItem(id = "read-old", feedId = feed.id, title = "Read old", url = "https://example.com/1", publishedEpochMs = 1_000L),
                FeedItem(id = "unread-2", feedId = feed.id, title = "Unread 2", url = "https://example.com/2", publishedEpochMs = 2_000L),
            ),
        )
        store.markItemRead("read-old", true)

        store.upsertItems(
            listOf(
                FeedItem(id = "unread-3", feedId = feed.id, title = "Unread 3", url = "https://example.com/3", publishedEpochMs = 3_000L),
                FeedItem(id = "unread-4", feedId = feed.id, title = "Unread 4", url = "https://example.com/4", publishedEpochMs = 4_000L),
            ),
        )

        val remaining = store.state.value.items.map { it.id }.toSet()
        assertEquals(3, remaining.size)
        assertFalse("read-old" in remaining, "read item must be evicted before unread items")
        assertTrue(remaining.containsAll(listOf("unread-2", "unread-3", "unread-4")))
    }

    @Test
    fun readStateTouchesOnlyTheCompactFlagKey() = runTest {
        val keyValueStore = TestKeyValueStore()
        val store = FeedSubscriptionStore(keyValueStore)
        val feed = store.addFeed("https://example.com/rss.xml")
        store.upsertItems(
            listOf(
                FeedItem(
                    id = "item-1",
                    feedId = feed.id,
                    title = "Heavy article",
                    url = "https://example.com/1",
                    contentHtml = "<p>" + "body ".repeat(500) + "</p>",
                    contentText = "body ".repeat(200),
                ),
            ),
        )
        val itemsBefore = keyValueStore.getString(FeedSubscriptionStore.KEY_ITEMS)
        val flagsBefore = keyValueStore.getString(FeedSubscriptionStore.KEY_ITEM_FLAGS)

        store.markItemRead("item-1", true)
        store.markItemBookmarked("item-1", true)

        assertEquals(itemsBefore, keyValueStore.getString(FeedSubscriptionStore.KEY_ITEMS))
        assertTrue(
            keyValueStore.getString(FeedSubscriptionStore.KEY_ITEM_FLAGS) != flagsBefore,
            "read/bookmark changes must persist through the compact flag key",
        )

        // A fresh store rehydrates the flags on top of the cached item payload.
        val restored = FeedSubscriptionStore(keyValueStore)
        val restoredItem = restored.state.value.items.single()
        assertTrue(restoredItem.isRead)
        assertTrue(restoredItem.isBookmarked)
        assertEquals(0, restored.state.value.unreadCount)
    }

    @Test
    fun readingQueueIsTheUnreadSliceOfSavedArticles() = runTest {
        val feed = com.simon.harmonichackernews.feed.model.FeedSource(
            id = "feed",
            url = "https://example.com/rss.xml",
            title = "Example",
        )
        val items = listOf(
            FeedItem(id = "saved-unread", feedId = feed.id, title = "Saved unread", url = "https://example.com/1", publishedEpochMs = 3_000L, isBookmarked = true),
            FeedItem(id = "saved-read", feedId = feed.id, title = "Saved read", url = "https://example.com/2", publishedEpochMs = 2_000L, isBookmarked = true, isRead = true),
            FeedItem(id = "plain", feedId = feed.id, title = "Plain", url = "https://example.com/3", publishedEpochMs = 1_000L),
        )

        val saved = filterFeedItems(items, listOf(feed), FeedTimelineFilter.Bookmarked)
        val readLater = filterFeedItems(items, listOf(feed), FeedTimelineFilter.ReadingQueue)

        assertEquals(setOf("saved-unread", "saved-read"), saved.map { it.id }.toSet())
        assertEquals(listOf("saved-unread"), readLater.map { it.id })
    }

    @Test
    fun sameArticleFromTwoFeedsIsCollapsedToOneRow() {
        val feedA = com.simon.harmonichackernews.feed.model.FeedSource(id = "a", url = "https://a.example/rss", title = "A")
        val feedB = com.simon.harmonichackernews.feed.model.FeedSource(id = "b", url = "https://b.example/rss", title = "B")
        val items = listOf(
            FeedItem(id = "a1", feedId = feedA.id, title = "A copy", url = "https://example.com/post/", publishedEpochMs = 1_000L),
            FeedItem(id = "b1", feedId = feedB.id, title = "B copy", url = "https://example.com/post", isRead = true, isBookmarked = true, publishedEpochMs = 2_000L),
        )

        val rows = filterFeedItems(items, listOf(feedA, feedB), FeedTimelineFilter.All)

        assertEquals(1, rows.size)
        assertTrue(rows.single().isRead, "read state of either copy must win")
        assertTrue(rows.single().isBookmarked, "bookmark state of either copy must win")
        assertEquals("B copy", rows.single().title, "newest copy supplies the row content")
    }

    @Test
    fun searchSpansTheWholeLibraryAndRanksTitleMatchesFirst() {
        val feed = com.simon.harmonichackernews.feed.model.FeedSource(id = "f", url = "https://f.example/rss", title = "F")
        val items = listOf(
            FeedItem(id = "body", feedId = feed.id, title = "Unrelated headline", url = "https://example.com/1", contentText = "kotlin coroutines deep dive", publishedEpochMs = 3_000L, isRead = true),
            FeedItem(id = "title", feedId = feed.id, title = "Kotlin release notes", url = "https://example.com/2", publishedEpochMs = 1_000L, isRead = true),
            FeedItem(id = "other", feedId = feed.id, title = "Rust news", url = "https://example.com/3", publishedEpochMs = 4_000L),
        )

        // Saved filter shows only one row, yet search still reaches the unread match.
        val savedSearch = filterFeedItems(items, listOf(feed), FeedTimelineFilter.Bookmarked, query = "rust")
        assertEquals(listOf("other"), savedSearch.map { it.id })

        val ranked = filterFeedItems(items, listOf(feed), FeedTimelineFilter.Unread, query = "kotlin")
        assertEquals(listOf("title", "body"), ranked.map { it.id }, "title hits outrank body hits across filters")
    }

    @Test
    fun snapshotDerivesUnreadAndSavedCountsOnce() = runTest {
        val store = FeedSubscriptionStore(TestKeyValueStore())
        val feedA = store.addFeed("https://a.example/rss")
        val feedB = store.addFeed("https://b.example/rss")
        store.upsertItems(
            listOf(
                FeedItem(id = "a1", feedId = feedA.id, title = "A1", url = "https://example.com/a1", publishedEpochMs = 1_000L),
                FeedItem(id = "a2", feedId = feedA.id, title = "A2", url = "https://example.com/a2", publishedEpochMs = 2_000L, isBookmarked = true),
                FeedItem(id = "b1", feedId = feedB.id, title = "B1", url = "https://example.com/b1", publishedEpochMs = 3_000L, isBookmarked = true),
            ),
        )
        store.markItemRead("a1", true)
        store.markItemBookmarked("a2", true)
        store.markItemRead("a2", true)

        val snapshot = store.state.value
        assertEquals(1, snapshot.unreadCount)
        assertEquals(0, snapshot.unreadByFeed[feedA.id] ?: 0, "fully read feeds are absent from the map")
        assertEquals(1, snapshot.unreadByFeed[feedB.id])
        assertEquals(2, snapshot.savedCount)
        assertEquals(1, snapshot.readingQueueCount, "saved-but-read articles leave the reading queue")
    }

    @Test
    fun transientServerErrorsAreRetried() = runTest {
        var attempts = 0
        val client = HttpClient(MockEngine { _ ->
            attempts++
            if (attempts == 1) {
                respond("busy", HttpStatusCode.ServiceUnavailable)
            } else {
                respond(
                    content = rssPayload,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ETag to listOf("\"v1\"")),
                )
            }
        })
        val store = FeedSubscriptionStore(TestKeyValueStore())
        val feed = store.addFeed("https://example.com/feed.xml")
        val result = FeedSyncCoordinator(client, store, nowEpochMs = { 1_000L }).syncFeed(feed)

        assertTrue(result is FeedSyncResult.Success)
        assertEquals(2, attempts)
    }

    @Test
    fun permanentClientErrorIsNotRetried() = runTest {
        var attempts = 0
        val client = HttpClient(MockEngine { _ ->
            attempts++
            respond("missing", HttpStatusCode.NotFound)
        })
        val store = FeedSubscriptionStore(TestKeyValueStore())
        val feed = store.addFeed("https://example.com/feed.xml")
        val result = FeedSyncCoordinator(client, store, nowEpochMs = { 1_000L }).syncFeed(feed)

        assertTrue(result is FeedSyncResult.Failed)
        assertEquals(1, attempts)
    }

    @Test
    fun feedLinkRequestsAreNormalized() {
        assertEquals(
            "https://example.com/rss.xml",
            FeedSubscribeRequestBus.normalizeRequestUrl("feed://example.com/rss.xml"),
        )
        assertEquals(
            "https://example.com/rss.xml",
            FeedSubscribeRequestBus.normalizeRequestUrl("rss://example.com/rss.xml"),
        )
        assertEquals(
            "https://example.com/rss.xml",
            FeedSubscribeRequestBus.normalizeRequestUrl("https://example.com/rss.xml"),
        )
        assertEquals(null, FeedSubscribeRequestBus.normalizeRequestUrl("   "))

        assertEquals(
            "https://example.com/post",
            FeedSubscribeRequestBus.extractSingleUrl("https://example.com/post Read this later"),
        )
        assertEquals(null, FeedSubscribeRequestBus.extractSingleUrl("no link in this share"))
    }
}