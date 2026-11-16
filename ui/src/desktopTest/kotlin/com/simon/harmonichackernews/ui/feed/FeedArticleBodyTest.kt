package com.simon.harmonichackernews.ui.feed

import com.simon.harmonichackernews.feed.model.FeedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The reader pane must never render markup as literal text. */
class FeedArticleBodyTest {

    private fun item(contentHtml: String? = null, fullContentHtml: String? = null, contentText: String? = null) =
        FeedItem(
            id = "1",
            feedId = "f",
            title = "Title",
            url = "https://example.com/post",
            contentHtml = contentHtml,
            fullContentHtml = fullContentHtml,
            contentText = contentText,
        )

    @Test
    fun plainTextPayloadIsUsedAsIs() {
        assertEquals("A clean summary.", articleBodyText(item(contentText = "A clean summary.")))
    }

    @Test
    fun htmlPayloadIsReducedToText() {
        val body = articleBodyText(item(contentHtml = "<p>Hello <strong>world</strong> &amp; friends</p>"))

        assertEquals("Hello world & friends", body)
        assertFalse(body.contains("<p>"))
    }

    @Test
    fun fetchedFullArticleHtmlFallsBackToText() {
        val body = articleBodyText(
            item(
                contentHtml = "<p>Feed teaser</p>",
                fullContentHtml = "<article><h1>Headline</h1><p>Full body text</p></article>",
            ),
        )

        assertEquals("Headline Full body text", body)
    }

    @Test
    fun blankPayloadsProduceNoBody() {
        assertEquals("", articleBodyText(item()))
        assertEquals("", articleBodyText(item(contentText = "   ", contentHtml = "")))
    }
}