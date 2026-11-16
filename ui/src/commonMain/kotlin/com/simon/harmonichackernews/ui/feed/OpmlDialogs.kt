package com.simon.harmonichackernews.ui.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simon.harmonichackernews.feed.opml.OpmlCodec
import com.simon.harmonichackernews.feed.opml.OpmlDocument

@Composable
fun OpmlImportDialog(
    onDismiss: () -> Unit,
    onImportOpml: (OpmlDocument) -> Unit,
) {
    var opmlText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Subscriptions (OPML)") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Paste your OPML 2.0 export from Feedly, Inoreader, NetNewsWire, or any feed reader.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = opmlText,
                    onValueChange = {
                        opmlText = it
                        errorMessage = null
                    },
                    label = { Text("OPML XML Content") },
                    placeholder = { Text("<opml version=\"2.0\">...") },
                    minLines = 6,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        val doc = OpmlCodec.decode(opmlText)
                        onImportOpml(doc)
                    } catch (e: Exception) {
                        errorMessage = "Invalid OPML: ${e.message ?: "Failed to parse"}"
                    }
                },
                enabled = opmlText.isNotBlank(),
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun OpmlExportDialog(
    opmlXml: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Subscriptions (OPML)") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Standard OPML 2.0 format compatible with all feed readers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = opmlXml,
                    onValueChange = {},
                    readOnly = true,
                    minLines = 6,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = onCopy) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}
