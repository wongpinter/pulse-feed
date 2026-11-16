package com.simon.harmonichackernews.feed

import com.simon.harmonichackernews.feed.data.FeedSubscriptionStore
import com.simon.harmonichackernews.feed.model.FeedItem
import com.simon.harmonichackernews.feed.model.FeedSource
import com.simon.harmonichackernews.feed.refresh.FeedAlertPreference
import com.simon.harmonichackernews.feed.refresh.FeedAlertPreferencesStore
import com.simon.harmonichackernews.feed.refresh.FeedNotificationBatch
import com.simon.harmonichackernews.feed.refresh.FeedNotificationPolicy
import com.simon.harmonichackernews.feed.refresh.FeedQuietHours
import com.simon.harmonichackernews.feed.refresh.FeedRefreshInterval
import com.simon.harmonichackernews.feed.refresh.FeedRefreshOutcome
import com.simon.harmonichackernews.feed.refresh.FeedRefreshPlatform
import com.simon.harmonichackernews.feed.refresh.FeedRefreshRuntime
import com.simon.harmonichackernews.feed.refresh.FeedRefreshSchedule
import com.simon.harmonichackernews.feed.refresh.FeedRefreshSkipReason
import com.simon.harmonichackernews.feed.sync.FeedSyncCoordinator
import com.simon.harmonichackernews.settings.NighttimeSchedule
import com.simon.harmonichackernews.settings.TestKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedRefreshTest {

    // ---------- interval and quiet hours ----------

    @Test
    fun intervalParsingFallsBackToTheDefault() {
        assertEquals(FeedRefreshInterval.HOURLY, FeedRefreshInterval.fromMinutes(60))
        assertEquals(FeedRefreshInterval.OFF, FeedRefreshInterval.fromMinutes(0))
        assertEquals(FeedRefreshInterval.DEFAULT, FeedRefreshInterval.fromMinutes(7))
        assertFalse(FeedRefreshInterval.OFF.enabled)
        assertTrue(FeedRefreshInterval.FIFTEEN.enabled)
        assertEquals(15L * 60_000L, FeedRefreshInterval.FIFTEEN.intervalMillis)
    }

    @Test
    fun quietHoursWrapMidnightAndReportTimeUntilEnd() {
        val quiet = FeedQuietHours(
            enabled = true,
            window = NighttimeSchedule(fromHour = 22, fromMinute = 0, toHour = 7, toMinute = 0),
        )

        assertTrue(quiet.contains(23 * 60))
        assertTrue(quiet.contains(2 * 60))
        assertFalse(quiet.contains(7 * 60))
        assertFalse(quiet.contains(12 * 60))
        assertEquals(0L, quiet.millisUntilEnd(12 * 60))
        assertEquals(5L * 3_600_000L, quiet.millisUntilEnd(2 * 60))
        assertFalse(FeedQuietHours(window = NighttimeSchedule()).contains(23 * 60))
    }

    // ---------- planner ----------

    @Test
    fun plannerAnnouncesOnlyUnreadArticlesNewerThanTheCheckpoint() {
        val feed = FeedSource(id = "f1", url = "https://example.com/rss", title = "Example")
        val items = listOf(
            item("old", feed.id, published = 1_000L),
            item("read", feed.id, published = 5_000L, isRead = true),
            item("new", feed.id, published = 9_000L),
        )
        val preferences = mapOf(feed.id to FeedAlertPreference(lastNotifiedEpochMs = 4_000L))

        val batch = FeedNotificationPolicy.plan(items, listOf(feed), preferences)

        assertEquals(1, batch.notifications.size)
        assertEquals("new", batch.notifications.single().itemId)
        assertEquals("Example", batch.notifications.single().title)
        assertNull(batch.summary)
    }

    @Test
    fun plannerHonoursMutedFeeds() {
        val muted = FeedSource(id = "muted", url = "https://m.example/rss", title = "Muted")
        val loud = FeedSource(id = "loud", url = "https://l.example/rss", title = "Loud")
        val items = listOf(
            item("a", muted.id, published = 9_000L),
            item("b", loud.id, published = 8_000L),
        )
        val preferences = mapOf(muted.id to FeedAlertPreference(enabled = false))

        val batch = FeedNotificationPolicy.plan(items, listOf(muted, loud), preferences)

        assertEquals(listOf("b"), batch.notifications.map { it.itemId })
    }

    @Test
    fun plannerGroupsMultipleFeedsUnderASummary() {
        val feeds = (1..6).map { FeedSource(id = "f$it", url = "https://f$it.example/rss", title = "Feed $it") }
        val items = feeds.mapIndexed { index, feed -> item("i$index", feed.id, published = 1_000L + index) }

        val batch = FeedNotificationPolicy.plan(items, feeds, emptyMap())

        assertEquals(FeedNotificationPolicy.MAX_INDIVIDUAL_NOTIFICATIONS, batch.notifications.size)
        val summary = assertNotNull(batch.summary)
        assertEquals("6 new articles", summary.title)
        assertEquals("From 6 feeds", summary.body)
        assertTrue(batch.notifications.first().id != batch.notifications.last().id)
    }

    @Test
    fun plannerMentionsAdditionalArticlesInTheSameFeed() {
        val feed = FeedSource(id = "f", url = "https://f.example/rss", title = "Feed")
        val items = listOf(
            item("a", feed.id, published = 1_000L),
            item("b", feed.id, published = 2_000L),
            item("c", feed.id, published = 3_000L),
        )

        val batch = FeedNotificationPolicy.plan(items, listOf(feed), emptyMap())

        assertEquals("Title c (+2 more)", batch.notifications.single().body)
    }

    // ---------- alert preference store ----------

    @Test
    fun baselineOnlySeedsMissingFeedsAndCheckpointsNeverMoveBackwards() = runTest {
        val keyValueStore = TestKeyValueStore()
        val store = FeedAlertPreferencesStore(keyValueStore)

        assertTrue(store.baseline(listOf("a", "b"), 1_000L))
        assertFalse(store.baseline(listOf("a", "b"), 5_000L), "existing checkpoints stay untouched")
        assertEquals(1_000L, store.preference("a").lastNotifiedEpochMs)

        assertTrue(store.recordNotified(mapOf("a" to 9_000L)))
        assertEquals(9_000L, store.preference("a").lastNotifiedEpochMs)
        assertFalse(store.recordNotified(mapOf("a" to 2_000L)), "older checkpoints are ignored")
        assertEquals(9_000L, store.preference("a").lastNotifiedEpochMs)

        store.setEnabled("a", false)
        assertFalse(store.preference("a").enabled)
        // A fresh store rehydrates from the same key.
        assertEquals(false, FeedAlertPreferencesStore(keyValueStore).preference("a").enabled)

        assertTrue(store.forget(listOf("a")))
        assertEquals(FeedAlertPreference(), store.preference("a"))
    }

    // ---------- runtime ----------

    @Test
    fun refreshBaselinesTheFirstSyncThenAnnouncesEachNewArticleOnce() = runTest {
        var payload = rss("Older article", "Mon, 27 Oct 2026 10:00:00 GMT")
        val keyValueStore = TestKeyValueStore()
        val settings = TestKeyValueStore()
        val store = FeedSubscriptionStore(keyValueStore)
        val alerts = FeedAlertPreferencesStore(keyValueStore)
        val platform = RecordingPlatform()
        var now = 1_000_000L
        val runtime = FeedRefreshRuntime(
            store = store,
            sync = FeedSyncCoordinator(
                HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }),
                store,
                nowEpochMs = { now },
            ),
            alerts = alerts,
            settings = settings,
            platform = platform,
            nowEpochMs = { now },
            currentMinutesFromMidnight = { 12 * 60 },
        )
        runtime.notificationsEnabled = true
        store.addFeed("https://example.com/feed.xml")

        val first = runtime.refresh()
        assertTrue(first is FeedRefreshOutcome.Synced)
        assertTrue(platform.batches.isEmpty(), "the first sync baselines instead of alerting")
        assertTrue(alerts.preference(store.state.value.feeds.single().id).lastNotifiedEpochMs > 0L)

        payload = rssDocument(
            "<item><title>Older article</title><link>https://example.com/1</link><guid>1</guid><pubDate>Mon, 27 Oct 2026 10:00:00 GMT</pubDate></item>" +
                "<item><title>Breaking article</title><link>https://example.com/2</link><guid>2</guid><pubDate>Mon, 27 Oct 2026 12:00:00 GMT</pubDate></item>",
            "",
        )
        now = 10_000_000L
        val second = runtime.refresh()
        assertTrue(second is FeedRefreshOutcome.Synced)
        assertEquals(1, platform.batches.size)
        val delivered = platform.batches.single()
        assertEquals(1, delivered.notifications.size)
        assertTrue(delivered.notifications.single().body.contains("Breaking article"))

        val third = runtime.refresh()
        assertTrue(third is FeedRefreshOutcome.Synced)
        assertEquals(1, platform.batches.size, "a repeated refresh must not re-announce")
    }

    @Test
    fun quietHoursPostponeDeliveryWithoutAdvancingTheCheckpoint() = runTest {
        var minutesFromMidnight = 23 * 60
        val keyValueStore = TestKeyValueStore()
        val store = FeedSubscriptionStore(keyValueStore)
        val alerts = FeedAlertPreferencesStore(keyValueStore)
        val platform = RecordingPlatform()
        var now = 1_000_000L
        var payload = rss("Nothing yet", "")
        val runtime = FeedRefreshRuntime(
            store = store,
            sync = FeedSyncCoordinator(
                HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }),
                store,
                nowEpochMs = { now },
            ),
            alerts = alerts,
            settings = TestKeyValueStore(),
            platform = platform,
            nowEpochMs = { now },
            currentMinutesFromMidnight = { minutesFromMidnight },
        )
        runtime.notificationsEnabled = true
        runtime.quietHours = FeedQuietHours(
            enabled = true,
            window = NighttimeSchedule(fromHour = 22, fromMinute = 0, toHour = 7, toMinute = 0),
        )
        val feed = store.addFeed("https://example.com/feed.xml")
        runtime.refresh(notify = false)

        payload = rss("Nightly article", "Mon, 27 Oct 2026 23:30:00 GMT")
        now = 20_000_000L
        runtime.refresh()
        assertTrue(platform.batches.isEmpty(), "quiet hours suppress delivery")

        minutesFromMidnight = 9 * 60
        runtime.refresh()
        assertEquals(1, platform.batches.size, "the postponed article alerts once quiet hours end")
        assertTrue(alerts.preference(feed.id).lastNotifiedEpochMs > 0L)
    }

    @Test
    fun disabledIntervalSkipsBackgroundWorkAndDisabledAlertsStillBaseline() = runTest {
        var payload = rss("Article", "Mon, 27 Oct 2026 10:00:00 GMT")
        val keyValueStore = TestKeyValueStore()
        val store = FeedSubscriptionStore(keyValueStore)
        val alerts = FeedAlertPreferencesStore(keyValueStore)
        val platform = RecordingPlatform()
        var now = 1_000_000L
        val runtime = FeedRefreshRuntime(
            store = store,
            sync = FeedSyncCoordinator(
                HttpClient(MockEngine { respond(payload, HttpStatusCode.OK) }),
                store,
                nowEpochMs = { now },
            ),
            alerts = alerts,
            settings = TestKeyValueStore(),
            platform = platform,
            nowEpochMs = { now },
            currentMinutesFromMidnight = { 12 * 60 },
        )

        assertEquals(
            FeedRefreshOutcome.Skipped(FeedRefreshSkipReason.NO_FEEDS),
            runtime.refreshInBackground(),
        )

        val feed = store.addFeed("https://example.com/feed.xml")
        runtime.refreshInterval = FeedRefreshInterval.OFF
        assertEquals(
            FeedRefreshOutcome.Skipped(FeedRefreshSkipReason.DISABLED),
            runtime.refreshInBackground(),
        )
        assertNull(runtime.schedule(), "an off interval cancels the host schedule")

        runtime.refresh()
        assertTrue(platform.batches.isEmpty())
        assertTrue(alerts.preference(feed.id).lastNotifiedEpochMs > 0L)
    }

    @Test
    fun schedulesPostponeUntilQuietHoursEnd() {
        val store = FeedSubscriptionStore(TestKeyValueStore())
        val platform = RecordingPlatform()
        val runtime = FeedRefreshRuntime(
            store = store,
            sync = FeedSyncCoordinator(
                HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
                store,
            ),
            alerts = FeedAlertPreferencesStore(TestKeyValueStore()),
            settings = TestKeyValueStore(),
            platform = platform,
            currentMinutesFromMidnight = { 2 * 60 },
        )
        runtime.refreshInterval = FeedRefreshInterval.THIRTY
        runtime.quietHours = FeedQuietHours(
            enabled = true,
            window = NighttimeSchedule(fromHour = 22, fromMinute = 0, toHour = 7, toMinute = 0),
        )

        val schedule = assertNotNull(runtime.schedule())
        assertEquals(FeedRefreshInterval.THIRTY.intervalMillis, schedule.intervalMillis)
        assertEquals(5L * 3_600_000L, schedule.delayMillis, "02:00 is five hours before 07:00")
        assertEquals(schedule, platform.scheduled, "policy changes re-register the host schedule")
    }

    // ---------- helpers ----------

    private fun item(
        id: String,
        feedId: String,
        published: Long,
        isRead: Boolean = false,
    ) = FeedItem(
        id = id,
        feedId = feedId,
        title = "Title $id",
        url = "https://example.com/$id",
        publishedEpochMs = published,
        isRead = isRead,
    )

    private fun rss(title: String, date: String): String = rssDocument(
        "<item><title>$title</title><link>https://example.com/1</link><guid>1</guid><pubDate>$date</pubDate></item>",
        "",
    )

    private fun rssDocument(items: String, extra: String): String = """
        <rss version="2.0">
          <channel>
            <title>Example</title>
            <link>https://example.com</link>
            $items
            $extra
          </channel>
        </rss>
    """.trimIndent()
}

private class RecordingPlatform : FeedRefreshPlatform {
    val batches = mutableListOf<FeedNotificationBatch>()
    var scheduled: FeedRefreshSchedule? = null
    var cancelled = 0

    override fun prepareNotifications() = Unit
    override fun scheduleRefresh(schedule: FeedRefreshSchedule) {
        scheduled = schedule
    }
    override fun cancelRefresh() {
        cancelled++
        scheduled = null
    }
    override suspend fun publish(batch: FeedNotificationBatch) {
        batches += batch
    }
}