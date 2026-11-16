package com.simon.harmonichackernews.ui.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.simon.harmonichackernews.feed.FeedOpenRequestBus
import com.simon.harmonichackernews.feed.FeedSubscribeRequestBus
import com.simon.harmonichackernews.feed.data.FeedSubscriptionStore
import com.simon.harmonichackernews.feed.model.FeedTimelineFilter
import com.simon.harmonichackernews.feed.opml.OpmlCodec
import com.simon.harmonichackernews.feed.refresh.FeedAlertPreference
import com.simon.harmonichackernews.feed.refresh.FeedRefreshRuntime
import com.simon.harmonichackernews.feed.sync.FeedSyncCoordinator
import com.simon.harmonichackernews.feed.sync.FeedArticleFetcher
import com.simon.harmonichackernews.summary.StorySummaryRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Self-contained root shell wiring [FeedSubscriptionStore], [FeedSyncCoordinator],
 * [FeedTimelineScreen], and [FeedArticleReaderScreen].
 */
@Composable
fun FeedReaderShell(
    store: FeedSubscriptionStore,
    syncCoordinator: FeedSyncCoordinator,
    onOpenExternalLink: (String) -> Unit,
    articleFetcher: FeedArticleFetcher? = null,
    summaryRuntime: StorySummaryRuntime? = null,
    subscribeRequests: FeedSubscribeRequestBus? = null,
    refreshRuntime: FeedRefreshRuntime? = null,
    openRequests: FeedOpenRequestBus? = null,
    requestNotificationPermission: () -> Unit = {},
    isBlockedHost: (String?) -> Boolean = { false },
    onFirstContentReady: () -> Unit = {},
    nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    modifier: Modifier = Modifier,
) {
    val snapshot by store.state.collectAsState()
    var currentFilter by remember { mutableStateOf<FeedTimelineFilter>(FeedTimelineFilter.All) }
    var syncing by remember { mutableStateOf(false) }
    var initialSyncSettled by remember { mutableStateOf(false) }
    var contentReported by remember { mutableStateOf(false) }
    var pendingSubscribeUrl by remember { mutableStateOf<String?>(null) }
    var pendingOpenItemId by remember { mutableStateOf<String?>(null) }
    val preferencesFlow = refreshRuntime?.alertPreferences?.state
    val alertPreferences by remember(preferencesFlow) {
        preferencesFlow ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, FeedAlertPreference>())
    }.collectAsState()
    val scope = rememberCoroutineScope()

    suspend fun runSync() {
        if (syncing) return
        syncing = true
        try {
            // The runtime owns per-feed alert checkpoints; falling back to a bare sync keeps the
            // shell usable in hosts that do not enable background refresh.
            val runtime = refreshRuntime
            if (runtime != null) runtime.refresh(notify = false) else syncCoordinator.syncAll()
        } finally {
            syncing = false
        }
    }

    // Refresh stale subscriptions once per app start instead of only on an explicit pull.
    LaunchedEffect(Unit) {
        val feeds = store.state.value.feeds
        if (feeds.isNotEmpty()) {
            val newestFetch = feeds.maxOfOrNull { it.lastFetchedEpochMs } ?: 0L
            val isStale = store.state.value.items.isEmpty() ||
                nowEpochMs() - newestFetch >= SYNC_MIN_INTERVAL_MS
            if (isStale) runSync()
        }
        initialSyncSettled = true
    }

    // Re-register the platform schedule with the current policy on every app start.
    LaunchedEffect(refreshRuntime) {
        refreshRuntime?.reschedule()
    }

    // In-app cadence: keeps the cache warm on hosts without a background scheduler, and never
    // raises an alert while the reader is already looking at the timeline.
    LaunchedEffect(refreshRuntime) {
        val runtime = refreshRuntime ?: return@LaunchedEffect
        while (true) {
            val interval = runtime.refreshInterval
            if (!interval.enabled) return@LaunchedEffect
            delay(interval.intervalMillis)
            runCatching { runtime.refresh(notify = false) }
        }
    }

    // Notification taps hand over a specific cached article.
    LaunchedEffect(openRequests) {
        val requests = openRequests ?: return@LaunchedEffect
        requests.pendingItemId.collect { itemId ->
            if (!itemId.isNullOrBlank()) {
                pendingOpenItemId = itemId
                requests.consume()
            }
        }
    }

    // Feed links/shares handed over by the platform prefills the subscribe dialog.
    LaunchedEffect(subscribeRequests) {
        val requests = subscribeRequests ?: return@LaunchedEffect
        requests.pendingUrl.collect { rawUrl ->
            val normalized = FeedSubscribeRequestBus.normalizeRequestUrl(rawUrl)
            if (normalized != null) {
                pendingSubscribeUrl = normalized
                requests.consume()
            }
        }
    }

    // Report a truthful first-content signal to the host launcher metrics.
    LaunchedEffect(initialSyncSettled, snapshot.items.size, snapshot.feeds.size) {
        if (contentReported) return@LaunchedEffect
        if (initialSyncSettled || snapshot.items.isNotEmpty() || snapshot.feeds.isEmpty()) {
            contentReported = true
            onFirstContentReady()
        }
    }

    // Kept stable across recompositions: a fresh lambda would restart the search effect on every
    // snapshot publish and re-query the index for an unchanged query.
    val searchArticles = remember(store) {
        if (store.usesRowStorage) store.searchHandle() else null
    }

    FeedTimelineScreen(
        snapshot = snapshot,
        currentFilter = currentFilter,
        syncing = syncing,
        nowEpochMs = nowEpochMs(),
        onSelectFilter = { currentFilter = it },
        onRefresh = {
            scope.launch { runSync() }
        },
        onOpenItem = { item ->
            scope.launch {
                store.markItemRead(item.id, true)
            }
        },
        onToggleBookmark = { item ->
            scope.launch {
                store.markItemBookmarked(item.id, !item.isBookmarked)
            }
        },
        onAddFeed = { url, title, folderId ->
            scope.launch {
                syncing = true
                try {
                    syncCoordinator.subscribeWithAutoDiscovery(url, title, folderId)
                } finally {
                    syncing = false
                }
            }
        },
        onCreateFolder = { name ->
            scope.launch {
                store.createFolder(name)
            }
        },
        onMarkFeedRead = { feedId ->
            scope.launch {
                store.markFeedRead(feedId)
            }
        },
        onOpenExternalLink = onOpenExternalLink,
        onImportOpml = { doc ->
            scope.launch {
                syncing = true
                try {
                    val folderIds = mutableMapOf<String, String>()
                    for (entry in doc.entries) {
                        val folderId = entry.folder?.let { folderName ->
                            folderIds.getOrPut(folderName) {
                                store.createFolder(folderName).id
                            }
                        }
                        store.addFeed(
                            url = entry.xmlUrl,
                            title = entry.title,
                            siteUrl = entry.htmlUrl,
                            folderId = folderId,
                        )
                    }
                    syncCoordinator.syncAll()
                } finally {
                    syncing = false
                }
            }
        },
        onExportOpml = {
            OpmlCodec.encode(snapshot.feeds, snapshot.folders)
        },
        onUpdateFeed = { updatedFeed ->
            scope.launch {
                store.updateFeed(updatedFeed)
                val activeFilter = currentFilter
                if (activeFilter is FeedTimelineFilter.ByFeed && activeFilter.feedId == updatedFeed.id) {
                    currentFilter = FeedTimelineFilter.ByFeed(updatedFeed.id, updatedFeed.title)
                }
            }
        },
        onRemoveFeed = { feedId ->
            scope.launch {
                store.removeFeed(feedId)
                refreshRuntime?.alertPreferences?.forget(listOf(feedId))
                val activeFilter = currentFilter
                if (activeFilter is FeedTimelineFilter.ByFeed && activeFilter.feedId == feedId) {
                    currentFilter = FeedTimelineFilter.All
                }
            }
        },
        onRenameFolder = { folderId, newName ->
            scope.launch {
                store.renameFolder(folderId, newName)
                val activeFilter = currentFilter
                if (activeFilter is FeedTimelineFilter.ByFolder && activeFilter.folderId == folderId) {
                    currentFilter = FeedTimelineFilter.ByFolder(folderId, newName)
                }
            }
        },
        onRemoveFolder = { folderId ->
            scope.launch {
                store.removeFolder(folderId)
                val activeFilter = currentFilter
                if (activeFilter is FeedTimelineFilter.ByFolder && activeFilter.folderId == folderId) {
                    currentFilter = FeedTimelineFilter.All
                }
            }
        },
        onViewModeChanged = { mode ->
            scope.launch {
                store.setViewMode(mode)
            }
        },
        summaryRuntime = summaryRuntime,
        onFetchFullArticle = articleFetcher?.let { fetcher -> fetcher::fetch },
        pendingSubscribeUrl = pendingSubscribeUrl,
        onPendingSubscribeUrlShown = { pendingSubscribeUrl = null },
        refreshRuntime = refreshRuntime,
        alertPreferences = alertPreferences,
        onFeedAlertsChanged = { feedId, enabled ->
            scope.launch { refreshRuntime?.alertPreferences?.setEnabled(feedId, enabled) }
        },
        onNotificationsEnabled = { enabled ->
            val runtime = refreshRuntime ?: return@FeedTimelineScreen
            runtime.notificationsEnabled = enabled
            if (enabled && runtime.notificationsSupported) requestNotificationPermission()
        },
        onSendTestNotification = {
            scope.launch { refreshRuntime?.sendTestNotification() }
        },
        pendingOpenItemId = pendingOpenItemId,
        onPendingOpenItemShown = { pendingOpenItemId = null },
        isBlockedHost = isBlockedHost,
        searchArticles = searchArticles,
        modifier = modifier,
    )
}

/** Minimum age of a subscription's cache before an app start triggers a refresh. */
private const val SYNC_MIN_INTERVAL_MS = 15L * 60L * 1000L
