package com.simon.harmonichackernews.feed.data

import com.simon.harmonichackernews.feed.classifier.FeedTopicClassifier
import com.simon.harmonichackernews.feed.model.FeedFolder
import com.simon.harmonichackernews.feed.model.FeedItem
import com.simon.harmonichackernews.feed.model.FeedSource
import com.simon.harmonichackernews.feed.model.FeedViewMode
import com.simon.harmonichackernews.network.StableHash
import com.simon.harmonichackernews.settings.KeyValueStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FeedSubscriptionSnapshot(
    val feeds: List<FeedSource> = emptyList(),
    val folders: List<FeedFolder> = emptyList(),
    val items: List<FeedItem> = emptyList(),
    val unreadCount: Int = 0,
    /**
     * Unread counts per feed, derived once per publish.
     *
     * Channel rows used to recount the whole item list per feed on every recomposition; with a
     * 2,000-item cache and a full drawer that is tens of thousands of comparisons per frame.
     */
    val unreadByFeed: Map<String, Int> = emptyMap(),
    val savedCount: Int = 0,
    val readingQueueCount: Int = 0,
    val viewMode: FeedViewMode = FeedViewMode.CARD_GRID,
    val changeVersion: Long = 0L,
)

/**
 * Portable repository managing user subscriptions, feed folders, and cached feed items.
 *
 * Subscriptions, folders, and view preferences live in [KeyValueStore] as JSON — they are a few
 * kilobytes and change rarely. Cached articles live in [FeedItemDatabase] when one is supplied:
 * they are the multi-megabyte part, and a read flag or a fetched article body must be one row
 * update, not a rewrite of the whole cache. Without a database (tests, hosts without storage) the
 * store keeps the original single-blob JSON layout so behaviour stays identical.
 */
class FeedSubscriptionStore(
    private val store: KeyValueStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val maxItemsRetained: Int = MAX_RETAINED_ITEMS_DEFAULT,
    private val itemDatabase: FeedItemDatabase? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /**
     * Compact per-item read/bookmark bits, stored separately from the item payload.
     *
     * The item payload carries full article bodies and can reach several megabytes, so writing it on
     * every read toggle is both slow and wasteful. Flag changes therefore touch only this small key.
     */
    private val itemFlags = mutableMapOf<String, Int>()

    private val mutableState = MutableStateFlow(FeedSubscriptionSnapshot())
    val state: StateFlow<FeedSubscriptionSnapshot> = mutableState.asStateFlow()

    init {
        loadFromStorage()
    }

    private fun loadFromStorage() {
        val rawFeeds = store.getString(KEY_FEEDS)
        val rawFolders = store.getString(KEY_FOLDERS)
        val rawItems = store.getString(KEY_ITEMS)
        loadItemFlags(store.getString(KEY_ITEM_FLAGS))

        val feeds = if (!rawFeeds.isNullOrBlank()) {
            runCatching { json.decodeFromString<List<FeedSource>>(rawFeeds) }.getOrDefault(emptyList())
        } else emptyList()

        val folders = if (!rawFolders.isNullOrBlank()) {
            runCatching { json.decodeFromString<List<FeedFolder>>(rawFolders) }.getOrDefault(emptyList())
        } else emptyList()

        val legacyItems = if (!rawItems.isNullOrBlank()) {
            runCatching { json.decodeFromString<List<FeedItem>>(rawItems) }.getOrDefault(emptyList())
        } else emptyList()

        val items = if (itemDatabase != null) {
            loadItemsFromDatabase(legacyItems)
        } else {
            legacyItems.map { it.withLegacyFlagsAndTopics() }
        }

        val viewMode = store.getString(KEY_VIEW_MODE)
            ?.let { raw -> runCatching { FeedViewMode.valueOf(raw) }.getOrNull() }
            ?: FeedViewMode.CARD_GRID

        publish(feeds = feeds, folders = folders, items = items, viewMode = viewMode)
    }

    /**
     * Reads cached articles from SQLite, importing any pre-migration JSON cache exactly once. The
     * import is only destructive after the rows are committed, so an interrupted upgrade re-imports
     * instead of losing the library.
     */
    private fun loadItemsFromDatabase(legacyItems: List<FeedItem>): List<FeedItem> {
        val database = itemDatabase ?: return emptyList()
        var stored = database.loadItems(maxItemsRetained)
        if (stored.isEmpty() && legacyItems.isNotEmpty()) {
            val imported = legacyItems.map { it.withLegacyFlagsAndTopics() }
            database.upsertItems(imported)
            database.trimToRetention(maxItemsRetained)
            stored = database.loadItems(maxItemsRetained)
            store.putString(KEY_ITEMS, "")
            itemFlags.clear()
            persistItemFlags()
        }
        return stored.map { item ->
            if (item.topics.isEmpty()) {
                val classified = item.copy(
                    topics = FeedTopicClassifier.classify(item.title, item.contentText),
                )
                database.upsertItems(listOf(classified))
                classified
            } else item
        }
    }

    private fun FeedItem.withLegacyFlagsAndTopics(): FeedItem {
        val flagged = itemFlags[id]?.let { bits ->
            copy(isRead = bits and FLAG_READ != 0, isBookmarked = bits and FLAG_BOOKMARK != 0)
        } ?: this
        return if (flagged.topics.isEmpty()) {
            flagged.copy(topics = FeedTopicClassifier.classify(flagged.title, flagged.contentText))
        } else flagged
    }

    suspend fun addFeed(
        url: String,
        title: String? = null,
        siteUrl: String? = null,
        folderId: String? = null,
    ): FeedSource = withContext(dispatcher) {
        val normalizedUrl = url.trim()
        val feedId = StableHash.sha256Hex(normalizedUrl).take(16)
        mutex.withLock {
            val existing = mutableState.value.feeds.firstOrNull { it.id == feedId || it.url == normalizedUrl }
            if (existing != null) return@withLock existing

            val newFeed = FeedSource(
                id = feedId,
                url = normalizedUrl,
                title = title?.ifBlank { null } ?: normalizedUrl,
                siteUrl = siteUrl,
                folderId = folderId,
            )
            val updated = mutableState.value.feeds + newFeed
            persistFeeds(updated)
            publish(feeds = updated)
            newFeed
        }
    }

    suspend fun updateFeed(feed: FeedSource): Boolean = withContext(dispatcher) {
        mutex.withLock {
            val current = mutableState.value.feeds
            val index = current.indexOfFirst { it.id == feed.id }
            if (index == -1) return@withLock false
            val updated = current.toMutableList().apply { set(index, feed) }
            persistFeeds(updated)
            publish(feeds = updated)
            true
        }
    }

    suspend fun removeFeed(feedId: String): Boolean = withContext(dispatcher) {
        mutex.withLock {
            val currentFeeds = mutableState.value.feeds
            val updatedFeeds = currentFeeds.filterNot { it.id == feedId }
            if (updatedFeeds.size == currentFeeds.size) return@withLock false

            val updatedItems = mutableState.value.items.filterNot { it.feedId == feedId }
            persistFeeds(updatedFeeds)
            if (itemDatabase != null) {
                itemDatabase.deleteFeedItems(feedId)
            } else {
                persistItems(updatedItems)
            }
            if (pruneItemFlags(updatedItems.mapTo(hashSetOf()) { it.id })) persistItemFlags()
            publish(feeds = updatedFeeds, items = updatedItems)
            true
        }
    }

    suspend fun createFolder(name: String): FeedFolder = withContext(dispatcher) {
        val cleanName = name.trim()
        val folderId = StableHash.sha256Hex(cleanName.lowercase()).take(12)
        mutex.withLock {
            val existing = mutableState.value.folders.firstOrNull { it.id == folderId }
            if (existing != null) return@withLock existing

            val folder = FeedFolder(
                id = folderId,
                name = cleanName,
                sortOrder = mutableState.value.folders.size,
            )
            val updated = mutableState.value.folders + folder
            persistFolders(updated)
            publish(folders = updated)
            folder
        }
    }

    suspend fun renameFolder(folderId: String, newName: String): Boolean = withContext(dispatcher) {
        val cleanName = newName.trim()
        if (cleanName.isBlank()) return@withContext false
        mutex.withLock {
            val current = mutableState.value.folders
            val index = current.indexOfFirst { it.id == folderId }
            if (index == -1) return@withLock false

            val updated = current.toMutableList().apply {
                set(index, current[index].copy(name = cleanName))
            }
            persistFolders(updated)
            publish(folders = updated)
            true
        }
    }

    suspend fun setFeedFolder(feedId: String, folderId: String?): Boolean = withContext(dispatcher) {
        mutex.withLock {
            val current = mutableState.value.feeds
            val index = current.indexOfFirst { it.id == feedId }
            if (index == -1) return@withLock false

            val updated = current.toMutableList().apply {
                set(index, current[index].copy(folderId = folderId))
            }
            persistFeeds(updated)
            publish(feeds = updated)
            true
        }
    }

    suspend fun updateFeedTitle(feedId: String, newTitle: String): Boolean = withContext(dispatcher) {
        val cleanTitle = newTitle.trim()
        if (cleanTitle.isBlank()) return@withContext false
        mutex.withLock {
            val current = mutableState.value.feeds
            val index = current.indexOfFirst { it.id == feedId }
            if (index == -1) return@withLock false

            val updated = current.toMutableList().apply {
                set(index, current[index].copy(title = cleanTitle))
            }
            persistFeeds(updated)
            publish(feeds = updated)
            true
        }
    }

    suspend fun removeFolder(folderId: String): Boolean = withContext(dispatcher) {
        mutex.withLock {
            val currentFolders = mutableState.value.folders
            val updatedFolders = currentFolders.filterNot { it.id == folderId }
            if (updatedFolders.size == currentFolders.size) return@withLock false

            // Move feeds in removed folder to root
            val updatedFeeds = mutableState.value.feeds.map { feed ->
                if (feed.folderId == folderId) feed.copy(folderId = null) else feed
            }
            persistFolders(updatedFolders)
            persistFeeds(updatedFeeds)
            publish(feeds = updatedFeeds, folders = updatedFolders)
            true
        }
    }

    suspend fun upsertItems(newItems: List<FeedItem>): Int = withContext(dispatcher) {
        if (newItems.isEmpty()) return@withContext 0
        mutex.withLock {
            val existingById = mutableState.value.items.associateBy { it.id }.toMutableMap()
            var addedOrUpdated = 0
            for (newItem in newItems) {
                val existing = existingById[newItem.id]
                if (existing == null) {
                    existingById[newItem.id] = newItem
                    addedOrUpdated++
                } else {
                    // Retain user's read and bookmark status on update
                    existingById[newItem.id] = newItem.copy(
                        isRead = existing.isRead,
                        isBookmarked = existing.isBookmarked,
                        contentText = if (existing.fullContentHtml != null) existing.contentText else newItem.contentText,
                        fullContentHtml = existing.fullContentHtml,
                        fullContentFetchedEpochMs = existing.fullContentFetchedEpochMs,
                    )
                }
            }

            // Sort by published timestamp descending and enforce retention bounds
            val sorted = existingById.values
                .sortedByDescending { it.publishedEpochMs }
                .let { list ->
                    if (list.size > maxItemsRetained) {
                        // Evict read, unbookmarked items first: unread content is never dropped before
                        // content the user has already seen.
                        val bookmarked = list.filter { it.isBookmarked }
                        val others = list.filterNot { it.isBookmarked }
                            .sortedWith(compareByDescending<FeedItem> { !it.isRead }.thenByDescending { it.publishedEpochMs })
                        val allowedOthers = (maxItemsRetained - bookmarked.size).coerceAtLeast(0)
                        (bookmarked + others.take(allowedOthers)).sortedByDescending { it.publishedEpochMs }
                    } else list
                }

            if (itemDatabase != null) {
                // Only rows whose payload actually changed are written; retention runs in SQL so the
                // database trims with the same read-first policy as the in-memory list.
                itemDatabase.upsertItems(
                    newItems.mapNotNull { existingById[it.id] }.distinctBy { it.id },
                )
                itemDatabase.trimToRetention(maxItemsRetained)
            } else {
                persistItems(sorted)
            }
            if (pruneItemFlags(sorted.mapTo(hashSetOf()) { it.id })) persistItemFlags()
            publish(items = sorted)
            addedOrUpdated
        }
    }

    suspend fun cacheFullArticle(itemId: String, html: String, text: String, fetchedEpochMs: Long): Boolean = withContext(dispatcher) {
        if (html.isBlank() || text.isBlank()) return@withContext false
        mutex.withLock {
            val current = mutableState.value.items
            val index = current.indexOfFirst { it.id == itemId }
            if (index == -1) return@withLock false
            val updated = current.toMutableList().apply {
                set(index, current[index].copy(fullContentHtml = html, contentText = text, fullContentFetchedEpochMs = fetchedEpochMs))
            }
            if (itemDatabase != null) {
                itemDatabase.setFullArticle(itemId, html, text, fetchedEpochMs)
            } else {
                persistItems(updated)
            }
            publish(items = updated)
            true
        }
    }

    suspend fun markItemRead(itemId: String, isRead: Boolean = true): Boolean = withContext(dispatcher) {
        mutex.withLock {
            val current = mutableState.value.items
            val index = current.indexOfFirst { it.id == itemId }
            if (index == -1 || current[index].isRead == isRead) return@withLock false

            val updated = current.toMutableList().apply {
                set(index, current[index].copy(isRead = isRead))
            }
            if (itemDatabase != null) itemDatabase.setRead(itemId, isRead) else writeItemFlag(updated[index])
            publish(items = updated)
            true
        }
    }

    suspend fun markItemBookmarked(itemId: String, isBookmarked: Boolean = true): Boolean = withContext(dispatcher) {
        mutex.withLock {
            val current = mutableState.value.items
            val index = current.indexOfFirst { it.id == itemId }
            if (index == -1 || current[index].isBookmarked == isBookmarked) return@withLock false

            val updated = current.toMutableList().apply {
                set(index, current[index].copy(isBookmarked = isBookmarked))
            }
            if (itemDatabase != null) {
                itemDatabase.setBookmarked(itemId, isBookmarked)
            } else {
                writeItemFlag(updated[index])
            }
            publish(items = updated)
            true
        }
    }

    suspend fun markFeedRead(feedId: String): Int = withContext(dispatcher) {
        mutex.withLock {
            val current = mutableState.value.items
            var marked = 0
            val updated = current.map { item ->
                if (item.feedId == feedId && !item.isRead) {
                    marked++
                    item.copy(isRead = true)
                } else item
            }
            if (marked > 0) {
                if (itemDatabase != null) {
                    itemDatabase.setFeedRead(feedId)
                } else {
                    for (item in updated) {
                        if (item.feedId == feedId) writeItemFlag(item)
                    }
                }
                publish(items = updated)
            }
            marked
        }
    }

    suspend fun setViewMode(mode: FeedViewMode) = withContext(dispatcher) {
        mutex.withLock {
            if (mutableState.value.viewMode == mode) return@withLock
            store.putString(KEY_VIEW_MODE, mode.name)
            publish(viewMode = mode)
        }
    }

    private fun persistFeeds(feeds: List<FeedSource>) {
        store.putString(KEY_FEEDS, json.encodeToString(feeds))
    }

    private fun persistFolders(folders: List<FeedFolder>) {
        store.putString(KEY_FOLDERS, json.encodeToString(folders))
    }

    private fun persistItems(items: List<FeedItem>) {
        store.putString(KEY_ITEMS, json.encodeToString(items))
    }

    /**
     * Searches the cached articles, so the timeline does not scan the in-memory cache when the
     * database can narrow the candidates through its full-text index first.
     */
    suspend fun searchLocal(query: String, limit: Int = SEARCH_RESULT_LIMIT): List<FeedItem> =
        withContext(dispatcher) {
            if (query.isBlank()) return@withContext emptyList()
            val database = itemDatabase ?: return@withContext emptyList()
            mutex.withLock { database.search(query, limit) }
        }

    /** True when this store persists articles as rows rather than as one JSON document. */
    val usesRowStorage: Boolean get() = itemDatabase != null

    /**
     * Stable [searchLocal] handle for composables: a lambda written inline inside `remember` cannot
     * be inferred as suspend, so the store hands one out instead.
     */
    fun searchHandle(): suspend (String) -> List<FeedItem> = { query -> searchLocal(query) }

    private fun loadItemFlags(raw: String?) {
        itemFlags.clear()
        if (raw.isNullOrBlank()) return
        runCatching { json.decodeFromString<Map<String, Int>>(raw) }
            .getOrDefault(emptyMap())
            .forEach { (id, bits) -> if (bits != 0) itemFlags[id] = bits }
    }

    private fun persistItemFlags() {
        store.putString(KEY_ITEM_FLAGS, json.encodeToString(itemFlags.toMap()))
    }

    private fun writeItemFlag(item: FeedItem) {
        var bits = itemFlags[item.id] ?: 0
        bits = if (item.isRead) bits or FLAG_READ else bits and FLAG_READ.inv()
        bits = if (item.isBookmarked) bits or FLAG_BOOKMARK else bits and FLAG_BOOKMARK.inv()
        if (bits == 0) itemFlags.remove(item.id) else itemFlags[item.id] = bits
        persistItemFlags()
    }

    /** Drops flag entries for items that are no longer cached. Returns true when anything changed. */
    private fun pruneItemFlags(validIds: Set<String>): Boolean {
        val removed = itemFlags.keys.removeAll { it !in validIds }
        return removed
    }

    private fun publish(
        feeds: List<FeedSource> = mutableState.value.feeds,
        folders: List<FeedFolder> = mutableState.value.folders,
        items: List<FeedItem> = mutableState.value.items,
        viewMode: FeedViewMode = mutableState.value.viewMode,
    ) {
        val unreadByFeed = HashMap<String, Int>()
        var unread = 0
        var saved = 0
        var readLater = 0
        for (item in items) {
            if (item.isBookmarked) {
                saved++
                if (!item.isRead) readLater++
            }
            if (!item.isRead) {
                unread++
                unreadByFeed[item.feedId] = (unreadByFeed[item.feedId] ?: 0) + 1
            }
        }
        mutableState.update { current ->
            FeedSubscriptionSnapshot(
                feeds = feeds,
                folders = folders,
                items = items,
                unreadCount = unread,
                unreadByFeed = unreadByFeed,
                savedCount = saved,
                readingQueueCount = readLater,
                viewMode = viewMode,
                changeVersion = current.changeVersion + 1L,
            )
        }
    }

    companion object {
        const val KEY_FEEDS = "harmonic_feed_subscriptions"
        const val KEY_FOLDERS = "harmonic_feed_folders"
        const val KEY_ITEMS = "harmonic_feed_items"
        const val KEY_VIEW_MODE = "harmonic_feed_view_mode"
        const val KEY_ITEM_FLAGS = "harmonic_feed_item_flags"
        const val MAX_RETAINED_ITEMS_DEFAULT = 2_000
        const val SEARCH_RESULT_LIMIT = 500

        private const val FLAG_READ = 1
        private const val FLAG_BOOKMARK = 2
    }
}
