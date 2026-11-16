package com.simon.harmonichackernews.ui.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-native in-app fullpage web viewer.
 * Android: Android WebView with JavaScript, DOM storage, zoom, ad-host blocking.
 * iOS: WKWebView.
 * Desktop: in-app extracted-text browser frame.
 *
 * [isBlockedHost] receives the network host of every sub-resource request and lets the host apply the
 * application ad blocklist. Implementations must never load a blocked host.
 */
@Composable
expect fun FeedFullpageWebView(
    url: String,
    modifier: Modifier = Modifier,
    isBlockedHost: (String?) -> Boolean = { false },
    onLoadingChanged: (Boolean) -> Unit = {},
)
