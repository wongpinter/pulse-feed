package com.simon.harmonichackernews.feed.refresh

import com.simon.harmonichackernews.network.StableHash
import com.simon.harmonichackernews.settings.KeyValueStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-feed alert record.
 *
 * [lastNotifiedEpochMs] is the checkpoint that keeps a refresh from re-announcing articles that
 * were already seen. A feed without a record is baselined at the moment it is first observed, so
 * subscribing (or upgrading with an existing library) never floods the notification shade.
 */
@Serializable
data class FeedAlertPreference(
    val enabled: Boolean = true,
    val lastNotifiedEpochMs: Long = 0L,
)

/** Portable per-feed alert preferences and notification checkpoints. */
class FeedAlertPreferencesStore(
    private val store: KeyValueStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(load())

    val state: StateFlow<Map<String, FeedAlertPreference>> = mutableState.asStateFlow()

    fun preference(feedId: String): FeedAlertPreference =
        mutableState.value[feedId] ?: FeedAlertPreference()

    val isAnyEnabled: Boolean get() = mutableState.value.values.any { it.enabled }

    suspend fun setEnabled(feedId: String, enabled: Boolean): Boolean = mutate { current ->
        val existing = current[feedId] ?: FeedAlertPreference()
        if (existing.enabled == enabled) return@mutate null
        current + (feedId to existing.copy(enabled = enabled))
    }

    /** Records the newest article announced for each feed so it is never announced twice. */
    suspend fun recordNotified(checkpoints: Map<String, Long>): Boolean {
        if (checkpoints.isEmpty()) return false
        return mutate { current ->
            val next = current.toMutableMap()
            var changed = false
            for ((feedId, epochMs) in checkpoints) {
                val existing = next[feedId] ?: FeedAlertPreference()
                val checkpoint = maxOf(existing.lastNotifiedEpochMs, epochMs)
                if (checkpoint != existing.lastNotifiedEpochMs || feedId !in next) {
                    next[feedId] = existing.copy(lastNotifiedEpochMs = checkpoint)
                    changed = true
                }
            }
            if (changed) next else null
        }
    }

    /** Baselines feeds that have no record yet, so their existing backlog is never announced. */
    suspend fun baseline(feedIds: Collection<String>, epochMs: Long): Boolean {
        val missing = feedIds.filter { it.isNotBlank() && mutableState.value[it] == null }
        if (missing.isEmpty()) return false
        return mutate { current ->
            val next = current.toMutableMap()
            missing.forEach { feedId ->
                next[feedId] = (next[feedId] ?: FeedAlertPreference())
                    .copy(lastNotifiedEpochMs = epochMs)
            }
            next
        }
    }

    suspend fun forget(feedIds: Collection<String>): Boolean {
        if (feedIds.isEmpty()) return false
        return mutate { current ->
            val next = current.filterKeys { it !in feedIds }
            if (next.size == current.size) null else next
        }
    }

    private suspend fun mutate(block: (Map<String, FeedAlertPreference>) -> Map<String, FeedAlertPreference>?): Boolean =
        withContext(dispatcher) {
            mutex.withLock {
                val next = block(mutableState.value) ?: return@withLock false
                store.putString(KEY_ALERT_PREFERENCES, json.encodeToString(next))
                mutableState.value = next
                true
            }
        }

    private fun load(): Map<String, FeedAlertPreference> {
        val raw = store.getString(KEY_ALERT_PREFERENCES)
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, FeedAlertPreference>>(raw) }
            .getOrDefault(emptyMap())
    }

    companion object {
        const val KEY_ALERT_PREFERENCES = "harmonic_feed_alert_preferences"
    }
}

/**
 * Common notification copy, grouping and ordering policy for feed articles.
 *
 * Only unread articles published after a feed's checkpoint qualify, so a refresh that merely
 * re-parses the same feed never re-announces it.
 */
object FeedNotificationPolicy {
    const val MAX_INDIVIDUAL_NOTIFICATIONS = 4
    const val SUMMARY_NOTIFICATION_ID = 98_374

    private const val MAX_BODY_CHARS = 180

    fun plan(
        items: List<com.simon.harmonichackernews.feed.model.FeedItem>,
        feeds: List<com.simon.harmonichackernews.feed.model.FeedSource>,
        preferences: Map<String, FeedAlertPreference>,
    ): FeedNotificationBatch {
        if (items.isEmpty() || feeds.isEmpty()) return FeedNotificationBatch()
        val feedsById = feeds.associateBy { it.id }
        val candidates = items.filter { item ->
            val preference = preferences[item.feedId] ?: FeedAlertPreference()
            !item.isRead &&
                preference.enabled &&
                item.publishedEpochMs > 0L &&
                item.publishedEpochMs > preference.lastNotifiedEpochMs &&
                feedsById.containsKey(item.feedId)
        }
        if (candidates.isEmpty()) return FeedNotificationBatch()

        val newestByFeed = candidates
            .groupBy { it.feedId }
            .mapValues { (_, feedItems) -> feedItems.maxByOrNull { it.publishedEpochMs }!! }
            .toList()
            .sortedByDescending { (_, item) -> item.publishedEpochMs }

        val notifications = newestByFeed.take(MAX_INDIVIDUAL_NOTIFICATIONS).map { (feedId, item) ->
            val feedTitle = feedsById[feedId]?.title?.takeIf { it.isNotBlank() } ?: "Feed"
            val additional = candidates.count { it.feedId == feedId } - 1
            val body = if (additional > 0) {
                "${item.title.trim().take(MAX_BODY_CHARS)} (+$additional more)"
            } else {
                item.title.trim().take(MAX_BODY_CHARS)
            }
            FeedNotificationPayload(
                id = notificationId(feedId),
                title = feedTitle,
                body = body,
                feedId = feedId,
                itemId = item.id,
            )
        }

        if (notifications.size == 1) return FeedNotificationBatch(notifications)
        val newest = newestByFeed.first().second
        val summary = FeedNotificationPayload(
            id = SUMMARY_NOTIFICATION_ID,
            title = "${candidates.size} new articles",
            body = "From ${newestByFeed.size} feeds",
            feedId = newest.feedId,
            itemId = newest.id,
        )
        return FeedNotificationBatch(notifications, summary)
    }

    /** Checkpoints to persist after delivery: the newest announced article per feed. */
    fun checkpoints(
        items: List<com.simon.harmonichackernews.feed.model.FeedItem>,
        preferences: Map<String, FeedAlertPreference>,
    ): Map<String, Long> = items
        .filter { item ->
            val preference = preferences[item.feedId] ?: FeedAlertPreference()
            !item.isRead && preference.enabled && item.publishedEpochMs > preference.lastNotifiedEpochMs
        }
        .groupBy { it.feedId }
        .mapValues { (_, feedItems) -> feedItems.maxOf { it.publishedEpochMs } }

    fun notificationId(feedId: String): Int {
        val hex = StableHash.sha256Hex(feedId).take(8)
        val value = hex.toLongOrNull(16) ?: 0L
        return (value % 90_000L).toInt() + 1_000
    }
}