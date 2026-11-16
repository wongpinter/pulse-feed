package com.simon.harmonichackernews.feed

import com.simon.harmonichackernews.feed.data.FeedSubscriptionStore
import com.simon.harmonichackernews.feed.model.FeedItem
import com.simon.harmonichackernews.feed.sync.FeedArticleFetcher
import com.simon.harmonichackernews.settings.TestKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedArticleFetcherTest {
    @Test
    fun fetchCachesArticleAndRefreshPreservesCache() = runTest {
        val store = FeedSubscriptionStore(TestKeyValueStore())
        store.addFeed("https://example.com/feed")
        store.upsertItems(listOf(FeedItem("item", store.state.value.feeds.single().id, "Title", "https://example.com/article", contentText = "short")))
        val body = "<html><body><article><h1>Title</h1><p>${"Readable article content. ".repeat(20)}</p></article></body></html>"
        val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK) })
        val fetcher = FeedArticleFetcher(client, store) { 123L }

        assertTrue(fetcher.fetch("item").isSuccess)
        val cached = store.state.value.items.single()
        assertTrue(cached.fullContentHtml!!.contains("article"))
        assertTrue(cached.contentText!!.length > 200)
        assertEquals(123L, cached.fullContentFetchedEpochMs)

        store.upsertItems(listOf(cached.copy(contentText = "new feed excerpt", fullContentHtml = null)))
        assertEquals(cached.fullContentHtml, store.state.value.items.single().fullContentHtml)
        assertEquals(cached.contentText, store.state.value.items.single().contentText)
    }

    @Test
    fun failedFetchDoesNotReplaceFeedExcerpt() = runTest {
        val store = FeedSubscriptionStore(TestKeyValueStore())
        store.addFeed("https://example.com/feed")
        store.upsertItems(listOf(FeedItem("item", store.state.value.feeds.single().id, "Title", "https://example.com/article", contentText = "excerpt")))
        val client = HttpClient(MockEngine { respond("<html><body>Too short</body></html>", HttpStatusCode.OK) })
        val result = FeedArticleFetcher(client, store).fetch("item")

        assertFalse(result.isSuccess)
        assertEquals("excerpt", store.state.value.items.single().contentText)
        assertEquals(null, store.state.value.items.single().fullContentHtml)
    }
}
