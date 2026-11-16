package com.simon.harmonichackernews.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.simon.harmonichackernews.resources.Res
import com.simon.harmonichackernews.resources.ic_arrow_drop_up
import com.simon.harmonichackernews.resources.ic_check
import org.jetbrains.compose.resources.painterResource
import com.simon.harmonichackernews.feed.model.FeedSource
import com.simon.harmonichackernews.feed.refresh.FeedAlertPreference
import com.simon.harmonichackernews.feed.refresh.FeedQuietHours
import com.simon.harmonichackernews.feed.refresh.FeedRefreshInterval
import com.simon.harmonichackernews.feed.refresh.FeedRefreshRuntime
import com.simon.harmonichackernews.settings.NighttimeSchedule

/**
 * Feed refresh and alert preferences.
 *
 * Lives next to the feed timeline rather than in global Settings because every control here is
 * scoped to subscriptions, and the per-feed mute list has no meaning outside them.
 */
@Composable
fun FeedPreferencesDialog(
    runtime: FeedRefreshRuntime?,
    feeds: List<FeedSource>,
    alertPreferences: Map<String, FeedAlertPreference>,
    onDismiss: () -> Unit,
    onIntervalChanged: (FeedRefreshInterval) -> Unit = {},
    onNotificationsChanged: (Boolean) -> Unit = {},
    onQuietHoursChanged: (FeedQuietHours) -> Unit = {},
    onFeedAlertsChanged: (feedId: String, enabled: Boolean) -> Unit = { _, _ -> },
    onSendTestNotification: () -> Unit = {},
) {
    val notificationsSupported = runtime?.notificationsSupported == true
    val backgroundSupported = runtime?.backgroundSupported == true

    var interval by remember { mutableStateOf(runtime?.refreshInterval ?: FeedRefreshInterval.OFF) }
    var notificationsEnabled by remember { mutableStateOf(runtime?.notificationsEnabled ?: false) }
    var quietHours by remember { mutableStateOf(runtime?.quietHours ?: FeedQuietHours()) }
    var intervalMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Feed settings") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { intervalMenuOpen = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Refresh schedule", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (backgroundSupported) {
                                "Feeds refresh in the background at this cadence."
                            } else {
                                "Background refresh is unavailable on this platform; feeds refresh while the app is open."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The value sits on the trailing edge like the switches below, so the whole row
                    // owns the tap target and the dialog needs no extra button.
                    Box {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = interval.label(), style = MaterialTheme.typography.bodyMedium)
                            Icon(
                                painter = painterResource(Res.drawable.ic_arrow_drop_up),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(180f),
                            )
                        }
                        DropdownMenu(
                            expanded = intervalMenuOpen,
                            onDismissRequest = { intervalMenuOpen = false },
                        ) {
                            FeedRefreshInterval.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label()) },
                                    trailingIcon = {
                                        if (interval == option) {
                                            Icon(
                                                painter = painterResource(Res.drawable.ic_check),
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    },
                                    onClick = {
                                        interval = option
                                        onIntervalChanged(option)
                                        intervalMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("New article alerts", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (notificationsSupported) {
                                "Notify once per feed when unread articles arrive."
                            } else {
                                "This platform has no native notification surface yet."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = notificationsEnabled && notificationsSupported,
                        enabled = notificationsSupported,
                        onCheckedChange = { enabled ->
                            notificationsEnabled = enabled
                            onNotificationsChanged(enabled)
                        },
                    )
                }

                if (notificationsEnabled && notificationsSupported) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Quiet hours", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Alerts wait until ${quietHours.window.endLabel()}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = quietHours.enabled,
                            onCheckedChange = { enabled ->
                                quietHours = quietHours.copy(enabled = enabled)
                                onQuietHoursChanged(quietHours)
                            },
                        )
                    }
                    if (quietHours.enabled) {
                        HourStepper(
                            label = "From",
                            hour = quietHours.window.fromHour,
                            onHourChanged = { hour ->
                                quietHours = quietHours.copy(window = quietHours.window.copy(fromHour = hour))
                                onQuietHoursChanged(quietHours)
                            },
                        )
                        HourStepper(
                            label = "Until",
                            hour = quietHours.window.toHour,
                            onHourChanged = { hour ->
                                quietHours = quietHours.copy(window = quietHours.window.copy(toHour = hour))
                                onQuietHoursChanged(quietHours)
                            },
                        )
                    }

                    if (feeds.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        Text("Muted feeds", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Turn off alerts for individual subscriptions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        feeds.forEach { feed ->
                            val enabled = alertPreferences[feed.id]?.enabled ?: true
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = feed.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { checked -> onFeedAlertsChanged(feed.id, checked) },
                                )
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onSendTestNotification) {
                            Text("Send test notification")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun HourStepper(label: String, hour: Int, onHourChanged: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onHourChanged((hour + 23) % 24) }) { Text("−") }
            Text(formatHour(hour), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onHourChanged((hour + 1) % 24) }) { Text("+") }
        }
    }
}

private fun FeedRefreshInterval.label(): String = when (this) {
    FeedRefreshInterval.OFF -> "Off"
    FeedRefreshInterval.FIFTEEN -> "Every 15 minutes"
    FeedRefreshInterval.THIRTY -> "Every 30 minutes"
    FeedRefreshInterval.HOURLY -> "Hourly"
    FeedRefreshInterval.THREE_HOURS -> "Every 3 hours"
    FeedRefreshInterval.SIX_HOURS -> "Every 6 hours"
}

private fun NighttimeSchedule.endLabel(): String = formatHour(toHour)

private fun formatHour(hour: Int): String = "${hour.toString().padStart(2, '0')}:00"