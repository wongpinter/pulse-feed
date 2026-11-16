package com.simon.harmonichackernews.ui.feed

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.simon.harmonichackernews.feed.model.FeedItem
import com.simon.harmonichackernews.feed.model.FeedSource
import com.simon.harmonichackernews.ui.theme.HarmonicTheme
import com.simon.harmonichackernews.ui.theme.HarmonicThemeCatalog
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

class FeedArticleReaderUiTest {

    @Test
    fun articleReaderRendersArticleAndActions() = SwingUtilities.invokeAndWait {
        val feed = FeedSource("f1", "https://example.com/rss", "Engineering Tech")
        val item = FeedItem(
            id = "item-101",
            feedId = feed.id,
            title = "Deep Dive into Multiplatform Feed Architecture",
            url = "https://example.com/posts/deep-dive",
            author = "Grace Hopper",
            publishedEpochMs = 1_700_000_000_000L,
            contentText = "Kotlin Multiplatform enables code sharing across Android, Desktop, and iOS with zero overhead.",
            isRead = true,
            isBookmarked = false,
        )

        val scene = ImageComposeScene(1080, 800, Density(1f)) {
            val palette = HarmonicThemeCatalog.resolve("material_light", false)
            HarmonicTheme(palette.colorScheme, palette.dark) {
                FeedArticleReaderScreen(
                    item = item,
                    feedTitle = feed.title,
                    nowEpochMs = 1_700_000_060_000L,
                    isBookmarked = item.isBookmarked,
                    onBack = {},
                    onToggleBookmark = {},
                    onOpenExternalLink = {},
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
            assertTrue(texts.any { it.contains("Deep Dive into Multiplatform Feed Architecture") })
            assertTrue(texts.any { it.contains("Grace Hopper") })
            assertTrue(texts.any { it.contains("View Full Webpage In-App") })
        } finally {
            scene.close()
        }
    }

    @Test
    fun opmlDialogsRenderWithoutCrashing() = SwingUtilities.invokeAndWait {
        val scene = ImageComposeScene(800, 600, Density(1f)) {
            val palette = HarmonicThemeCatalog.resolve("material_light", false)
            HarmonicTheme(palette.colorScheme, palette.dark) {
                OpmlImportDialog(
                    onDismiss = {},
                    onImportOpml = {},
                )
            }
        }
        try {
            scene.render(0L)
            val texts = scene.semanticsOwners.flatMap { owner ->
                fun collect(node: SemanticsNode): List<String> =
                    listOfNotNull(node.config.getOrNull(SemanticsProperties.Text)?.joinToString()) +
                        node.children.flatMap(::collect)
                collect(owner.unmergedRootSemanticsNode)
            }
            assertTrue(texts.any { it.contains("Import Subscriptions (OPML)") })
        } finally {
            scene.close()
        }
    }
}
