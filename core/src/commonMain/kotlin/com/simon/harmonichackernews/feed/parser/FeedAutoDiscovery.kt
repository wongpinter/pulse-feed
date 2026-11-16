package com.simon.harmonichackernews.feed.parser

import com.fleeksoft.ksoup.Ksoup
import com.simon.harmonichackernews.network.toNetworkUrlOrNull

data class DiscoveredFeed(
    val title: String,
    val feedUrl: String,
    val type: String,
)

/**
 * Discovers RSS, Atom, and JSON Feed links embedded in standard HTML pages.
 */
object FeedAutoDiscovery {
    private val FEED_MIME_TYPES = setOf(
        "application/rss+xml",
        "application/atom+xml",
        "application/feed+json",
        "application/json",
        "text/xml",
    )

    fun discoverFromHtml(html: String, siteUrl: String): List<DiscoveredFeed> {
        val document = Ksoup.parse(html)
        val discovered = mutableListOf<DiscoveredFeed>()

        // 1. Inspect <link rel="alternate" type="..." href="..."> in header
        for (link in document.select("link[rel~=(?i)alternate][href]")) {
            val type = link.attr("type").trim().lowercase()
            if (type in FEED_MIME_TYPES || link.attr("rel").contains("feed", ignoreCase = true)) {
                val href = link.attr("href").trim()
                val resolved = resolveUrl(siteUrl, href) ?: continue
                val title = link.attr("title").ifBlank { type }.ifBlank { "Feed" }
                discovered += DiscoveredFeed(title, resolved, type)
            }
        }

        // 2. Fallback: inspect <a> tags pointing directly to common feed paths
        if (discovered.isEmpty()) {
            for (a in document.select("a[href]")) {
                val href = a.attr("href").trim()
                val lower = href.lowercase()
                if (lower.endsWith(".xml") || lower.endsWith(".rss") ||
                    lower.endsWith("/feed") || lower.endsWith("/rss") || lower.endsWith("/feed.xml")
                ) {
                    val resolved = resolveUrl(siteUrl, href) ?: continue
                    val title = a.text().ifBlank { a.attr("title") }.ifBlank { "Feed" }
                    discovered += DiscoveredFeed(title, resolved, "application/rss+xml")
                }
            }
        }

        return discovered.distinctBy { it.feedUrl }
    }

    private fun resolveUrl(base: String, relative: String): String? {
        val trimmed = relative.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return base.toNetworkUrlOrNull()?.resolve(trimmed)?.toString()
    }
}
