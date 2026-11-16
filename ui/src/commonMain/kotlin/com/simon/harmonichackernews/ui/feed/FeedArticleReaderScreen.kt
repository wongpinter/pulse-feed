package com.simon.harmonichackernews.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.simon.harmonichackernews.feed.model.FeedItem
import kotlinx.coroutines.launch
import com.simon.harmonichackernews.resources.Res
import com.simon.harmonichackernews.resources.ic_arrow_back
import com.simon.harmonichackernews.resources.ic_auto_awesome
import com.simon.harmonichackernews.resources.ic_bookmark
import com.simon.harmonichackernews.resources.ic_bookmark_filled
import com.simon.harmonichackernews.resources.ic_chrome_reader_mode
import com.simon.harmonichackernews.resources.ic_open_in_browser
import com.simon.harmonichackernews.resources.ic_web
import com.simon.harmonichackernews.summary.StorySummaryInput
import com.simon.harmonichackernews.summary.StorySummaryMode
import com.simon.harmonichackernews.summary.StorySummaryRuntime
import com.simon.harmonichackernews.summary.StorySummaryStatus
import com.simon.harmonichackernews.utils.HtmlTextUtils
import com.simon.harmonichackernews.utils.RelativeTimeFormatter
import org.jetbrains.compose.resources.painterResource

enum class ArticleDisplayMode {
    READER,
    WEB,
}

/**
 * Readable body text for the reader pane.
 *
 * Feed payloads carry plain text, HTML, or both. Rendering the HTML fallback directly would print
 * literal tags, so any markup is converted to text first.
 */
internal fun articleBodyText(item: FeedItem): String {
    item.contentText?.takeIf { it.isNotBlank() }?.let { return it.trim() }
    val html = item.fullContentHtml?.takeIf { it.isNotBlank() }
        ?: item.contentHtml?.takeIf { it.isNotBlank() }
    return HtmlTextUtils.plainText(html).trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedArticleReaderScreen(
    item: FeedItem,
    feedTitle: String,
    nowEpochMs: Long,
    isBookmarked: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenExternalLink: (String) -> Unit,
    summaryRuntime: StorySummaryRuntime? = null,
    onFetchFullArticle: (() -> Unit)? = null,
    fullArticleLoading: Boolean = false,
    fullArticleError: String? = null,
    isBlockedHost: (String?) -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    var displayMode by remember { mutableStateOf(ArticleDisplayMode.READER) }
    var aiSummaryRequested by remember { mutableStateOf(false) }
    var inAppWebLoading by remember { mutableStateOf(false) }
    val summaryState by (summaryRuntime?.state?.collectAsState()
        ?: remember { mutableStateOf(null) })

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = feedTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        displayMode = if (displayMode == ArticleDisplayMode.READER) {
                            ArticleDisplayMode.WEB
                        } else {
                            ArticleDisplayMode.READER
                        }
                    }) {
                        Icon(
                            painter = painterResource(
                                if (displayMode == ArticleDisplayMode.READER) Res.drawable.ic_web else Res.drawable.ic_chrome_reader_mode,
                            ),
                            contentDescription = if (displayMode == ArticleDisplayMode.READER) "View Web Page" else "View Reader Mode",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = onToggleBookmark) {
                        Icon(
                            painter = painterResource(
                                if (isBookmarked) Res.drawable.ic_bookmark_filled else Res.drawable.ic_bookmark,
                            ),
                            contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark",
                            tint = if (isBookmarked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        )
                    }
                    IconButton(onClick = { onOpenExternalLink(item.url) }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_open_in_browser),
                            contentDescription = "Open in browser",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (displayMode == ArticleDisplayMode.WEB) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                FeedFullpageWebView(
                    url = item.url,
                    modifier = Modifier.fillMaxSize(),
                    isBlockedHost = isBlockedHost,
                    onLoadingChanged = { inAppWebLoading = it },
                )
                if (inAppWebLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
            // Article Headline
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Metadata / Byline Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = feedTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )

                val timeStr = RelativeTimeFormatter.format(item.publishedEpochMs, nowEpochMs)
                if (timeStr != "?") {
                    Text(
                        text = " • $timeStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                if (!item.author.isNullOrBlank()) {
                    Text(
                        text = " • by ${item.author}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Optional Lead Image
            val leadImageUrl = item.leadImageUrl
            if (leadImageUrl != null) {
                AsyncImage(
                    model = leadImageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Local AI Summarization Section
            if (summaryRuntime != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_auto_awesome),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "AI Key Insights",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            if (!aiSummaryRequested) {
                                Button(
                                    onClick = {
                                        aiSummaryRequested = true
                                        summaryRuntime.start(
                                            StorySummaryMode.LOCAL,
                                            StorySummaryInput(
                                                articleUrl = item.url,
                                                articleText = item.contentText,
                                            ),
                                        )
                                    },
                                ) {
                                    Text("Summarize")
                                }
                            }
                        }

                        if (aiSummaryRequested && summaryState != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            val currentSummary = summaryState
                            if (currentSummary != null) {
                                when (currentSummary.status) {
                                    is StorySummaryStatus.Running -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = currentSummary.text ?: "Reading and summarizing article...",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    is StorySummaryStatus.Success -> {
                                        Text(
                                            text = currentSummary.text.orEmpty(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            lineHeight = 22.sp,
                                        )
                                    }
                                    is StorySummaryStatus.Failure -> {
                                        Text(
                                            text = (currentSummary.status as StorySummaryStatus.Failure).message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    StorySummaryStatus.Idle -> Unit
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(16.dp))

            // Article Body Content
            val articleContent = articleBodyText(item)
            if (articleContent.isNotBlank()) {
                Text(
                    text = articleContent,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 26.sp,
                )
            } else {
                Text(
                    text = "No article preview available in feed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (onFetchFullArticle != null && item.fullContentHtml.isNullOrBlank()) {
                fullArticleError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = onFetchFullArticle,
                    enabled = !fullArticleLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (fullArticleLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    else Text("Fetch full article")
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Switch to In-App Web View Button
            Button(
                onClick = { displayMode = ArticleDisplayMode.WEB },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_web),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("View Full Webpage In-App")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // External Browser fallback
            TextButton(
                onClick = { onOpenExternalLink(item.url) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_open_in_browser),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open in External Browser", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
}
