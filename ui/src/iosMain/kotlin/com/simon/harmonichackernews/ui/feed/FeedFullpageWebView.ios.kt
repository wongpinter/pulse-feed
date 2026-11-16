package com.simon.harmonichackernews.ui.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
@Suppress("UNUSED_PARAMETER")
@Composable
actual fun FeedFullpageWebView(
    url: String,
    modifier: Modifier,
    isBlockedHost: (String?) -> Boolean,
    onLoadingChanged: (Boolean) -> Unit,
) {
    UIKitView(
        modifier = modifier,
        factory = {
            WKWebView(
                frame = CGRectZero.readValue(),
                configuration = WKWebViewConfiguration().apply {
                    defaultWebpagePreferences.allowsContentJavaScript = true
                },
            ).apply {
                navigationDelegate = object : NSObject(), WKNavigationDelegateProtocol {
                    override fun webView(
                        webView: WKWebView,
                        didStartProvisionalNavigation: WKNavigation?,
                    ) {
                        onLoadingChanged(true)
                    }

                    override fun webView(
                        webView: WKWebView,
                        didFinishNavigation: WKNavigation?,
                    ) {
                        onLoadingChanged(false)
                    }
                }
                NSURL.URLWithString(url)?.let { nsUrl ->
                    loadRequest(NSURLRequest.requestWithURL(nsUrl))
                }
            }
        },
        update = { webView ->
            val currentUrl = webView.URL?.absoluteString
            if (currentUrl != url) {
                NSURL.URLWithString(url)?.let { nsUrl ->
                    webView.loadRequest(NSURLRequest.requestWithURL(nsUrl))
                }
            }
        },
    )
}
