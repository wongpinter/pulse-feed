package com.simon.harmonichackernews.feed.sync

import com.simon.harmonichackernews.feed.classifier.FeedTopicClassifier
import com.simon.harmonichackernews.feed.data.FeedSubscriptionStore
import com.simon.harmonichackernews.feed.model.FeedItem
import com.simon.harmonichackernews.feed.model.FeedSource
import com.simon.harmonichackernews.feed.parser.FeedAutoDiscovery
import com.simon.harmonichackernews.feed.parser.FeedParser
import com.simon.harmonichackernews.feed.parser.UniversalFeedParser
import com.simon.harmonichackernews.network.DEFAULT_MAX_BUFFERED_BODY_BYTES
import com.simon.harmonichackernews.network.StableHash
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlin.time.Clock

sealed interface FeedSyncResult {
    data class Success(val feedId: String, val itemsCount: Int) : FeedSyncResult
    data class NotModified(val feedId: String) : FeedSyncResult
    data class Failed(val feedId: String, val error: String) : FeedSyncResult
}

data class SyncBatchSummary(
    val results: List<FeedSyncResult>,
    val totalNewItems: Int,
    val successCount: Int,
    val notModifiedCount: Int,
    val failedCount: Int,
)

class FeedSyncCoordinator(
    private val clientProvider: suspend () -> HttpClient,
    private val store: FeedSubscriptionStore,
    private val parser: FeedParser = UniversalFeedParser(),
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    maxConcurrency: Int = 4,
) {
    constructor(
        client: HttpClient,
        store: FeedSubscriptionStore,
        parser: FeedParser = UniversalFeedParser(),
        nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        maxConcurrency: Int = 4,
    ) : this({ client }, store, parser, nowEpochMs, maxConcurrency)

    private val semaphore = Semaphore(maxConcurrency)

    suspend fun syncAll(): SyncBatchSummary = coroutineScope {
        val feeds = store.state.value.feeds
        val results = feeds.map { feed ->
            async {
                semaphore.withPermit { syncFeed(feed) }
            }
        }.awaitAll()

        var totalItems = 0
        var success = 0
        var notModified = 0
        var failed = 0

        for (result in results) {
            when (result) {
                is FeedSyncResult.Success -> {
                    success++
                    totalItems += result.itemsCount
                }
                is FeedSyncResult.NotModified -> notModified++
                is FeedSyncResult.Failed -> failed++
            }
        }

        SyncBatchSummary(
            results = results,
            totalNewItems = totalItems,
            successCount = success,
            notModifiedCount = notModified,
            failedCount = failed,
        )
    }

    suspend fun syncFeed(feed: FeedSource): FeedSyncResult = withContext(Dispatchers.Default) {
        var attempt = 0
        while (true) {
            val outcome = try {
                attemptSync(feed, attempt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                SyncOutcome.Retry(
                    delayMs = backoffDelayMs(attempt),
                    reason = e.message ?: (e::class.simpleName ?: "Sync failed"),
                )
            }
            when (outcome) {
                is SyncOutcome.Done -> return@withContext outcome.result
                is SyncOutcome.Retry -> {
                    if (attempt >= MAX_SYNC_ATTEMPTS - 1) {
                        return@withContext FeedSyncResult.Failed(feed.id, outcome.reason)
                    }
                    delay(outcome.delayMs)
                    attempt++
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        FeedSyncResult.Failed(feed.id, "Sync failed")
    }

    private suspend fun attemptSync(feed: FeedSource, attempt: Int): SyncOutcome {
        val httpClient = clientProvider()
        val now = nowEpochMs()

        return httpClient.prepareGet(feed.url) {
            header(
                HttpHeaders.Accept,
                "application/rss+xml, application/atom+xml, application/xml, text/xml, application/feed+json, application/json, */*",
            )
            feed.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            feed.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
        }.execute { response ->
            when (val status = response.status) {
                HttpStatusCode.NotModified -> {
                    store.updateFeed(feed.copy(lastFetchedEpochMs = now))
                    SyncOutcome.Done(FeedSyncResult.NotModified(feed.id))
                }
                HttpStatusCode.OK -> {
                    val channel = response.bodyAsChannel()
                    val body = try {
                        val buffer = channel.readBuffer(DEFAULT_MAX_BUFFERED_BODY_BYTES.toLong() + 1L)
                        val bytes = buffer.readByteArray()
                        bytes.decodeToString()
                    } finally {
                        channel.cancel()
                    }

                    val parsed = parser.parse(body, feed.url)
                    val etag = response.headers[HttpHeaders.ETag] ?: feed.etag
                    val lastModified = response.headers[HttpHeaders.LastModified] ?: feed.lastModified

                    val items = parsed.items.map { parsedItem ->
                        val itemId = StableHash.sha256Hex(feed.id + ":" + (parsedItem.guid ?: parsedItem.url)).take(16)
                        val classifiedTopics = FeedTopicClassifier.classify(
                            title = parsedItem.title,
                            content = parsedItem.contentText,
                            existingTags = parsedItem.topics,
                        )
                        FeedItem(
                            id = itemId,
                            feedId = feed.id,
                            title = parsedItem.title,
                            url = parsedItem.url,
                            author = parsedItem.author,
                            publishedEpochMs = if (parsedItem.publishedEpochMs > 0L) parsedItem.publishedEpochMs else now,
                            contentHtml = parsedItem.contentHtml,
                            contentText = parsedItem.contentText,
                            leadImageUrl = parsedItem.leadImageUrl,
                            topics = classifiedTopics,
                        )
                    }

                    val updatedFeed = feed.copy(
                        title = if (feed.title == feed.url && parsed.title.isNotBlank()) parsed.title else feed.title,
                        siteUrl = feed.siteUrl ?: parsed.siteUrl,
                        description = feed.description ?: parsed.description,
                        iconUrl = feed.iconUrl ?: parsed.iconUrl,
                        etag = etag,
                        lastModified = lastModified,
                        lastFetchedEpochMs = now,
                    )

                    store.updateFeed(updatedFeed)
                    val addedCount = store.upsertItems(items)
                    SyncOutcome.Done(FeedSyncResult.Success(feed.id, addedCount))
                }
                else -> {
                    val description = "HTTP ${status.value}: ${status.description}"
                    if (shouldRetry(status)) {
                        SyncOutcome.Retry(
                            delayMs = retryAfterDelayMs(response.headers[HttpHeaders.RetryAfter], attempt = attempt),
                            reason = description,
                        )
                    } else {
                        SyncOutcome.Done(FeedSyncResult.Failed(feed.id, description))
                    }
                }
            }
        }
    }

    private sealed interface SyncOutcome {
        data class Done(val result: FeedSyncResult) : SyncOutcome
        data class Retry(val delayMs: Long, val reason: String) : SyncOutcome
    }

    private fun shouldRetry(status: HttpStatusCode): Boolean =
        status.value == 429 || status.value in 500..599

    private fun retryAfterDelayMs(headerValue: String?, attempt: Int): Long {
        val seconds = headerValue
            ?.trim()
            ?.substringBefore(' ')
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
        return seconds?.coerceAtMost(MAX_RETRY_AFTER_SECONDS)?.times(1_000L)
            ?: backoffDelayMs(attempt)
    }

    private fun backoffDelayMs(attempt: Int): Long =
        (BASE_RETRY_DELAY_MS shl attempt.coerceIn(0, 4)).coerceAtMost(MAX_RETRY_DELAY_MS)

    /**
     * Subscribes to a URL directly if it is a valid feed, or automatically discovers
     * embedded RSS/Atom/JSON feed links if the URL points to a standard HTML webpage.
     */
    suspend fun subscribeWithAutoDiscovery(
        rawUrl: String,
        customTitle: String? = null,
        folderId: String? = null,
    ): FeedSyncResult {
        val candidateUrl = rawUrl.trim().let {
            if (!it.startsWith("http://") && !it.startsWith("https://")) "https://$it" else it
        }
        return try {
            val httpClient = clientProvider()
            val body = httpClient.prepareGet(candidateUrl) {
                header(
                    HttpHeaders.Accept,
                    "application/rss+xml, application/atom+xml, application/xml, text/xml, application/feed+json, application/json, text/html, */*",
                )
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    throw IllegalStateException("HTTP ${response.status.value}: ${response.status.description}")
                }
                val channel = response.bodyAsChannel()
                try {
                    channel.readBuffer(DEFAULT_MAX_BUFFERED_BODY_BYTES.toLong() + 1L)
                        .readByteArray()
                        .decodeToString()
                } finally {
                    channel.cancel()
                }
            }

            // 1. Check if candidateUrl is directly a feed
            val parsedDirect = runCatching { parser.parse(body, candidateUrl) }.getOrNull()
            if (parsedDirect != null) {
                val added = store.addFeed(
                    url = candidateUrl,
                    title = customTitle ?: parsedDirect.title.ifBlank { null },
                    siteUrl = parsedDirect.siteUrl,
                    folderId = folderId,
                )
                return syncFeed(added)
            }

            // 2. Otherwise inspect HTML for alternate <link> feed references
            val discovered = FeedAutoDiscovery.discoverFromHtml(body, candidateUrl)
            val firstMatch = discovered.firstOrNull()
                ?: return FeedSyncResult.Failed(candidateUrl, "No RSS, Atom, or JSON feed found at $candidateUrl")

            val added = store.addFeed(
                url = firstMatch.feedUrl,
                title = customTitle ?: firstMatch.title.ifBlank { null },
                siteUrl = candidateUrl,
                folderId = folderId,
            )
            syncFeed(added)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            FeedSyncResult.Failed(candidateUrl, e.message ?: (e::class.simpleName ?: "Discovery failed"))
        }
    }

    private companion object {
        const val MAX_SYNC_ATTEMPTS = 3
        const val BASE_RETRY_DELAY_MS = 500L
        const val MAX_RETRY_DELAY_MS = 4_000L
        const val MAX_RETRY_AFTER_SECONDS = 30L
    }
}
