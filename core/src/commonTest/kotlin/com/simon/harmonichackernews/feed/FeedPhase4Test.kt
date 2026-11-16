package com.simon.harmonichackernews.feed

import com.simon.harmonichackernews.feed.data.FeedSubscriptionStore
import com.simon.harmonichackernews.feed.sync.FeedSyncCoordinator
import com.simon.harmonichackernews.feed.sync.FeedSyncResult
import com.simon.harmonichackernews.settings.TestKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedPhase4Test {

    @Test
    fun subscribeWithAutoDiscoveryFetchesWebsiteFindsRssAndSyncs() = runTest {
        val store = FeedSubscriptionStore(TestKeyValueStore())

        val htmlHomePage = """
            <!doctype html>
            <html>
              <head>
                <title>Kotlin Blog</title>
                <link rel="alternate" type="application/rss+xml" title="Kotlin Official RSS" href="/feed.xml" />
              </head>
              <body>Welcome</body>
            </html>
        """.trimIndent()

        val rssContent = """
            <rss version="2.0">
              <channel>
                <title>Kotlin Official RSS</title>
                <link>https://kotlin.example.com</link>
                <item>
                  <title>Multiplatform Coroutines</title>
                  <link>https://kotlin.example.com/posts/coroutines</link>
                  <guid>coroutines-1</guid>
                  <pubDate>Mon, 27 Oct 2026 12:00:00 GMT</pubDate>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val client = HttpClient(MockEngine { request ->
            when (request.url.toString()) {
                "https://kotlin.example.com" -> respond(htmlHomePage, HttpStatusCode.OK)
                "https://kotlin.example.com/feed.xml" -> respond(rssContent, HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        })

        val coordinator = FeedSyncCoordinator(client, store, nowEpochMs = { 123_456L })
        val result = coordinator.subscribeWithAutoDiscovery("kotlin.example.com")

        assertTrue(result is FeedSyncResult.Success)
        assertEquals(1, result.itemsCount)
        assertEquals(1, store.state.value.feeds.size)
        assertEquals("https://kotlin.example.com/feed.xml", store.state.value.feeds.first().url)
        assertEquals("Kotlin Official RSS", store.state.value.feeds.first().title)
        assertEquals(1, store.state.value.items.size)
        assertEquals("Multiplatform Coroutines", store.state.value.items.first().title)
    }
}
