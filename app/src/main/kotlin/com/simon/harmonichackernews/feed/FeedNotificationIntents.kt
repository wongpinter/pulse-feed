package com.simon.harmonichackernews.feed

/** Intent contract shared by feed notifications and the activity that receives their taps. */
object FeedNotificationIntents {
    const val ACTION_OPEN_FEED_ARTICLE = "com.simon.harmonichackernews.action.OPEN_FEED_ARTICLE"
    const val EXTRA_FEED_ITEM_ID = "feed_item_id"
}