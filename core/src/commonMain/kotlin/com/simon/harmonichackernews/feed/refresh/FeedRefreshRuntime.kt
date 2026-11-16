package com.simon.harmonichackernews.feed.refresh

import com.simon.harmonichackernews.settings.KeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Host hooks for the operating system's scheduler and notification surface.
 *
 * Hosts that cannot schedule work in the background still expose the in-app refresh timer, so the
 * portable runtime keeps a single code path for refresh and alert presentation.
 */
interface FeedRefreshPlatform {
    val supported: Boolean get() = true
    val notificationsSupported: Boolean get() = true

    fun prepareNotifications()

    fun scheduleRefresh(schedule: FeedRefreshSchedule)

    fun cancelRefresh()

    /** Completes after delivery; implementations must keep blocking platform calls off the caller. */
    suspend fun publish(batch: FeedNotificationBatch)

    companion object {
        val Unavailable: FeedRefreshPlatform = object : FeedRefreshPlatform {
            override val supported = false
            override val notificationsSupported = false
            override fun prepareNotifications() = Unit
            override fun scheduleRefresh(schedule: FeedRefreshSchedule) = Unit
            override fun cancelRefresh() = Unit
            override suspend fun publish(batch: FeedNotificationBatch) = Unit
        }
    }
}

object FeedRefreshKeys {
    const val INTERVAL_MINUTES = "harmonic_feed_refresh_interval_minutes"
    const val NOTIFICATIONS_ENABLED = "harmonic_feed_notifications_enabled"
    const val QUIET_HOURS_ENABLED = "harmonic_feed_quiet_hours_enabled"
    const val QUIET_HOURS_FROM_HOUR = "harmonic_feed_quiet_hours_from_hour"
    const val QUIET_HOURS_FROM_MINUTE = "harmonic_feed_quiet_hours_from_minute"
    const val QUIET_HOURS_TO_HOUR = "harmonic_feed_quiet_hours_to_hour"
    const val QUIET_HOURS_TO_MINUTE = "harmonic_feed_quiet_hours_to_minute"
}

/**
 * Portable feed refresh and alert workflow shared by every host.
 *
 * A refresh runs the sync batch, then announces only genuinely new unread articles. The first time
 * a feed is seen its checkpoint is baselined so an existing library never produces a notification
 * flood, and quiet hours postpone delivery without losing the checkpoint.
 */
class FeedRefreshRuntime(
    private val store: com.simon.harmonichackernews.feed.data.FeedSubscriptionStore,
    private val sync: com.simon.harmonichackernews.feed.sync.FeedSyncCoordinator,
    private val alerts: FeedAlertPreferencesStore,
    private val settings: KeyValueStore,
    private val platform: FeedRefreshPlatform = FeedRefreshPlatform.Unavailable,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val currentMinutesFromMidnight: () -> Int = { 12 * 60 },
) {
    private val refreshMutex = Mutex()

    val backgroundSupported: Boolean get() = platform.supported
    val notificationsSupported: Boolean get() = platform.notificationsSupported
    val alertPreferences: FeedAlertPreferencesStore get() = alerts

    var refreshInterval: FeedRefreshInterval
        get() = FeedRefreshInterval.fromMinutes(
            settings.getInt(FeedRefreshKeys.INTERVAL_MINUTES, FeedRefreshInterval.DEFAULT.minutes),
        )
        set(value) {
            if (refreshInterval == value) return
            settings.putInt(FeedRefreshKeys.INTERVAL_MINUTES, value.minutes)
            reschedule()
        }

    var notificationsEnabled: Boolean
        get() = settings.getBoolean(FeedRefreshKeys.NOTIFICATIONS_ENABLED, false)
        set(value) {
            if (notificationsEnabled == value) return
            settings.putBoolean(FeedRefreshKeys.NOTIFICATIONS_ENABLED, value)
            if (value && notificationsSupported) platform.prepareNotifications()
            reschedule()
        }

    var quietHours: FeedQuietHours
        get() = FeedQuietHours(
            enabled = settings.getBoolean(FeedRefreshKeys.QUIET_HOURS_ENABLED, false),
            window = com.simon.harmonichackernews.settings.NighttimeSchedule(
                fromHour = settings.getInt(FeedRefreshKeys.QUIET_HOURS_FROM_HOUR, 22),
                fromMinute = settings.getInt(FeedRefreshKeys.QUIET_HOURS_FROM_MINUTE, 0),
                toHour = settings.getInt(FeedRefreshKeys.QUIET_HOURS_TO_HOUR, 7),
                toMinute = settings.getInt(FeedRefreshKeys.QUIET_HOURS_TO_MINUTE, 0),
            ),
        )
        set(value) {
            settings.putBoolean(FeedRefreshKeys.QUIET_HOURS_ENABLED, value.enabled)
            settings.putInt(FeedRefreshKeys.QUIET_HOURS_FROM_HOUR, value.window.fromHour)
            settings.putInt(FeedRefreshKeys.QUIET_HOURS_FROM_MINUTE, value.window.fromMinute)
            settings.putInt(FeedRefreshKeys.QUIET_HOURS_TO_HOUR, value.window.toHour)
            settings.putInt(FeedRefreshKeys.QUIET_HOURS_TO_MINUTE, value.window.toMinute)
            reschedule()
        }

    /** The schedule hosts should register, or null when background refresh is off. */
    fun schedule(): FeedRefreshSchedule? {
        val interval = refreshInterval
        if (!interval.enabled || !backgroundSupported) return null
        val quiet = quietHours
        val minutes = currentMinutesFromMidnight()
        val delay = if (quiet.contains(minutes)) {
            quiet.millisUntilEnd(minutes).coerceAtLeast(FeedRefreshInterval.FIFTEEN.intervalMillis)
        } else {
            null
        }
        return FeedRefreshSchedule(intervalMillis = interval.intervalMillis, delayMillis = delay)
    }

    /** Re-register the host schedule after any policy change or a completed wakeup. */
    fun reschedule() {
        val schedule = schedule()
        if (schedule == null) platform.cancelRefresh() else platform.scheduleRefresh(schedule)
    }

    /** Skip work the user disabled while still keeping per-feed checkpoints up to date. */
    suspend fun refreshInBackground(): FeedRefreshOutcome {
        if (!refreshInterval.enabled) return FeedRefreshOutcome.Skipped(FeedRefreshSkipReason.DISABLED)
        return refresh(notify = true)
    }

    /**
     * One refresh pass.
     *
     * [notify] is false for the in-app timer: a reader with the timeline already open does not need
     * an alert for an article that just appeared on screen.
     */
    suspend fun refresh(notify: Boolean = true): FeedRefreshOutcome = refreshMutex.withLock {
        val snapshot = store.state.value
        if (snapshot.feeds.isEmpty()) return@withLock FeedRefreshOutcome.Skipped(FeedRefreshSkipReason.NO_FEEDS)

        val summary = sync.syncAll()
        val updated = store.state.value
        val now = nowEpochMs()

        // Baseline feeds observed for the first time so their backlog is never announced. The
        // checkpoint also covers future-dated entries, which would otherwise alert on every pass.
        val baselineEpochMs = maxOf(now, updated.items.maxOfOrNull { it.publishedEpochMs } ?: 0L)
        alerts.baseline(updated.feeds.map { it.id }, baselineEpochMs)

        if (!notify || !notificationsEnabled || !notificationsSupported) {
            return@withLock FeedRefreshOutcome.Synced(
                feeds = summary.successCount,
                newItems = summary.totalNewItems,
                failedFeeds = summary.failedCount,
                notified = 0,
            )
        }

        val minutes = currentMinutesFromMidnight()
        if (quietHours.contains(minutes)) {
            return@withLock FeedRefreshOutcome.Synced(
                feeds = summary.successCount,
                newItems = summary.totalNewItems,
                failedFeeds = summary.failedCount,
                notified = 0,
            )
        }

        val preferences = alerts.state.value
        val batch = FeedNotificationPolicy.plan(updated.items, updated.feeds, preferences)
        if (batch.isEmpty) {
            return@withLock FeedRefreshOutcome.Synced(
                feeds = summary.successCount,
                newItems = summary.totalNewItems,
                failedFeeds = summary.failedCount,
                notified = 0,
            )
        }

        // Deliver first, then advance checkpoints, so a crash can only repeat one alert instead of
        // silently swallowing the articles that were never delivered.
        platform.publish(batch)
        alerts.recordNotified(FeedNotificationPolicy.checkpoints(updated.items, preferences))

        FeedRefreshOutcome.Synced(
            feeds = summary.successCount,
            newItems = summary.totalNewItems,
            failedFeeds = summary.failedCount,
            notified = batch.notifications.size,
        )
    }

    /** Used by the settings surface and hosts that offer an explicit "send a test" control. */
    suspend fun sendTestNotification(): Boolean {
        if (!notificationsSupported) return false
        platform.prepareNotifications()
        val feed = store.state.value.feeds.firstOrNull()
        val payload = FeedNotificationPayload(
            id = feed?.let { FeedNotificationPolicy.notificationId(it.id) }
                ?: FeedNotificationPolicy.SUMMARY_NOTIFICATION_ID,
            title = feed?.title?.takeIf { it.isNotBlank() } ?: "Pulse Feed",
            body = "Background refresh is working. New articles will appear here.",
            feedId = feed?.id.orEmpty(),
            itemId = "",
        )
        platform.publish(FeedNotificationBatch(listOf(payload)))
        return true
    }
}