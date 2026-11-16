package com.simon.harmonichackernews.feed

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.NetworkRequest
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.simon.harmonichackernews.MainActivity
import com.simon.harmonichackernews.R
import com.simon.harmonichackernews.feed.refresh.FeedNotificationBatch
import com.simon.harmonichackernews.feed.refresh.FeedNotificationPolicy
import com.simon.harmonichackernews.feed.refresh.FeedNotificationPayload
import com.simon.harmonichackernews.feed.refresh.FeedRefreshPlatform
import com.simon.harmonichackernews.feed.refresh.FeedRefreshSchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Android scheduler and notification renderer for feed refreshes.
 *
 * Uses [JobScheduler] rather than WorkManager so the feed job keeps the same persisted periodic
 * semantics and dependency surface as reply notifications.
 */
internal class AndroidFeedRefreshPlatform(context: Context) : FeedRefreshPlatform {
    private val appContext = context.applicationContext

    override fun prepareNotifications() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "New articles",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "New articles from your subscribed feeds" }
        appContext.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    override fun scheduleRefresh(schedule: FeedRefreshSchedule) {
        val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val builder = JobInfo.Builder(
            JOB_ID,
            ComponentName(appContext, FeedRefreshJobService::class.java),
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
        val delayMillis = schedule.delayMillis
        if (delayMillis != null) {
            builder.setMinimumLatency(delayMillis)
        } else {
            builder.setPeriodic(schedule.intervalMillis, schedule.flexMillis)
        }
        if (schedule.requiresUnmetered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setRequiredNetwork(
                NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
            )
        }
        runCatching { scheduler.schedule(builder.build()) }
    }

    override fun cancelRefresh() {
        (appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler)?.cancel(JOB_ID)
    }

    override suspend fun publish(batch: FeedNotificationBatch): Unit = withContext(Dispatchers.IO) {
        if (batch.notifications.isEmpty() || !canPostNotifications()) return@withContext
        if (batch.summary == null) {
            publishOne(batch.notifications.single(), grouped = false)
            return@withContext
        }
        try {
            val manager = NotificationManagerCompat.from(appContext)
            batch.notifications.forEach { notification ->
                currentCoroutineContext().ensureActive()
                manager.notify(notification.id, buildNotification(notification, grouped = true).build())
            }
            val summary = batch.summary ?: return@withContext
            val style = NotificationCompat.InboxStyle().setBigContentTitle(summary.title)
            batch.notifications.forEach { notification ->
                style.addLine("${notification.title}: ${notification.body}")
            }
            val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_feed_notification)
                .setContentTitle(summary.title)
                .setContentText(summary.body)
                .setStyle(style)
                .setContentIntent(intentFor(summary, summary.id))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setGroup(NOTIFICATION_GROUP_KEY)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setAutoCancel(true)
                .build()
            currentCoroutineContext().ensureActive()
            manager.notify(FeedNotificationPolicy.SUMMARY_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the runtime check and native publish.
        }
    }

    private fun publishOne(notification: FeedNotificationPayload, grouped: Boolean) {
        if (!canPostNotifications()) return
        try {
            NotificationManagerCompat.from(appContext).notify(
                notification.id,
                buildNotification(notification, grouped).build(),
            )
        } catch (_: SecurityException) {
            // Permission can be revoked at any point.
        }
    }

    private fun buildNotification(
        notification: FeedNotificationPayload,
        grouped: Boolean,
    ): NotificationCompat.Builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_feed_notification)
        .setContentTitle(notification.title)
        .setContentText(notification.body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
        .setContentIntent(intentFor(notification, notification.id))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .also { builder ->
            if (grouped) {
                builder.setGroup(NOTIFICATION_GROUP_KEY)
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            }
        }

    /** Tapping an alert opens the article when one is known, otherwise the feed timeline. */
    private fun intentFor(
        notification: FeedNotificationPayload,
        requestCode: Int,
    ): PendingIntent = PendingIntent.getActivity(
        appContext,
        requestCode,
        Intent(appContext, MainActivity::class.java).apply {
            action = FeedNotificationIntents.ACTION_OPEN_FEED_ARTICLE
            putExtra(FeedNotificationIntents.EXTRA_FEED_ITEM_ID, notification.itemId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_ID = "feed_notifications"
        private const val NOTIFICATION_GROUP_KEY =
            "com.simon.harmonichackernews.FEED_NOTIFICATIONS"
        private const val JOB_ID = 98374
    }
}