package com.simon.harmonichackernews.feed.refresh

import com.simon.harmonichackernews.settings.NighttimeSchedule

/** User-selectable cadence for background feed refreshes. */
enum class FeedRefreshInterval(val minutes: Int) {
    OFF(0),
    FIFTEEN(15),
    THIRTY(30),
    HOURLY(60),
    THREE_HOURS(180),
    SIX_HOURS(360),
    ;

    val enabled: Boolean get() = minutes > 0
    val intervalMillis: Long get() = minutes * 60_000L

    companion object {
        val DEFAULT = HOURLY

        fun fromMinutes(minutes: Int): FeedRefreshInterval =
            entries.firstOrNull { it.minutes == minutes } ?: DEFAULT
    }
}

/** Platform scheduler request; hosts translate it into a periodic job or timer. */
data class FeedRefreshSchedule(
    val intervalMillis: Long,
    val flexMillis: Long = maxOf(MIN_FLEX_MILLIS, intervalMillis / 4),
    val requiresUnmetered: Boolean = false,
    /** Set when quiet hours must postpone the first run; null keeps the periodic cadence. */
    val delayMillis: Long? = null,
)

/** Skip feed alerts inside a daily window, mirroring the nighttime theme schedule shape. */
data class FeedQuietHours(
    val enabled: Boolean = false,
    val window: NighttimeSchedule = NighttimeSchedule(fromHour = 22, fromMinute = 0, toHour = 7, toMinute = 0),
) {
    fun contains(minutesFromMidnight: Int): Boolean =
        enabled && window.containsMinutes(minutesFromMidnight)

    /** Milliseconds until the window closes, or 0 when currently outside it. */
    fun millisUntilEnd(minutesFromMidnight: Int): Long {
        if (!contains(minutesFromMidnight)) return 0L
        val remainingMinutes = (window.endMinutes - minutesFromMidnight + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return (if (remainingMinutes == 0) MINUTES_PER_DAY else remainingMinutes) * 60_000L
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
    }
}

data class FeedNotificationPayload(
    val id: Int,
    val title: String,
    val body: String,
    val feedId: String,
    val itemId: String,
)

/** A single delivery pass: optional per-feed notifications plus a grouped summary. */
data class FeedNotificationBatch(
    val notifications: List<FeedNotificationPayload> = emptyList(),
    val summary: FeedNotificationPayload? = null,
) {
    val isEmpty: Boolean get() = notifications.isEmpty()
}

/** Outcome of one refresh pass, used by hosts to decide job success and retry. */
sealed interface FeedRefreshOutcome {
    /** Nothing to do: no subscriptions, background disabled, or inside quiet hours. */
    data class Skipped(val reason: FeedRefreshSkipReason) : FeedRefreshOutcome
    data class Synced(
        val feeds: Int,
        val newItems: Int,
        val failedFeeds: Int,
        val notified: Int,
    ) : FeedRefreshOutcome
}

enum class FeedRefreshSkipReason {
    NO_FEEDS,
    DISABLED,
    QUIET_HOURS,
    ALREADY_RUNNING,
}

internal const val MIN_FLEX_MILLIS = 5L * 60_000L