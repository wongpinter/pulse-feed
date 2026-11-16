package com.simon.harmonichackernews.feed.parser

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.simon.harmonichackernews.feed.model.ParsedFeed
import com.simon.harmonichackernews.feed.model.ParsedFeedItem
import com.simon.harmonichackernews.network.toNetworkUrlOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Universal feed parser auto-detecting RSS 2.0, Atom 1.0, and JSON Feed v1.1.
 */
interface FeedParser {
    fun parse(content: String, sourceUrl: String): ParsedFeed
}

class UniversalFeedParser : FeedParser {
    private val json = Json { ignoreUnknownKeys = true }

    override fun parse(content: String, sourceUrl: String): ParsedFeed {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Feed content is empty")

        return when {
            trimmed.startsWith("{") -> parseJsonFeed(trimmed, sourceUrl)
            trimmed.contains("<feed", ignoreCase = true) -> parseAtomFeed(trimmed, sourceUrl)
            else -> parseRssFeed(trimmed, sourceUrl)
        }
    }

    private fun parseJsonFeed(content: String, sourceUrl: String): ParsedFeed {
        val root = try {
            json.parseToJsonElement(content).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSON Feed: ${e.message}", e)
        }

        val version = root["version"]?.jsonPrimitive?.content.orEmpty()
        require(version.startsWith("https://jsonfeed.org/version/")) {
            "Unsupported JSON Feed version: $version"
        }

        val title = root["title"]?.jsonPrimitive?.content.orEmpty()
        val homePageUrl = root["home_page_url"]?.jsonPrimitive?.content
            ?.let { resolveUrl(sourceUrl, it) }
        val description = root["description"]?.jsonPrimitive?.content
        val iconUrl = (root["icon"] ?: root["favicon"])?.jsonPrimitive?.content
            ?.let { resolveUrl(sourceUrl, it) }

        val rawItems = root["items"] as? JsonArray ?: JsonArray(emptyList())
        val items = rawItems.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.content
            val url = obj["url"]?.jsonPrimitive?.content?.let { resolveUrl(sourceUrl, it) }
                ?: obj["external_url"]?.jsonPrimitive?.content?.let { resolveUrl(sourceUrl, it) }
                ?: id?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: return@mapNotNull null

            val itemTitle = obj["title"]?.jsonPrimitive?.content ?: ""
            val contentHtml = obj["content_html"]?.jsonPrimitive?.content
            val contentText = obj["content_text"]?.jsonPrimitive?.content
                ?: obj["summary"]?.jsonPrimitive?.content
                ?: contentHtml?.let { Ksoup.parse(it).text() }

            val publishedEpochMs = FeedDateParser.parseEpochMs(
                obj["date_published"]?.jsonPrimitive?.content
                    ?: obj["date_modified"]?.jsonPrimitive?.content,
            )

            val author = obj["authors"]?.jsonArray?.firstOrNull()?.let { authorEl ->
                authorEl.jsonObject["name"]?.jsonPrimitive?.content
            } ?: (obj["author"] as? JsonObject)?.get("name")?.jsonPrimitive?.content

            val leadImageUrl = (obj["image"] ?: obj["banner_image"])?.jsonPrimitive?.content
                ?.let { resolveUrl(sourceUrl, it) }
                ?: contentHtml?.let { extractFirstImgSrc(it, sourceUrl) }

            val tags = (obj["tags"] as? JsonArray)?.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()
            }.orEmpty()

            ParsedFeedItem(
                guid = id,
                url = url,
                title = itemTitle,
                author = author,
                publishedEpochMs = publishedEpochMs,
                contentHtml = contentHtml,
                contentText = contentText,
                leadImageUrl = leadImageUrl,
                topics = tags,
            )
        }

        return ParsedFeed(
            title = title,
            siteUrl = homePageUrl,
            description = description,
            iconUrl = iconUrl,
            items = items,
        )
    }

    private fun parseAtomFeed(content: String, sourceUrl: String): ParsedFeed {
        val document = Ksoup.parseXml(content)
        val feed = document.selectFirst("feed")
            ?: throw IllegalArgumentException("Missing <feed> root in Atom document")

        val title = feed.selectFirst("> title")?.text().orEmpty()
        val siteUrl = (feed.selectFirst("> link[rel=alternate]")
            ?: feed.selectFirst("> link:not([rel])")
            ?: feed.selectFirst("> link"))?.attr("href")?.let { resolveUrl(sourceUrl, it) }
        val description = feed.selectFirst("> subtitle")?.text()
        val iconUrl = (feed.selectFirst("> icon") ?: feed.selectFirst("> logo"))?.text()
            ?.let { resolveUrl(sourceUrl, it) }

        val items = feed.select("entry").mapNotNull { entry ->
            val link = (entry.selectFirst("link[rel=alternate]")
                ?: entry.selectFirst("link:not([rel])")
                ?: entry.selectFirst("link"))?.attr("href")?.let { resolveUrl(sourceUrl, it) }

            val guid = entry.selectFirst("id")?.text()
            val finalUrl = link ?: guid?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: return@mapNotNull null

            val itemTitle = entry.selectFirst("title")?.text().orEmpty()
            val author = entry.selectFirst("author > name")?.text()
            val published = entry.selectFirst("published")?.text()
                ?: entry.selectFirst("updated")?.text()
            val publishedEpochMs = FeedDateParser.parseEpochMs(published)

            val contentHtml = extractHtmlContent(entry.selectFirst("content"))
                ?: extractHtmlContent(entry.selectFirst("summary"))

            val contentText = contentHtml?.let { Ksoup.parse(it).text() }

            val leadImageUrl = (entry.selectFirst("media\\:content[url], content[url]")
                ?: entry.selectFirst("media\\:thumbnail[url]")
                ?: entry.selectFirst("enclosure[type^=image]"))?.attr("url")?.let { resolveUrl(sourceUrl, it) }
                ?: contentHtml?.let { extractFirstImgSrc(it, sourceUrl) }

            val categories = entry.select("category").mapNotNull { cat ->
                cat.attr("term").ifBlank { cat.attr("label") }.ifBlank { cat.text() }.trim().ifBlank { null }
            }

            ParsedFeedItem(
                guid = guid,
                url = finalUrl,
                title = itemTitle,
                author = author,
                publishedEpochMs = publishedEpochMs,
                contentHtml = contentHtml,
                contentText = contentText,
                leadImageUrl = leadImageUrl,
                topics = categories,
            )
        }

        return ParsedFeed(
            title = title,
            siteUrl = siteUrl,
            description = description,
            iconUrl = iconUrl,
            items = items,
        )
    }

    private fun parseRssFeed(content: String, sourceUrl: String): ParsedFeed {
        val document = Ksoup.parseXml(content)
        val channel = document.selectFirst("channel")
            ?: throw IllegalArgumentException("Missing <channel> element in RSS document")

        val title = channel.selectFirst("> title")?.text().orEmpty()
        val siteUrl = channel.selectFirst("> link")?.text()?.let { resolveUrl(sourceUrl, it) }
        val description = channel.selectFirst("> description")?.text()
        val iconUrl = channel.selectFirst("> image > url")?.text()?.let { resolveUrl(sourceUrl, it) }

        val items = channel.select("item").mapNotNull { item ->
            val link = item.selectFirst("link")?.text()?.let { resolveUrl(sourceUrl, it) }
            val guid = item.selectFirst("guid")?.text()
            val finalUrl = link ?: guid?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: return@mapNotNull null

            val itemTitle = item.selectFirst("title")?.text().orEmpty()
            val author = item.selectFirst("dc\\:creator, creator")?.text()
                ?: item.selectFirst("author")?.text()
            val pubDate = item.selectFirst("pubDate, dc\\:date, date")?.text()
            val publishedEpochMs = FeedDateParser.parseEpochMs(pubDate)

            val contentHtml = extractHtmlContent(item.selectFirst("content\\:encoded, encoded"))
                ?: extractHtmlContent(item.selectFirst("description"))

            val contentText = contentHtml?.let { Ksoup.parse(it).text() }

            val leadImageUrl = (item.selectFirst("enclosure[type^=image]")
                ?: item.selectFirst("media\\:content[url], content[url]")
                ?: item.selectFirst("media\\:thumbnail[url]"))?.attr("url")?.let { resolveUrl(sourceUrl, it) }
                ?: contentHtml?.let { extractFirstImgSrc(it, sourceUrl) }

            val categories = item.select("category").mapNotNull { cat ->
                cat.text().trim().ifBlank { null }
            }

            ParsedFeedItem(
                guid = guid,
                url = finalUrl,
                title = itemTitle,
                author = author,
                publishedEpochMs = publishedEpochMs,
                contentHtml = contentHtml,
                contentText = contentText,
                leadImageUrl = leadImageUrl,
                topics = categories,
            )
        }

        return ParsedFeed(
            title = title,
            siteUrl = siteUrl,
            description = description,
            iconUrl = iconUrl,
            items = items,
        )
    }

    private fun extractHtmlContent(element: Element?): String? {
        if (element == null) return null
        val text = element.text().trim()
        val html = element.html().trim()
        return when {
            element.children().isNotEmpty() -> html
            text.contains("<") && text.contains(">") -> text
            html.isNotBlank() -> html
            text.isNotBlank() -> text
            else -> null
        }
    }

    private fun extractFirstImgSrc(html: String, baseUri: String): String? {
        val img = Ksoup.parse(html).selectFirst("img[src]") ?: return null
        val src = img.attr("src").takeIf(String::isNotBlank) ?: return null
        return resolveUrl(baseUri, src)
    }

    private fun resolveUrl(base: String, candidate: String): String {
        val trimmed = candidate.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return base.toNetworkUrlOrNull()?.resolve(trimmed)?.toString() ?: trimmed
    }
}
