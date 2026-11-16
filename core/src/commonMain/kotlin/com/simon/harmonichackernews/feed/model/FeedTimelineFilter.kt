package com.simon.harmonichackernews.feed.model

sealed interface FeedTimelineFilter {
    val displayName: String

    data object All : FeedTimelineFilter {
        override val displayName: String = "All Articles"
    }

    data object Unread : FeedTimelineFilter {
        override val displayName: String = "Unread"
    }

    data object Bookmarked : FeedTimelineFilter {
        override val displayName: String = "Saved"
    }

    data object ReadingQueue : FeedTimelineFilter {
        override val displayName: String = "Read later"
    }

    data class ByFolder(val folderId: String, val folderName: String) : FeedTimelineFilter {
        override val displayName: String = folderName
    }

    data class ByFeed(val feedId: String, val feedTitle: String) : FeedTimelineFilter {
        override val displayName: String = feedTitle
    }

    data class ByTopic(val topic: String) : FeedTimelineFilter {
        override val displayName: String = topic
    }
}

/**
 * Filter an item stream according to the selected timeline criteria and search query.
 *
 * A non-blank [query] deliberately ignores [filter] and [selectedTopic] and searches every cached
 * article, ranked by match quality and then recency.
 */
fun filterFeedItems(
    items: List<FeedItem>,
    feeds: List<FeedSource>,
    filter: FeedTimelineFilter,
    selectedTopic: String? = null,
    query: String? = null,
): List<FeedItem> {
    val streamFiltered = when (filter) {
        is FeedTimelineFilter.All -> items
        is FeedTimelineFilter.Unread -> items.filter { !it.isRead }
        is FeedTimelineFilter.Bookmarked -> items.filter { it.isBookmarked }
        // "Read later" is the to-read slice of Saved, not a second copy of it.
        is FeedTimelineFilter.ReadingQueue -> items.filter { it.isBookmarked && !it.isRead }
        is FeedTimelineFilter.ByFolder -> {
            val matchingFeedIds = feeds.filter { it.folderId == filter.folderId }
                .mapTo(hashSetOf(), FeedSource::id)
            items.filter { it.feedId in matchingFeedIds }
        }
        is FeedTimelineFilter.ByFeed -> items.filter { it.feedId == filter.feedId }
        is FeedTimelineFilter.ByTopic -> items.filter { filter.topic in it.topics }
    }

    val topicFiltered = if (!selectedTopic.isNullOrBlank()) {
        streamFiltered.filter { selectedTopic in it.topics }
    } else {
        streamFiltered
    }

    val deduped = dedupeByArticleUrl(topicFiltered)

    val trimmedQuery = query?.trim()
    if (trimmedQuery.isNullOrBlank()) return deduped

    // A query searches the whole library, not just the rows the current filter happens to show:
    // the Search control is labelled "Search articles" and silently hiding matches based on the
    // selected channel would be a correctness bug. Ranking keeps title hits above body hits.
    return dedupeByArticleUrl(items)
        .mapNotNull { item -> searchRank(item, trimmedQuery)?.let { rank -> item to rank } }
        .sortedWith(
            compareBy<Pair<FeedItem, Int>> { (_, rank) -> rank }
                .thenByDescending { (item, _) -> item.publishedEpochMs },
        )
        .map { (item, _) -> item }
}

/** Lower is a better match; null means the item does not match at all. */
private fun searchRank(item: FeedItem, query: String): Int? {
    val lowerQuery = query.lowercase()
    val title = item.title.lowercase()
    return when {
        title.startsWith(lowerQuery) -> 0
        title.contains(lowerQuery) -> 1
        item.author?.lowercase()?.contains(lowerQuery) == true -> 2
        item.contentText?.lowercase()?.contains(lowerQuery) == true -> 3
        item.contentHtml?.lowercase()?.contains(lowerQuery) == true -> 4
        else -> null
    }
}

/**
 * Collapses the same article carried by several feeds into one row.
 *
 * Cross-feed views (All, Unread, folders, topic chips) would otherwise show the same link once per
 * feed. The newest copy supplies the content and read/bookmarked state is merged so acting on one
 * duplicate does not resurrect it from another feed.
 */
internal fun dedupeByArticleUrl(items: List<FeedItem>): List<FeedItem> {
    if (items.size < 2) return items
    val merged = LinkedHashMap<String, FeedItem>(items.size)
    for (item in items) {
        val key = item.url.trim().lowercase().trimEnd('/')
        if (key.isEmpty()) {
            // No usable URL: keep the item addressed by its own id.
            merged.getOrPut("id:${item.id}") { item }
            continue
        }
        val existing = merged[key]
        merged[key] = if (existing == null) {
            item
        } else {
            val newest = if (item.publishedEpochMs > existing.publishedEpochMs) item else existing
            newest.copy(
                isRead = existing.isRead || item.isRead,
                isBookmarked = existing.isBookmarked || item.isBookmarked,
            )
        }
    }
    return merged.values.toList()
}
