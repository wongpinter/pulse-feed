package com.simon.harmonichackernews.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.simon.harmonichackernews.resources.Res
import com.simon.harmonichackernews.resources.ic_auto_awesome
import com.simon.harmonichackernews.resources.ic_business_center
import com.simon.harmonichackernews.resources.ic_code
import com.simon.harmonichackernews.resources.ic_memory
import com.simon.harmonichackernews.resources.ic_newspaper
import com.simon.harmonichackernews.resources.ic_palette
import com.simon.harmonichackernews.resources.ic_science
import com.simon.harmonichackernews.resources.ic_shield
import com.simon.harmonichackernews.resources.ic_visibility_off
import org.jetbrains.compose.resources.painterResource
import kotlin.math.abs

/**
 * Editorial gradient placeholder with topic-matched symbol and feed initial badge
 * displayed whenever a feed item lacks a lead image or fails to load.
 */
@Composable
fun FeedPlaceholderImage(
    seed: String,
    feedTitle: String = "",
    primaryTopic: String? = null,
    modifier: Modifier = Modifier,
) {
    val gradient = remember(seed) {
        val hash = abs(seed.hashCode())
        val palettes = listOf(
            listOf(Color(0xFF2C3E50), Color(0xFF3498DB)),
            listOf(Color(0xFF1F4037), Color(0xFF2E7D32)),
            listOf(Color(0xFF4A148C), Color(0xFF8E24AA)),
            listOf(Color(0xFF3E2723), Color(0xFF8D6E63)),
            listOf(Color(0xFF1A237E), Color(0xFF3949AB)),
            listOf(Color(0xFF263238), Color(0xFF546E7A)),
            listOf(Color(0xFF880E4F), Color(0xFFD81B60)),
            listOf(Color(0xFF004D40), Color(0xFF00897B)),
        )
        palettes[hash % palettes.size]
    }

    val iconRes = when (primaryTopic) {
        "AI" -> Res.drawable.ic_auto_awesome
        "Programming" -> Res.drawable.ic_code
        "Security" -> Res.drawable.ic_shield
        "Science" -> Res.drawable.ic_science
        "Business" -> Res.drawable.ic_business_center
        "Design" -> Res.drawable.ic_palette
        "Hardware" -> Res.drawable.ic_memory
        "Privacy" -> Res.drawable.ic_visibility_off
        else -> Res.drawable.ic_newspaper
    }

    Box(
        modifier = modifier.background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.42f),
            modifier = Modifier.size(34.dp),
        )

        val badgeText = feedTitle.trim().take(1).uppercase()
        if (badgeText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.28f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
