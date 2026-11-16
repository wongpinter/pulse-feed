package com.simon.harmonichackernews.feed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hand-off channel for notification taps that should open a specific cached article.
 *
 * Hosts publish the item id carried by the launch intent; the feed shell consumes it once and
 * opens the reader for that item (falling through to the timeline when the item was evicted).
 */
class FeedOpenRequestBus {
    private val mutableItemId = MutableStateFlow<String?>(null)
    val pendingItemId: StateFlow<String?> = mutableItemId.asStateFlow()

    fun request(itemId: String?) {
        val cleaned = itemId?.trim().orEmpty()
        if (cleaned.isEmpty()) return
        mutableItemId.value = cleaned
    }

    fun consume() {
        mutableItemId.value = null
    }
}

/**
 * Hand-off channel for "open this feed in the app" requests coming from platform launch objects
 * (`feed:`/`rss:` links, typed feed MIME types, and share-sheet text).
 *
 * Hosts publish a raw URL, the feed shell consumes it once and prefills the subscribe dialog.
 */
class FeedSubscribeRequestBus {
    private val mutableUrl = MutableStateFlow<String?>(null)
    val pendingUrl: StateFlow<String?> = mutableUrl.asStateFlow()

    fun request(rawUrl: String?) {
        val cleaned = rawUrl?.trim().orEmpty()
        if (cleaned.isEmpty()) return
        mutableUrl.value = cleaned
    }

    fun consume() {
        mutableUrl.value = null
    }

    /** Converts `feed://host/path` and `rss://host/path` into an http(s) URL the fetcher can use. */
    companion object {
        fun normalizeRequestUrl(rawUrl: String?): String? {
            val cleaned = rawUrl?.trim().orEmpty()
            if (cleaned.isEmpty()) return null
            val lower = cleaned.lowercase()
            val scheme = when {
                lower.startsWith("feed://") -> "feed://"
                lower.startsWith("rss://") -> "rss://"
                lower.startsWith("feeds://") -> "feeds://"
                else -> null
            } ?: return cleaned
            val remainder = cleaned.substring(scheme.length)
            if (remainder.isEmpty()) return null
            return "https://$remainder"
        }

        /** True when the text looks like a single shareable URL rather than prose. */
        fun extractSingleUrl(rawText: String?): String? {
            val firstWord = rawText
                ?.trim()
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty()
            if (firstWord.isEmpty()) return null
            val candidate = firstWord.substringBefore(' ').trim()
            val lower = candidate.lowercase()
            return when {
                lower.startsWith("http://") || lower.startsWith("https://") -> candidate
                lower.startsWith("feed://") || lower.startsWith("rss://") || lower.startsWith("feeds://") ->
                    normalizeRequestUrl(candidate)
                else -> null
            }
        }
    }
}