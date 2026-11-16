package com.simon.harmonichackernews.feed.data

import com.simon.harmonichackernews.feed.model.FeedItem
import com.simon.harmonichackernews.settings.TestKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The store's public behaviour is unchanged; what changes is where articles live. These tests hold
 * the two properties the migration exists for: an article write is a row write, and a store that
 * starts without a database keeps working exactly as it did.
 */
class FeedSubscriptionStoreRowStorageTest {

    @Test
    fun articleWritesNeverSerializeTheWholeCache() = runTest {
        val keyValue = TestKeyValueStore()
        val database = FeedItemDatabase(":memory:")
        val store = FeedSubscriptionStore(keyValue, itemDatabase = database)
        val feed = store.addFeed("https://example.com/rss")
        store.upsertItems(
            listOf(
                item("a", feed.id, published = 100L),
                item("b", feed.id, published = 200L),
            ),
        )

        assertEquals(2, database.itemCount())
        assertTrue(keyValue.getString(FeedSubscriptionStore.KEY_ITEMS).isNullOrBlank())

        store.markItemRead("a", true)
        store.markItemBookmarked("b", true)

        assertEquals(2, database.itemCount())
        assertTrue(keyValue.getString(FeedSubscriptionStore.KEY_ITEMS).isNullOrBlank())
        // Per-article flags no longer need a second representation beside the rows.
        assertTrue(keyValue.getString(FeedSubscriptionStore.KEY_ITEM_FLAGS).isNullOrBlank())
        assertEquals(1, store.state.value.unreadCount)
        assertEquals(1, store.state.value.savedCount)
    }

    @Test
    fun aNewStoreReadsTheSameRowsBack() = runTest {
        val keyValue = TestKeyValueStore()
        val database = FeedItemDatabase(":memory:")
        val first = FeedSubscriptionStore(keyValue, itemDatabase = database)
        val feed = first.addFeed("https://example.com/rss")
        first.upsertItems(listOf(item("a", feed.id, published = 100L)))
        first.markItemRead("a", true)
        first.markItemBookmarked("a", true)

        val second = FeedSubscriptionStore(keyValue, itemDatabase = database)

        val restored = second.state.value.items.single()
        assertEquals("a", restored.id)
        assertTrue(restored.isRead)
        assertTrue(restored.isBookmarked)
        assertEquals(feed.id, restored.feedId)
        assertEquals(1, second.state.value.savedCount)
        assertEquals(0, second.state.value.unreadCount)
    }

    @Test
    fun legacyJsonCacheIsImportedOnceThenCleared() = runTest {
        val legacyItems = listOf(
            item("old-read", "feed-legacy", published = 100L).copy(isRead = true),
            item("old-unread", "feed-legacy", published = 200L),
        )
        val keyValue = TestKeyValueStore(
            mapOf(
                FeedSubscriptionStore.KEY_ITEMS to Json.encodeToString(legacyItems),
                FeedSubscriptionStore.KEY_ITEM_FLAGS to """{"old-read":3}""",
            ),
        )
        val database = FeedItemDatabase(":memory:")

        val store = FeedSubscriptionStore(keyValue, itemDatabase = database)

        assertEquals(setOf("old-read", "old-unread"), store.state.value.items.map { it.id }.toSet())
        val readItem = store.state.value.items.single { it.id == "old-read" }
        assertTrue(readItem.isRead, "flags from the old layout still apply to imported rows")
        assertTrue(readItem.isBookmarked)
        assertEquals(2, database.itemCount())
        assertTrue(keyValue.getString(FeedSubscriptionStore.KEY_ITEMS).isNullOrBlank())

        // A second start must not re-import or duplicate anything.
        val reopened = FeedSubscriptionStore(keyValue, itemDatabase = database)
        assertEquals(2, reopened.state.value.items.size)
    }

    @Test
    fun indexedSearchCoversStoredArticlesOnlyWhenTheDatabaseExists() = runTest {
        val keyValue = TestKeyValueStore()
        val database = FeedItemDatabase(":memory:")
        val store = FeedSubscriptionStore(keyValue, itemDatabase = database)
        val feed = store.addFeed("https://example.com/rss")
        store.upsertItems(
            listOf(
                item("body", feed.id, title = "Weekly roundup", published = 300L)
                    .copy(contentText = "notes about coroutines"),
                item("title", feed.id, title = "Coroutines in practice", published = 100L),
            ),
        )

        assertEquals(listOf("title", "body"), store.searchLocal("coroutine").map { it.id })
        assertTrue(store.usesRowStorage)

        val legacyStore = FeedSubscriptionStore(TestKeyValueStore())
        assertTrue(legacyStore.searchLocal("coroutine").isEmpty())
    }

    @Test
    fun rowStorageAndTheInMemoryListAgreeOnRetention() = runTest {
        val database = FeedItemDatabase(":memory:")
        val store = FeedSubscriptionStore(
            TestKeyValueStore(),
            maxItemsRetained = 3,
            itemDatabase = database,
        )
        val feed = store.addFeed("https://example.com/rss")
        store.upsertItems((1..4).map { index -> item("i$index", feed.id, published = index * 100L) })
        store.markItemRead("i2", true)

        val retained = store.state.value.items.map { it.id }.sorted()
        assertEquals(3, retained.size, "retention bound applies to the new rows too")
        assertEquals(retained, database.loadItems(10).map { it.id }.sorted())
        assertEquals(2, store.state.value.unreadByFeed[feed.id] ?: 0, "the read flag reaches the counts")
    }

    @Test
    fun fullArticleFetchRewritesOneRowAndSurvivesRestart() = runTest {
        val keyValue = TestKeyValueStore()
        val database = FeedItemDatabase(":memory:")
        val store = FeedSubscriptionStore(keyValue, itemDatabase = database)
        val feed = store.addFeed("https://example.com/rss")
        store.upsertItems(
            listOf(
                item("a", feed.id, published = 100L).copy(contentText = "feed teaser"),
                item("b", feed.id, published = 200L),
            ),
        )

        assertTrue(store.cacheFullArticle("a", "<p>full</p>", "full body", fetchedEpochMs = 7L))

        val reopened = FeedSubscriptionStore(keyValue, itemDatabase = database)
        val stored = reopened.state.value.items.single { it.id == "a" }
        assertEquals("<p>full</p>", stored.fullContentHtml)
        assertEquals("full body", stored.contentText)
        assertEquals(7L, stored.fullContentFetchedEpochMs)
    }

    @Test
    fun removalAndMarkAllReadReachTheRows() = runTest {
        val database = FeedItemDatabase(":memory:")
        val store = FeedSubscriptionStore(TestKeyValueStore(), itemDatabase = database)
        val feed = store.addFeed("https://example.com/rss")
        val otherFeed = store.addFeed("https://other.example.com/rss")
        store.upsertItems(
            listOf(
                item("a", feed.id, published = 100L),
                item("b", feed.id, published = 200L),
                item("c", otherFeed.id, published = 300L),
            ),
        )

        assertEquals(2, store.markFeedRead(feed.id))
        assertEquals(2, database.loadItems(10).count { it.isRead })

        assertTrue(store.removeFeed(feed.id))
        assertEquals(listOf("c"), database.loadItems(10).map { it.id })
        assertEquals(0, store.state.value.unreadByFeed[feed.id] ?: 0)
    }

    @Test
    fun jsonFallbackStillPersistsTheWholeCache() = runTest {
        val keyValue = TestKeyValueStore()
        val store = FeedSubscriptionStore(keyValue)
        val feed = store.addFeed("https://example.com/rss")
        store.upsertItems(listOf(item("a", feed.id, published = 100L)))
        store.markItemRead("a", true)

        val rawItems = store.state.value.let { keyValue.getString(FeedSubscriptionStore.KEY_ITEMS) }
        assertNotNull(rawItems)
        assertTrue(rawItems.contains("\"a\""))
        assertTrue(!store.usesRowStorage)
        assertTrue(keyValue.getString(FeedSubscriptionStore.KEY_ITEM_FLAGS).orEmpty().contains("\"a\""))
    }

    private fun item(
        id: String,
        feedId: String,
        title: String = "article $id",
        published: Long,
    ) = FeedItem(
        id = id,
        feedId = feedId,
        title = title,
        url = "https://example.com/$id",
        publishedEpochMs = published,
    )
}