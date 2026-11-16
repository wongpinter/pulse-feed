package com.simon.harmonichackernews.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simon.harmonichackernews.feed.model.FeedFolder

@Composable
fun AddFeedDialog(
    folders: List<FeedFolder>,
    onDismiss: () -> Unit,
    onAddFeed: (url: String, title: String?, folderId: String?) -> Unit,
    onCreateFolder: (name: String) -> Unit,
    initialUrl: String? = null,
) {
    var urlText by remember(initialUrl) { mutableStateOf(initialUrl.orEmpty()) }
    var titleText by remember { mutableStateOf("") }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var folderDropdownExpanded by remember { mutableStateOf(false) }
    var newFolderMode by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Feed Subscription") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("Feed or Website URL") },
                    placeholder = { Text("https://example.com/rss") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("Custom Title (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (!newFolderMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val folderName = folders.firstOrNull { it.id == selectedFolderId }?.name ?: "None (Root)"
                        OutlinedButton(onClick = { folderDropdownExpanded = true }) {
                            Text("Folder: $folderName")
                        }

                        DropdownMenu(
                            expanded = folderDropdownExpanded,
                            onDismissRequest = { folderDropdownExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("None (Root)") },
                                onClick = {
                                    selectedFolderId = null
                                    folderDropdownExpanded = false
                                },
                            )
                            for (folder in folders) {
                                DropdownMenuItem(
                                    text = { Text(folder.name) },
                                    onClick = {
                                        selectedFolderId = folder.id
                                        folderDropdownExpanded = false
                                    },
                                )
                            }
                        }

                        TextButton(onClick = { newFolderMode = true }) {
                            Text("+ New Folder")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            label = { Text("Folder Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                if (newFolderName.isNotBlank()) {
                                    onCreateFolder(newFolderName.trim())
                                    newFolderMode = false
                                    newFolderName = ""
                                }
                            },
                        ) {
                            Text("Save")
                        }
                        TextButton(onClick = { newFolderMode = false }) {
                            Text("Cancel")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (urlText.isNotBlank()) {
                        onAddFeed(
                            urlText.trim(),
                            titleText.trim().ifBlank { null },
                            selectedFolderId,
                        )
                    }
                },
                enabled = urlText.isNotBlank(),
            ) {
                Text("Subscribe")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
