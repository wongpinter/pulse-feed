package com.simon.harmonichackernews.feed.data

import com.simon.harmonichackernews.feed.model.FeedItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedItemDatabaseTest {

    private val database = FeedItemDatabase(":memory:")

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun articlesRoundTripWithEveryNullableField() {
        database.upsertItems(
            listOf(
                item("a", title = "Kotlin news", author = null, published = 300L, topics = listOf("Tech", "News")),
                item("b", title = "Rust news", author = "Alice", published = 200L),
            ),
        )

        val loaded = database.loadItems(limit = 10)
        assertEquals(listOf("a", "b"), loaded.map { it.id })
        assertEquals("Kotlin news", loaded[0].title)
        assertEquals(listOf("Tech", "News"), loaded[0].topics)
        assertNull(loaded[0].author)
        assertNull(loaded[0].fullContentHtml)
        assertNull(loaded[0].fullContentFetchedEpochMs)
        assertEquals("Alice", loaded[1].author)
    }

    @Test
    fun upsertKeepsOneRowPerId() {
        database.upsertItems(listOf(item("a", title = "first", published = 100L)))
        database.upsertItems(listOf(item("a", title = "second", published = 100L)))
        assertEquals(1, database.itemCount())
        assertEquals("second", database.loadItems(10).single().title)
    }

    @Test
    fun flagsUpdateInPlace() {
        database.upsertItems(listOf(item("a", published = 100L), item("b", published = 200L), item("c", published = 300L)))

        database.setRead("a", true)
        database.setBookmarked("a", true)
        database.setFeedRead("feed-1")

        val loaded = database.loadItems(10).associateBy { it.id }
        assertTrue(loaded.getValue("a").isRead)
        assertTrue(loaded.getValue("a").isBookmarked)
        assertTrue(loaded.getValue("b").isRead)
        assertTrue(loaded.getValue("c").isRead)
    }

    @Test
    fun fetchedFullArticleReplacesBody() {
        database.upsertItems(listOf(item("a", published = 100L, contentText = "feed teaser")))
        database.setFullArticle("a", html = "<p>full body</p>", text = "full body", fetchedEpochMs = 42L)

        val stored = database.loadItems(10).single()
        assertEquals("<p>full body</p>", stored.fullContentHtml)
        assertEquals("full body", stored.contentText)
        assertEquals(42L, stored.fullContentFetchedEpochMs)
    }

    @Test
    fun retentionDropsReadArticlesBeforeUnreadOnesAndKeepsBookmarks() {
        val items = (1..6).map { index ->
            item("i$index", title = "article $index", published = index * 100L)
        }
        database.upsertItems(items)
        database.setRead("i1", true)
        database.setRead("i2", true)
        database.setRead("i3", true)
        database.setBookmarked("i1", true)

        database.trimToRetention(maxItems = 4)

        val remaining = database.loadItems(10).map { it.id }.sorted()
        // 1 is bookmarked so it survives even though it is read; 2 and 3 are the next read ones.
        assertEquals(listOf("i1", "i4", "i5", "i6"), remaining)
    }

    @Test
    fun retentionNeverDropsBookmarksToMakeRoom() {
        database.upsertItems(
            (1..5).map { index -> item("b$index", published = index * 10L) },
        )
        (1..5).forEach { database.setBookmarked("b$it", true) }

        database.trimToRetention(maxItems = 2)

        assertEquals(5, database.itemCount())
    }

    @Test
    fun searchRanksTitlePrefixAboveBodyMatches() {
        database.upsertItems(
            listOf(
                item("body", title = "Weekly roundup", published = 300L, contentText = "all about kotlin coroutines"),
                item("title", title = "Kotlin coroutines explained", published = 100L),
                item("author", title = "Something else", author = "Kotlin Weekly", published = 200L),
                item("unrelated", title = "Gardening", published = 400L),
            ),
        )
        assertTrue(database.fullTextAvailable)

        val ranked = database.search("kotlin", limit = 10).map { it.id }
        assertEquals(listOf("title", "author", "body"), ranked)
    }

    @Test
    fun searchHandlesPartialWordsAndPunctuation() {
        database.upsertItems(
            listOf(
                item("a", title = "Coroutines in Kotlin", published = 100L),
                item("b", title = "Unrelated", published = 200L, contentText = "quotes \" and ' and * stars"),
            ),
        )

        assertEquals(listOf("a"), database.search("corou", limit = 10).map { it.id })
        assertEquals(listOf("b"), database.search("stars", limit = 10).map { it.id })
        // Operators and quotes must not reach FTS5 as syntax.
        assertTrue(database.search("\"*'(", limit = 10).isEmpty())
        assertTrue(database.search("   ", limit = 10).isEmpty())
    }

    @Test
    fun searchRespectsLimitAndRecencyTieBreak() {
        database.upsertItems(
            (1..5).map { index -> item("i$index", title = "match $index", published = index * 100L) },
        )
        val limited = database.search("match", limit = 2).map { it.id }
        assertEquals(listOf("i5", "i4"), limited)
    }

    @Test
    fun feedDeletionRemovesOnlyThatFeedsArticles() {
        database.upsertItems(
            listOf(
                item("a", published = 100L, feedId = "feed-1"),
                item("b", published = 200L, feedId = "feed-2"),
            ),
        )
        database.deleteFeedItems("feed-1")

        assertEquals(listOf("b"), database.loadItems(10).map { it.id })
        assertFalse(database.search("article", 10).any { it.id == "a" })
    }

    @Test
    fun fileDatabaseReopensWithContentAndWal() {
        val file = java.io.File.createTempFile("pulse-feed-items", ".db").apply { delete() }
        try {
            val first = FeedItemDatabase(file.absolutePath)
            first.upsertItems(listOf(item("a", title = "persisted", published = 100L)))
            first.setRead("a", true)
            first.close()

            val second = FeedItemDatabase(file.absolutePath)
            val stored = second.loadItems(10).single()
            assertEquals("persisted", stored.title)
            assertTrue(stored.isRead)
            second.close()
        } finally {
            listOf("", "-wal", "-shm").forEach { suffix ->
                java.io.File(file.absolutePath + suffix).delete()
            }
        }
    }

    private fun item(
        id: String,
        title: String = "article $id",
        author: String? = null,
        published: Long,
        feedId: String = "feed-1",
        contentText: String? = null,
        topics: List<String> = emptyList(),
    ) = FeedItem(
        id = id,
        feedId = feedId,
        title = title,
        url = "https://example.com/$id",
        author = author,
        publishedEpochMs = published,
        contentText = contentText,
        topics = topics,
    )
}