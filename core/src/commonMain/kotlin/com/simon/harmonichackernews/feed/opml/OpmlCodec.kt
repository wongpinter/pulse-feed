package com.simon.harmonichackernews.feed.opml

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.simon.harmonichackernews.feed.model.FeedFolder
import com.simon.harmonichackernews.feed.model.FeedSource

data class OpmlFeedEntry(
    val title: String,
    val xmlUrl: String,
    val htmlUrl: String? = null,
    val folder: String? = null,
)

data class OpmlDocument(
    val title: String,
    val entries: List<OpmlFeedEntry>,
)

/**
 * Standard OPML 2.0 parser and serializer for feed subscriptions.
 */
object OpmlCodec {

    fun decode(opmlContent: String): OpmlDocument {
        val document = Ksoup.parseXml(opmlContent)
        val title = document.selectFirst("head > title")?.text()?.ifBlank { null }
            ?: "Feed Subscriptions"

        val entries = mutableListOf<OpmlFeedEntry>()
        val body = document.selectFirst("body")
            ?: throw IllegalArgumentException("Missing <body> element in OPML document")

        for (outline in body.children().filter { it.tagName() == "outline" }) {
            extractOutlines(outline, parentFolder = null, entries)
        }

        return OpmlDocument(title = title, entries = entries)
    }

    private fun extractOutlines(
        outline: Element,
        parentFolder: String?,
        output: MutableList<OpmlFeedEntry>,
    ) {
        val xmlUrl = outline.attr("xmlUrl").trim()
        val title = outline.attr("title").ifBlank { outline.attr("text") }.trim()

        if (xmlUrl.isNotBlank()) {
            val htmlUrl = outline.attr("htmlUrl").trim().ifBlank { null }
            output += OpmlFeedEntry(
                title = title.ifBlank { xmlUrl },
                xmlUrl = xmlUrl,
                htmlUrl = htmlUrl,
                folder = parentFolder,
            )
        } else {
            // Outline is a folder category
            val folderName = title.ifBlank { parentFolder }
            for (child in outline.children().filter { it.tagName() == "outline" }) {
                extractOutlines(child, folderName, output)
            }
        }
    }

    fun encode(
        feeds: List<FeedSource>,
        folders: List<FeedFolder> = emptyList(),
        title: String = "Pulse Feed Subscriptions",
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        append("""<opml version="2.0">""").append("\n")
        append("  <head>\n")
        append("    <title>").append(escapeXml(title)).append("</title>\n")
        append("  </head>\n")
        append("  <body>\n")

        val foldersById = folders.associateBy { it.id }
        val (groupedFeeds, rootFeeds) = feeds.partition { it.folderId != null }
        val feedsByFolderId = groupedFeeds.groupBy { it.folderId!! }

        for ((folderId, folderFeeds) in feedsByFolderId) {
            val folderName = foldersById[folderId]?.name ?: "Uncategorized"
            append("""    <outline text="${escapeXml(folderName)}" title="${escapeXml(folderName)}">""").append("\n")
            for (feed in folderFeeds) {
                appendFeedOutline(feed, indent = "      ")
            }
            append("    </outline>\n")
        }

        for (feed in rootFeeds) {
            appendFeedOutline(feed, indent = "    ")
        }

        append("  </body>\n")
        append("</opml>\n")
    }

    private fun StringBuilder.appendFeedOutline(feed: FeedSource, indent: String) {
        append(indent).append("""<outline type="rss" """)
        append("""text="${escapeXml(feed.title)}" """)
        append("""title="${escapeXml(feed.title)}" """)
        append("""xmlUrl="${escapeXml(feed.url)}"""")
        if (!feed.siteUrl.isNullOrBlank()) {
            append(""" htmlUrl="${escapeXml(feed.siteUrl)}"""")
        }
        append("/>\n")
    }

    private fun escapeXml(input: String): String = buildString(input.length + 16) {
        for (char in input) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(char)
            }
        }
    }
}
