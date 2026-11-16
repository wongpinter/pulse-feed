package com.simon.harmonichackernews.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.simon.harmonichackernews.feed.model.FeedItem
import com.simon.harmonichackernews.resources.Res
import com.simon.harmonichackernews.resources.ic_bookmark
import com.simon.harmonichackernews.resources.ic_bookmark_filled
import com.simon.harmonichackernews.utils.RelativeTimeFormatter
import org.jetbrains.compose.resources.painterResource

@Composable
fun FeedCardItem(
    item: FeedItem,
    feedTitle: String,
    nowEpochMs: Long,
    onClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Full-bleed top image with placeholder behind for loading/failure or missing URL
            val leadImageUrl = item.leadImageUrl
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (leadImageUrl != null) 130.dp else 110.dp),
            ) {
                FeedPlaceholderImage(
                    seed = item.id + ":" + item.title,
                    feedTitle = feedTitle,
                    primaryTopic = item.topics.firstOrNull(),
                    modifier = Modifier.matchParentSize(),
                )
                if (leadImageUrl != null) {
                    AsyncImage(
                        model = leadImageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }

            Column(modifier = Modifier.padding(12.dp)) {
                // Header: Unread indicator + Feed title + time
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (!item.isRead) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Text(
                        text = feedTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    val timeStr = RelativeTimeFormatter.format(item.publishedEpochMs, nowEpochMs)
                    if (timeStr != "?") {
                        Text(
                            text = " • $timeStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Title
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (item.isRead) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (item.isRead) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                // Topic Pills + Bookmark Button row
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.topics.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            for (topic in item.topics.take(2)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = topic,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    IconButton(
                        onClick = onToggleBookmark,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            painter = painterResource(
                                if (item.isBookmarked) Res.drawable.ic_bookmark_filled else Res.drawable.ic_bookmark,
                            ),
                            contentDescription = if (item.isBookmarked) "Remove bookmark" else "Bookmark",
                            tint = if (item.isBookmarked) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
