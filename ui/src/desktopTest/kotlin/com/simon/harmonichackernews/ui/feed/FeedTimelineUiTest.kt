package com.simon.harmonichackernews.ui.feed

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.simon.harmonichackernews.feed.data.FeedSubscriptionSnapshot
import com.simon.harmonichackernews.feed.model.FeedFolder
import com.simon.harmonichackernews.feed.model.FeedItem
import com.simon.harmonichackernews.feed.model.FeedSource
import com.simon.harmonichackernews.feed.model.FeedTimelineFilter
import com.simon.harmonichackernews.ui.theme.HarmonicTheme
import com.simon.harmonichackernews.ui.theme.HarmonicThemeCatalog
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

class FeedTimelineUiTest {

    @Test
    fun feedTimelineScreenRendersItemsAndEmptyStatesWithoutCrashing() = SwingUtilities.invokeAndWait {
        val folder = FeedFolder("tech", "Tech")
        val feed = FeedSource("f1", "https://example.com/rss", "Example Blog", folderId = folder.id)
        val items = listOf(
            FeedItem(
                id = "1",
                feedId = feed.id,
                title = "Universal Feed Reader Pivot",
                url = "https://example.com/1",
                author = "Author One",
                publishedEpochMs = 1_700_000_000_000L,
                contentText = "This is a clean summary of the article content.",
                isRead = false,
                isBookmarked = true,
            ),
            FeedItem(
                id = "2",
                feedId = feed.id,
                title = "Second Item Already Read",
                url = "https://example.com/2",
                publishedEpochMs = 1_700_000_000_000L,
                isRead = true,
                isBookmarked = false,
            ),
        )
        val snapshot = FeedSubscriptionSnapshot(
            feeds = listOf(feed),
            folders = listOf(folder),
            items = items,
            unreadCount = 1,
        )

        val scene = ImageComposeScene(1080, 800, Density(1f)) {
            val palette = HarmonicThemeCatalog.resolve("material_light", false)
            HarmonicTheme(palette.colorScheme, palette.dark) {
                FeedTimelineScreen(
                    snapshot = snapshot,
                    currentFilter = FeedTimelineFilter.All,
                    syncing = false,
                    nowEpochMs = 1_700_000_060_000L,
                    onSelectFilter = {},
                    onRefresh = {},
                    onOpenItem = {},
                    onToggleBookmark = {},
                    onAddFeed = { _, _, _ -> },
                    onCreateFolder = {},
                )
            }
        }
        try {
            var time = 0L
            repeat(10) { scene.render(time.also { time += 16_666_667L }).close() }
            val texts = scene.semanticsOwners.flatMap { owner ->
                fun collect(node: SemanticsNode): List<String> =
                    listOfNotNull(node.config.getOrNull(SemanticsProperties.Text)?.joinToString()) +
                        node.children.flatMap(::collect)
                collect(owner.unmergedRootSemanticsNode)
            }
            assertTrue(texts.any { it.contains("Universal Feed Reader Pivot") })
            assertTrue(texts.any { it.contains("All Articles") })
        } finally {
            scene.close()
        }
    }

    @Test
    fun feedTimelineScreenRendersBothCardGridAndListModesWithPlaceholders() = SwingUtilities.invokeAndWait {
        val feed = FeedSource("f1", "https://example.com/rss", "Design Digest")
        val request = FeedItem(
            id = "card-no-img",
            feedId = feed.id,
            title = "Card Using Placeholder Gradient",
            url = "https://example.com/1",
            leadImageUrl = null,
            topics = listOf("Design"),
        )
        val snapshot = FeedSubscriptionSnapshot(feeds = listOf(feed), items = listOf(request), unreadCount = 1, viewMode = com.simon.harmonichackernews.feed.model.FeedViewMode.CARD_GRID)

        val scene = ImageComposeScene(800, 1000, Density(1f)) {
            val palette = HarmonicThemeCatalog.resolve("material_light", false)
            HarmonicTheme(palette.colorScheme, palette.dark) {
                FeedTimelineScreen(
                    snapshot = snapshot,
                    currentFilter = FeedTimelineFilter.All,
                    syncing = false,
                    nowEpochMs = 1_700_000_060_000L,
                    onSelectFilter = {},
                    onRefresh = {},
                    onOpenItem = {},
                    onToggleBookmark = {},
                    onAddFeed = { _, _, _ -> },
                    onCreateFolder = {},
                )
            }
        }
        try {
            scene.render(0L).close()
            val texts = scene.semanticsOwners.flatMap { owner ->
                fun collect(node: SemanticsNode): List<String> =
                    listOfNotNull(node.config.getOrNull(SemanticsProperties.Text)?.joinToString()) +
                        node.children.flatMap(::collect)
                collect(owner.unmergedRootSemanticsNode)
            }
            assertTrue(texts.any { it.contains("Card Using Placeholder Gradient") })
            assertTrue(texts.any { it.contains("Design") })
        } finally {
            scene.close()
        }
    }
}
