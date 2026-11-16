package com.simon.harmonichackernews.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import com.simon.harmonichackernews.feed.model.FeedViewMode
import com.simon.harmonichackernews.resources.ic_grid_view
import com.simon.harmonichackernews.resources.ic_view_agenda
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import com.simon.harmonichackernews.feed.classifier.FeedTopicClassifier
import com.simon.harmonichackernews.feed.model.FeedFolder
import com.simon.harmonichackernews.feed.model.FeedSource
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simon.harmonichackernews.feed.data.FeedSubscriptionSnapshot
import com.simon.harmonichackernews.feed.model.FeedItem
import com.simon.harmonichackernews.feed.model.FeedTimelineFilter
import com.simon.harmonichackernews.feed.model.filterFeedItems
import com.simon.harmonichackernews.feed.opml.OpmlCodec
import com.simon.harmonichackernews.feed.opml.OpmlDocument
import com.simon.harmonichackernews.resources.Res
import com.simon.harmonichackernews.resources.ic_add
import com.simon.harmonichackernews.resources.ic_check
import com.simon.harmonichackernews.resources.ic_filter_list
import com.simon.harmonichackernews.resources.ic_more_vert
import com.simon.harmonichackernews.resources.ic_refresh
import com.simon.harmonichackernews.resources.ic_search
import com.simon.harmonichackernews.summary.StorySummaryRuntime
import com.simon.harmonichackernews.ui.common.LazyContentList
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedTimelineScreen(
    snapshot: FeedSubscriptionSnapshot,
    currentFilter: FeedTimelineFilter,
    syncing: Boolean,
    nowEpochMs: Long,
    onSelectFilter: (FeedTimelineFilter) -> Unit,
    onRefresh: () -> Unit,
    onOpenItem: (FeedItem) -> Unit,
    onToggleBookmark: (FeedItem) -> Unit,
    onAddFeed: (url: String, title: String?, folderId: String?) -> Unit,
    onCreateFolder: (name: String) -> Unit,
    onMarkFeedRead: ((feedId: String) -> Unit)? = null,
    onOpenExternalLink: (String) -> Unit = {},
    onImportOpml: (OpmlDocument) -> Unit = {},
    onExportOpml: () -> String = { OpmlCodec.encode(snapshot.feeds, snapshot.folders) },
    onUpdateFeed: ((FeedSource) -> Unit)? = null,
    onRemoveFeed: ((feedId: String) -> Unit)? = null,
    onRenameFolder: ((folderId: String, newName: String) -> Unit)? = null,
    onRemoveFolder: ((folderId: String) -> Unit)? = null,
    onViewModeChanged: ((FeedViewMode) -> Unit)? = null,
    summaryRuntime: StorySummaryRuntime? = null,
    onFetchFullArticle: (suspend (String) -> Result<Unit>)? = null,
    pendingSubscribeUrl: String? = null,
    onPendingSubscribeUrlShown: () -> Unit = {},
    refreshRuntime: com.simon.harmonichackernews.feed.refresh.FeedRefreshRuntime? = null,
    alertPreferences: Map<String, com.simon.harmonichackernews.feed.refresh.FeedAlertPreference> = emptyMap(),
    onFeedAlertsChanged: (feedId: String, enabled: Boolean) -> Unit = { _, _ -> },
    onNotificationsEnabled: (Boolean) -> Unit = {},
    onSendTestNotification: () -> Unit = {},
    pendingOpenItemId: String? = null,
    onPendingOpenItemShown: () -> Unit = {},
    isBlockedHost: (String?) -> Boolean = { false },
    /**
     * Indexed search over stored articles. Null leaves search to the in-memory ranking, which is
     * exact for a small cache and the only option for hosts without row storage.
     */
    searchArticles: (suspend (String) -> List<FeedItem>)? = null,
    modifier: Modifier = Modifier,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showAddFeedDialog by remember { mutableStateOf(false) }
    var addFeedInitialUrl by remember { mutableStateOf<String?>(null) }
    var showOpmlImportDialog by remember { mutableStateOf(false) }
    var showOpmlExportDialog by remember { mutableStateOf(false) }
    var editingFeed by remember { mutableStateOf<FeedSource?>(null) }
    var editingFolder by remember { mutableStateOf<FeedFolder?>(null) }
    var selectedTopic by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var viewMode by remember(snapshot.viewMode) { mutableStateOf(snapshot.viewMode) }
    var activeArticle by remember { mutableStateOf<FeedItem?>(null) }
    var fetchingArticleId by remember { mutableStateOf<String?>(null) }
    var fullArticleError by remember { mutableStateOf<String?>(null) }

    val feedsById = remember(snapshot.feeds) { snapshot.feeds.associateBy { it.id } }
    val trimmedSearchQuery = searchQuery.trim()

    // Indexed results arrive asynchronously; the in-memory ranking renders immediately and is
    // replaced once the index answers, so typing never waits on storage.
    var indexedResults by remember { mutableStateOf<Pair<String, List<FeedItem>>?>(null) }
    LaunchedEffect(trimmedSearchQuery, searchArticles, snapshot.changeVersion) {
        indexedResults = if (trimmedSearchQuery.isEmpty() || searchArticles == null) {
            null
        } else {
            trimmedSearchQuery to searchArticles(trimmedSearchQuery)
        }
    }

    // Results are tagged with the query that produced them; a slow answer for an old query must
    // never replace the rows the user is currently typing about.
    val indexedMatches = indexedResults
        ?.takeIf { (query, _) -> query == trimmedSearchQuery }
        ?.second
    val filteredItems = remember(
        snapshot.items,
        snapshot.feeds,
        currentFilter,
        selectedTopic,
        searchQuery,
        indexedMatches,
    ) {
        if (indexedMatches != null) {
            // The query deliberately spans the whole library, so only dedupe applies on top of it.
            filterFeedItems(indexedMatches, snapshot.feeds, FeedTimelineFilter.All)
        } else {
            filterFeedItems(
                snapshot.items,
                snapshot.feeds,
                currentFilter,
                selectedTopic = selectedTopic,
                query = searchQuery,
            )
        }
    }

    var showPreferencesDialog by remember { mutableStateOf(false) }
    var showActionsMenu by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    // A feed link opened from another app prefills the subscribe dialog instead of subscribing silently.
    LaunchedEffect(pendingSubscribeUrl) {
        val requested = pendingSubscribeUrl
        if (!requested.isNullOrBlank()) {
            addFeedInitialUrl = requested
            showAddFeedDialog = true
            onPendingSubscribeUrlShown()
        }
    }

    // A notification tap opens the article it announced, or leaves the timeline untouched when the
    // cached item has since been evicted.
    LaunchedEffect(pendingOpenItemId, snapshot.items) {
        val requested = pendingOpenItemId ?: return@LaunchedEffect
        val item = snapshot.items.firstOrNull { it.id == requested }
        if (item != null) {
            onOpenItem(item)
            activeArticle = item
        }
        onPendingOpenItemShown()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            FeedDrawer(
                snapshot = snapshot,
                currentFilter = currentFilter,
                onSelectFilter = { filter ->
                    onSelectFilter(filter)
                    scope.launch { drawerState.close() }
                },
                onOpenAddFeedDialog = {
                    scope.launch { drawerState.close() }
                    showAddFeedDialog = true
                },
                onOpenOpmlImport = {
                    scope.launch { drawerState.close() }
                    showOpmlImportDialog = true
                },
                onOpenOpmlExport = {
                    scope.launch { drawerState.close() }
                    showOpmlExportDialog = true
                },
                onEditFeed = { feed ->
                    scope.launch { drawerState.close() }
                    editingFeed = feed
                },
                onEditFolder = { folder ->
                    scope.launch { drawerState.close() }
                    editingFolder = folder
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Text(
                                text = currentFilter.displayName,
                                fontWeight = FontWeight.SemiBold,
                                // Six actions plus the drawer icon leave ~110dp for the title on a
                                // 360dp phone; without a single-line clamp it breaks mid-word.
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_filter_list),
                                    contentDescription = "Open feed channels",
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = {
                                    if (searchVisible) {
                                        searchVisible = false
                                        searchQuery = ""
                                    } else {
                                        searchVisible = true
                                    }
                                },
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_search),
                                    contentDescription = if (searchVisible) "Close search" else "Search articles",
                                )
                            }

                            IconButton(onClick = {
                                addFeedInitialUrl = null
                                showAddFeedDialog = true
                            }) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_add),
                                    contentDescription = "Add feed",
                                )
                            }

                            if (syncing) {
                                Box(
                                    modifier = Modifier.size(48.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            } else {
                                // Three visible actions leave the title room to stay on one line at
                                // 360dp; the view toggle, mark-read, manual sync, and feed settings
                                // live behind the overflow.
                                Box {
                                    IconButton(onClick = { showActionsMenu = true }) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_more_vert),
                                            contentDescription = "More feed actions",
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showActionsMenu,
                                        onDismissRequest = { showActionsMenu = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (viewMode == FeedViewMode.CARD_GRID) "List view" else "Card grid",
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    painter = painterResource(
                                                        if (viewMode == FeedViewMode.CARD_GRID) {
                                                            Res.drawable.ic_view_agenda
                                                        } else {
                                                            Res.drawable.ic_grid_view
                                                        },
                                                    ),
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                showActionsMenu = false
                                                val nextMode = if (viewMode == FeedViewMode.CARD_GRID) {
                                                    FeedViewMode.LIST
                                                } else {
                                                    FeedViewMode.CARD_GRID
                                                }
                                                viewMode = nextMode
                                                onViewModeChanged?.invoke(nextMode)
                                            },
                                        )
                                        if (currentFilter is FeedTimelineFilter.ByFeed && onMarkFeedRead != null) {
                                            DropdownMenuItem(
                                                text = { Text("Mark feed as read") },
                                                leadingIcon = {
                                                    Icon(
                                                        painter = painterResource(Res.drawable.ic_check),
                                                        contentDescription = null,
                                                    )
                                                },
                                                onClick = {
                                                    showActionsMenu = false
                                                    onMarkFeedRead(currentFilter.feedId)
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Sync feeds now") },
                                            leadingIcon = {
                                                Icon(
                                                    painter = painterResource(Res.drawable.ic_refresh),
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                showActionsMenu = false
                                                onRefresh()
                                            },
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Feed settings") },
                                            leadingIcon = {
                                                Icon(
                                                    painter = painterResource(Res.drawable.ic_more_vert),
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                showActionsMenu = false
                                                showPreferencesDialog = true
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )

                    if (searchVisible) {
                        // Opening search must raise the keyboard; otherwise the field looks inert.
                        LaunchedEffect(Unit) { runCatching { searchFocusRequester.requestFocus() } }
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .focusRequester(searchFocusRequester),
                            singleLine = true,
                            label = { Text("Search articles") },
                        )
                    }

                    // Horizontal Topic Filter Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = selectedTopic == null,
                            onClick = { selectedTopic = null },
                            label = { Text("All Topics") },
                        )
                        for (topic in FeedTopicClassifier.TOPICS) {
                            FilterChip(
                                selected = selectedTopic == topic,
                                onClick = {
                                    selectedTopic = if (selectedTopic == topic) null else topic
                                },
                                label = { Text(topic) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val emptyText = if (snapshot.feeds.isEmpty()) {
                            "No feeds subscribed yet."
                        } else {
                            "No articles match this view."
                        }
                        Text(
                            text = emptyText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        if (snapshot.feeds.isEmpty()) {
                            Button(onClick = { showAddFeedDialog = true }) {
                                Text("Add First Feed")
                            }
                        } else {
                            Button(onClick = onRefresh) {
                                Text("Refresh Feeds")
                            }
                        }
                    }
                }
            } else if (viewMode == FeedViewMode.CARD_GRID) {
                val gridState = rememberLazyGridState()
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 165.dp),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = filteredItems,
                        key = { it.id },
                        contentType = { "feed-card" },
                    ) { item ->
                        val feedTitle = feedsById[item.feedId]?.title ?: "Feed"
                        FeedCardItem(
                            item = item,
                            feedTitle = feedTitle,
                            nowEpochMs = nowEpochMs,
                            onClick = {
                                activeArticle = item
                                onOpenItem(item)
                            },
                            onToggleBookmark = { onToggleBookmark(item) },
                        )
                    }
                }
            } else {
                val listState = rememberLazyListState()
                LazyContentList(
                    items = filteredItems,
                    state = listState,
                    key = { it.id },
                    contentType = { "feed-item" },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) { _, item ->
                    val feedTitle = feedsById[item.feedId]?.title ?: "Feed"
                    FeedItemRow(
                        item = item,
                        feedTitle = feedTitle,
                        nowEpochMs = nowEpochMs,
                        onClick = {
                            activeArticle = item
                            onOpenItem(item)
                        },
                        onToggleBookmark = { onToggleBookmark(item) },
                    )
                }
            }
        }
    }

    if (showAddFeedDialog) {
        AddFeedDialog(
            folders = snapshot.folders,
            initialUrl = addFeedInitialUrl,
            onDismiss = {
                showAddFeedDialog = false
                addFeedInitialUrl = null
            },
            onAddFeed = { url, title, folderId ->
                onAddFeed(url, title, folderId)
                showAddFeedDialog = false
                addFeedInitialUrl = null
            },
            onCreateFolder = onCreateFolder,
        )
    }

    if (showOpmlImportDialog) {
        OpmlImportDialog(
            onDismiss = { showOpmlImportDialog = false },
            onImportOpml = { doc ->
                onImportOpml(doc)
                showOpmlImportDialog = false
            },
        )
    }

    if (showOpmlExportDialog) {
        OpmlExportDialog(
            opmlXml = onExportOpml(),
            onDismiss = { showOpmlExportDialog = false },
            onCopy = { showOpmlExportDialog = false },
        )
    }

    val feedToEdit = editingFeed
    if (feedToEdit != null) {
        EditFeedDialog(
            feed = feedToEdit,
            folders = snapshot.folders,
            onDismiss = { editingFeed = null },
            onSave = { newTitle, newFolderId ->
                onUpdateFeed?.invoke(feedToEdit.copy(title = newTitle, folderId = newFolderId))
                editingFeed = null
            },
            onUnsubscribe = {
                onRemoveFeed?.invoke(feedToEdit.id)
                editingFeed = null
            },
        )
    }

    val folderToEdit = editingFolder
    if (folderToEdit != null) {
        EditFolderDialog(
            folder = folderToEdit,
            onDismiss = { editingFolder = null },
            onRename = { newName ->
                onRenameFolder?.invoke(folderToEdit.id, newName)
                editingFolder = null
            },
            onDelete = {
                onRemoveFolder?.invoke(folderToEdit.id)
                editingFolder = null
            },
        )
    }

    if (showPreferencesDialog) {
        FeedPreferencesDialog(
            runtime = refreshRuntime,
            feeds = snapshot.feeds,
            alertPreferences = alertPreferences,
            onDismiss = { showPreferencesDialog = false },
            onIntervalChanged = { refreshRuntime?.refreshInterval = it },
            onNotificationsChanged = onNotificationsEnabled,
            onQuietHoursChanged = { refreshRuntime?.quietHours = it },
            onFeedAlertsChanged = onFeedAlertsChanged,
            onSendTestNotification = onSendTestNotification,
        )
    }

    val article = activeArticle
    if (article != null) {
        FeedArticleReaderScreen(
            item = article,
            feedTitle = feedsById[article.feedId]?.title ?: "Feed",
            nowEpochMs = nowEpochMs,
            isBookmarked = snapshot.items.firstOrNull { it.id == article.id }?.isBookmarked ?: article.isBookmarked,
            onBack = { activeArticle = null },
            onToggleBookmark = { onToggleBookmark(article) },
            onOpenExternalLink = onOpenExternalLink,
            summaryRuntime = summaryRuntime,
            isBlockedHost = isBlockedHost,
            onFetchFullArticle = onFetchFullArticle?.let { fetch ->
                {
                    if (fetchingArticleId == null) {
                        fetchingArticleId = article.id
                        fullArticleError = null
                        scope.launch {
                            val result = fetch(article.id)
                            fetchingArticleId = null
                            fullArticleError = result.exceptionOrNull()?.message
                        }
                    }
                }
            },
            fullArticleLoading = fetchingArticleId == article.id,
            fullArticleError = fullArticleError,
        )
    }
}
