package com.simon.harmonichackernews.feed.model

import kotlinx.serialization.Serializable

@Serializable
enum class FeedViewMode {
    LIST,
    CARD_GRID,
}

/**
 * A subscribed feed endpoint (RSS, Atom, or JSON Feed).
 */
@Serializable
data class FeedSource(
    val id: String,
    val url: String,
    val title: String,
    val siteUrl: String? = null,
    val description: String? = null,
    val iconUrl: String? = null,
    val folderId: String? = null,
    val lastFetchedEpochMs: Long = 0L,
    val etag: String? = null,
    val lastModified: String? = null,
)

/**
 * A single entry/post from a feed source.
 */
@Serializable
data class FeedItem(
    val id: String,
    val feedId: String,
    val title: String,
    val url: String,
    val author: String? = null,
    val publishedEpochMs: Long = 0L,
    val contentHtml: String? = null,
    val contentText: String? = null,
    val fullContentHtml: String? = null,
    val fullContentFetchedEpochMs: Long? = null,
    val leadImageUrl: String? = null,
    val isRead: Boolean = false,
    val isBookmarked: Boolean = false,
    val topics: List<String> = emptyList(),
)

/**
 * A folder/category grouping multiple feed sources.
 */
@Serializable
data class FeedFolder(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
)

/**
 * Immediate, unpersisted output of [com.simon.harmonichackernews.feed.parser.FeedParser].
 */
data class ParsedFeed(
    val title: String,
    val siteUrl: String?,
    val description: String?,
    val iconUrl: String?,
    val items: List<ParsedFeedItem>,
)

/**
 * An individual item extracted from feed XML/JSON before assigning persistence identity.
 */
data class ParsedFeedItem(
    val guid: String?,
    val url: String,
    val title: String,
    val author: String?,
    val publishedEpochMs: Long,
    val contentHtml: String?,
    val contentText: String?,
    val leadImageUrl: String?,
    val topics: List<String> = emptyList(),
)
