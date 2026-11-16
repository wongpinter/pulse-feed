package com.simon.harmonichackernews.feed

import com.simon.harmonichackernews.feed.classifier.FeedTopicClassifier
import com.simon.harmonichackernews.feed.data.FeedSubscriptionStore
import com.simon.harmonichackernews.feed.model.FeedItem
import com.simon.harmonichackernews.feed.model.FeedTimelineFilter
import com.simon.harmonichackernews.feed.model.filterFeedItems
import com.simon.harmonichackernews.feed.parser.UniversalFeedParser
import com.simon.harmonichackernews.settings.TestKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedCategorizationTest {

    @Test
    fun topicClassifierDetectsTopicsFromTextAndTags() {
        val aiTopics = FeedTopicClassifier.classify(
            title = "Training a new LLM transformer on open weights",
            content = "Machine learning researchers published benchmarks.",
        )
        assertTrue("AI" in aiTopics)

        val secTopics = FeedTopicClassifier.classify(
            title = "Protecting SSH Keys with macOS Secure Enclave",
        )
        assertTrue("Security" in secTopics)

        val progTopics = FeedTopicClassifier.classify(
            title = "Greg Kurtzer's Ginux is now a userspace kernel",
        )
        assertTrue("Programming" in progTopics)

        val explicitTagTopics = FeedTopicClassifier.classify(
            title = "Quarterly Update",
            existingTags = listOf("Hardware", "RandomTag"),
        )
        assertTrue("Hardware" in explicitTagTopics)
    }

    @Test
    fun universalFeedParserExtractsCategoryTags() {
        val rss = """
            <rss version="2.0">
              <channel>
                <title>Test</title>
                <item>
                  <title>Post</title>
                  <link>https://example.com/1</link>
                  <category>Security</category>
                  <category>Privacy</category>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val parsed = UniversalFeedParser().parse(rss, "https://example.com/rss")
        assertEquals(listOf("Security", "Privacy"), parsed.items.first().topics)
    }

    @Test
    fun storeRenamesFoldersAndMovesFeedsBetweenFolders() = runTest {
        val store = FeedSubscriptionStore(TestKeyValueStore())
        val folderA = store.createFolder("Old Folder")
        val folderB = store.createFolder("Target Folder")
        val feed = store.addFeed("https://example.com/rss", title = "Blog", folderId = folderA.id)

        // Rename folder
        assertTrue(store.renameFolder(folderA.id, "Renamed Folder"))
        assertEquals("Renamed Folder", store.state.value.folders.first { it.id == folderA.id }.name)

        // Move feed to folder B
        assertTrue(store.setFeedFolder(feed.id, folderB.id))
        assertEquals(folderB.id, store.state.value.feeds.first { it.id == feed.id }.folderId)

        // Rename feed
        assertTrue(store.updateFeedTitle(feed.id, "Engineering Blog"))
        assertEquals("Engineering Blog", store.state.value.feeds.first { it.id == feed.id }.title)
    }

    @Test
    fun filterFeedItemsFiltersByTopicAndFolderSimultaneously() {
        val feedTech = com.simon.harmonichackernews.feed.model.FeedSource("f1", "https://a.com/rss", "Tech", folderId = "tech")
        val feedSci = com.simon.harmonichackernews.feed.model.FeedSource("f2", "https://b.com/rss", "Sci", folderId = "sci")
        val feeds = listOf(feedTech, feedSci)

        val items = listOf(
            FeedItem("1", "f1", "AI in Kotlin", "https://a.com/1", topics = listOf("AI", "Programming")),
            FeedItem("2", "f1", "SQL Query Tuning", "https://a.com/2", topics = listOf("Programming")),
            FeedItem("3", "f2", "AI in Biology", "https://b.com/1", topics = listOf("AI", "Science")),
        )

        // All AI articles across folders
        assertEquals(2, filterFeedItems(items, feeds, FeedTimelineFilter.ByTopic("AI")).size)

        // AI articles ONLY inside Tech folder
        val filtered = filterFeedItems(items, feeds, FeedTimelineFilter.ByFolder("tech", "Tech"), selectedTopic = "AI")
        assertEquals(1, filtered.size)
        assertEquals("1", filtered.first().id)
    }
}
