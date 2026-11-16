package com.simon.harmonichackernews.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fleeksoft.ksoup.Ksoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

@Suppress("UNUSED_PARAMETER")
@Composable
actual fun FeedFullpageWebView(
    url: String,
    modifier: Modifier,
    isBlockedHost: (String?) -> Boolean,
    onLoadingChanged: (Boolean) -> Unit,
) {
    var extractedText by remember(url) { mutableStateOf<String?>(null) }
    var loadError by remember(url) { mutableStateOf<String?>(null) }

    LaunchedEffect(url) {
        onLoadingChanged(true)
        loadError = null
        try {
            val html = withContext(Dispatchers.IO) {
                URI(url).toURL().openConnection().run {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", "Harmonic-Feed-Reader")
                    getInputStream().bufferedReader().use { it.readText() }
                }
            }
            val doc = Ksoup.parse(html, url)
            doc.select("script, style, nav, footer, header, aside").remove()
            val bodyText = doc.select("article, main").ifEmpty { listOfNotNull(doc.body()) }
                .joinToString("\n\n") { el ->
                    el.select("p, h1, h2, h3, li").joinToString("\n\n") { it.text() }
                }
                .ifBlank { doc.body()?.text().orEmpty() }
            extractedText = bodyText
        } catch (e: Exception) {
            loadError = e.message ?: "Could not load page"
        } finally {
            onLoadingChanged(false)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val error = loadError
        val content = extractedText
        when {
            error != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            runCatching { Desktop.getDesktop().browse(URI(url)) }
                        },
                    ) {
                        Text("Open in System Browser")
                    }
                }
            }
            content == null -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
