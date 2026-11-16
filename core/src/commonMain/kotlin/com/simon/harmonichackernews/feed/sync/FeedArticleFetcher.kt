package com.simon.harmonichackernews.feed.sync

import com.fleeksoft.ksoup.Ksoup
import com.simon.harmonichackernews.feed.data.FeedSubscriptionStore
import com.simon.harmonichackernews.network.DEFAULT_MAX_BUFFERED_BODY_BYTES
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Fetches and caches a readable article body without replacing feed-provided content on failure. */
class FeedArticleFetcher(
    private val client: HttpClient,
    private val store: FeedSubscriptionStore,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun fetch(itemId: String): Result<Unit> = try {
        // HTML download and Ksoup parsing are blocking: keep them off the caller's (UI) dispatcher.
        withContext(Dispatchers.Default) { fetchBlocking(itemId) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun fetchBlocking(itemId: String): Result<Unit> {
        val item = store.state.value.items.firstOrNull { it.id == itemId }
            ?: error("Article not found")
        if (!item.fullContentHtml.isNullOrBlank()) return Result.success(Unit)

        val html = client.prepareGet(item.url) {
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
        }.execute { response ->
            check(response.status == HttpStatusCode.OK) { "HTTP ${response.status.value}" }
            val channel = response.bodyAsChannel()
            try {
                val buffer = channel.readBuffer(DEFAULT_MAX_BUFFERED_BODY_BYTES.toLong() + 1L)
                val bytes = buffer.readByteArray()
                require(bytes.size <= DEFAULT_MAX_BUFFERED_BODY_BYTES) { "Article exceeds size limit" }
                bytes.decodeToString()
            } finally {
                channel.cancel()
            }
        }

        val document = Ksoup.parse(html, baseUri = item.url)
        document.select("script,style,noscript,nav,header,footer,aside,form").remove()
        val article = document.selectFirst("article") ?: document.selectFirst("main") ?: document.body()
        val text = article?.text()?.trim().orEmpty()
        require(text.length >= MIN_ARTICLE_TEXT_LENGTH) { "No readable article content found" }
        val articleHtml = article.outerHtml()
        check(store.cacheFullArticle(itemId, articleHtml, text, nowEpochMs())) { "Article no longer exists" }
        return Result.success(Unit)
    }

    private companion object {
        const val MIN_ARTICLE_TEXT_LENGTH = 200
    }
}
