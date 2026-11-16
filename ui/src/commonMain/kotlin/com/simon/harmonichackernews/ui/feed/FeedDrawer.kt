package com.simon.harmonichackernews.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simon.harmonichackernews.feed.data.FeedSubscriptionSnapshot
import com.simon.harmonichackernews.feed.model.FeedFolder
import com.simon.harmonichackernews.feed.model.FeedItem
import com.simon.harmonichackernews.feed.model.FeedSource
import com.simon.harmonichackernews.feed.model.FeedTimelineFilter
import com.simon.harmonichackernews.resources.Res
import com.simon.harmonichackernews.resources.ic_more_vert
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Box
import com.simon.harmonichackernews.resources.ic_add
import com.simon.harmonichackernews.resources.ic_bookmark_filled
import com.simon.harmonichackernews.resources.ic_schedule
import com.simon.harmonichackernews.resources.ic_filter_list
import com.simon.harmonichackernews.resources.ic_label
import com.simon.harmonichackernews.resources.ic_layers
import com.simon.harmonichackernews.resources.ic_newspaper
import org.jetbrains.compose.resources.painterResource

@Composable
fun FeedDrawer(
    snapshot: FeedSubscriptionSnapshot,
    currentFilter: FeedTimelineFilter,
    onSelectFilter: (FeedTimelineFilter) -> Unit,
    onOpenAddFeedDialog: () -> Unit,
    onOpenOpmlImport: () -> Unit = {},
    onOpenOpmlExport: () -> Unit = {},
    onEditFeed: ((FeedSource) -> Unit)? = null,
    onEditFolder: ((FeedFolder) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    ModalDrawerSheet(
        modifier = modifier.width(300.dp).fillMaxHeight(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 16.dp),
        ) {
            // App Header
            Text(
                text = "Pulse Feed",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 1. Primary Channels
            NavigationDrawerItem(
                label = { Text("All Articles") },
                icon = { Icon(painterResource(Res.drawable.ic_newspaper), contentDescription = null, modifier = Modifier.size(20.dp)) },
                selected = currentFilter is FeedTimelineFilter.All,
                onClick = { onSelectFilter(FeedTimelineFilter.All) },
                badge = {
                    if (snapshot.unreadCount > 0) {
                        Badge { Text(snapshot.unreadCount.toString()) }
                    }
                },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )

            NavigationDrawerItem(
                label = { Text("Unread") },
                icon = { Icon(painterResource(Res.drawable.ic_filter_list), contentDescription = null, modifier = Modifier.size(20.dp)) },
                selected = currentFilter is FeedTimelineFilter.Unread,
                onClick = { onSelectFilter(FeedTimelineFilter.Unread) },
                badge = {
                    if (snapshot.unreadCount > 0) {
                        Badge { Text(snapshot.unreadCount.toString()) }
                    }
                },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )

            val bookmarkedCount = snapshot.savedCount
            NavigationDrawerItem(
                label = { Text("Saved") },
                icon = { Icon(painterResource(Res.drawable.ic_bookmark_filled), contentDescription = null, modifier = Modifier.size(20.dp)) },
                selected = currentFilter is FeedTimelineFilter.Bookmarked,
                onClick = { onSelectFilter(FeedTimelineFilter.Bookmarked) },
                badge = {
                    if (bookmarkedCount > 0) {
                        Badge { Text(bookmarkedCount.toString()) }
                    }
                },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )

            NavigationDrawerItem(
                label = { Text("Read later") },
                icon = { Icon(painterResource(Res.drawable.ic_schedule), contentDescription = null, modifier = Modifier.size(20.dp)) },
                selected = currentFilter is FeedTimelineFilter.ReadingQueue,
                onClick = { onSelectFilter(FeedTimelineFilter.ReadingQueue) },
                badge = {
                    val count = snapshot.readingQueueCount
                    if (count > 0) Badge { Text(count.toString()) }
                },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // 2. Folders
            if (snapshot.folders.isNotEmpty()) {
                Text(
                    text = "FOLDERS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )

                for (folder in snapshot.folders) {
                    val folderFeeds = snapshot.feeds.filter { it.folderId == folder.id }
                    val folderFeedIds = folderFeeds.mapTo(hashSetOf(), FeedSource::id)
                    val unreadInFolder = folderFeedIds.sumOf { snapshot.unreadByFeed[it] ?: 0 }

                    NavigationDrawerItem(
                        label = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        icon = { Icon(painterResource(Res.drawable.ic_layers), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        selected = currentFilter is FeedTimelineFilter.ByFolder && currentFilter.folderId == folder.id,
                        onClick = { onSelectFilter(FeedTimelineFilter.ByFolder(folder.id, folder.name)) },
                        badge = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (unreadInFolder > 0) {
                                    Badge { Text(unreadInFolder.toString()) }
                                }
                                if (onEditFolder != null) {
                                    IconButton(
                                        onClick = { onEditFolder(folder) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_more_vert),
                                            contentDescription = "Edit folder",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )

                    // Sub-items for feeds inside this folder
                    for (feed in folderFeeds) {
                        val unreadInFeed = snapshot.unreadByFeed[feed.id] ?: 0
                        NavigationDrawerItem(
                            label = { Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
                            selected = currentFilter is FeedTimelineFilter.ByFeed && currentFilter.feedId == feed.id,
                            onClick = { onSelectFilter(FeedTimelineFilter.ByFeed(feed.id, feed.title)) },
                            badge = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (unreadInFeed > 0) {
                                        Badge { Text(unreadInFeed.toString()) }
                                    }
                                    if (onEditFeed != null) {
                                        IconButton(
                                            onClick = { onEditFeed(feed) },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(Res.drawable.ic_more_vert),
                                                contentDescription = "Edit feed",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.outline,
                                            )
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.padding(start = 24.dp).padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            }

            // 3. Standalone Feeds (No folder)
            val rootFeeds = snapshot.feeds.filter { it.folderId == null }
            if (rootFeeds.isNotEmpty()) {
                Text(
                    text = "FEEDS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )

                for (feed in rootFeeds) {
                    val unreadInFeed = snapshot.unreadByFeed[feed.id] ?: 0
                    NavigationDrawerItem(
                        label = { Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        icon = { Icon(painterResource(Res.drawable.ic_label), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        selected = currentFilter is FeedTimelineFilter.ByFeed && currentFilter.feedId == feed.id,
                        onClick = { onSelectFilter(FeedTimelineFilter.ByFeed(feed.id, feed.title)) },
                        badge = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (unreadInFeed > 0) {
                                    Badge { Text(unreadInFeed.toString()) }
                                }
                                if (onEditFeed != null) {
                                    IconButton(
                                        onClick = { onEditFeed(feed) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_more_vert),
                                            contentDescription = "Edit feed",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(16.dp))

            // Action Button
            OutlinedButton(
                onClick = onOpenAddFeedDialog,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Icon(painterResource(Res.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add Feed")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(
                    onClick = onOpenOpmlImport,
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                ) {
                    Text("Import OPML", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = onOpenOpmlExport,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                ) {
                    Text("Export OPML", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
