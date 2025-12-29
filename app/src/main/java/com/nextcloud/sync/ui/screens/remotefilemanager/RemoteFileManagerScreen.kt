package com.nextcloud.sync.ui.screens.remotefilemanager

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.nextcloud.sync.ui.components.ErrorDialog
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Screen for browsing remote Nextcloud files.
 * Allows multi-select and adding files to individual sync.
 */
@Composable
fun RemoteFileManagerScreen(
    navController: NavHostController,
    context: Context,
    modifier: Modifier = Modifier,
    viewModel: RemoteFileManagerViewModel = viewModel(
        factory = RemoteFileManagerViewModel.Factory(context)
    )
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        when {
            uiState.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            uiState.errorMessage != null -> {
                ErrorDialog(
                    message = uiState.errorMessage ?: "Unknown error",
                    onDismiss = { viewModel.onEvent(RemoteFileManagerEvent.ClearError) }
                )
            }
            uiState.items.isEmpty() -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No files in this folder",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                FileListContent(
                    uiState = uiState,
                    onEvent = viewModel::onEvent
                )
            }
        }
    }
}

@Composable
private fun FileListContent(
    uiState: RemoteFileManagerUiState,
    onEvent: (RemoteFileManagerEvent) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Breadcrumb navigation
        if (uiState.currentPath != "/") {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(uiState.breadcrumbs) { breadcrumb ->
                    FilterChip(
                        selected = false,
                        onClick = { onEvent(RemoteFileManagerEvent.NavigateToBreadcrumb(breadcrumb.path)) },
                        label = { Text(breadcrumb.name) }
                    )
                }
            }
            HorizontalDivider()
        }

        // File list
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(uiState.items) { item ->
                RemoteFileCard(
                    item = item,
                    isSelected = uiState.selectedFiles.contains(item.path),
                    isMultiSelectMode = uiState.isMultiSelectMode,
                    onClick = {
                        if (uiState.isMultiSelectMode) {
                            onEvent(RemoteFileManagerEvent.ToggleSelection(item.path))
                        } else {
                            if (item.isDirectory) {
                                onEvent(RemoteFileManagerEvent.NavigateToFolder(item.name))
                            } else {
                                onEvent(RemoteFileManagerEvent.LongPress(item.path))
                            }
                        }
                    },
                    onLongClick = {
                        onEvent(RemoteFileManagerEvent.LongPress(item.path))
                    }
                )
            }
        }

        // Action bar for multi-select
        if (uiState.isMultiSelectMode && uiState.selectedFiles.isNotEmpty()) {
            BottomAppBar {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = { onEvent(RemoteFileManagerEvent.ExitMultiSelect) }) {
                        Text("Cancel")
                    }
                    FilledTonalButton(onClick = { onEvent(RemoteFileManagerEvent.AddToSync) }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add to Sync (${uiState.selectedFiles.size})")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteFileCard(
    item: RemoteFileItem,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        onClick = onLongClick
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() }
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Icon(
                if (item.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = if (item.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (!item.isDirectory) {
                    Text(
                        text = "${formatSize(item.size)} • ${formatDate(item.lastModified)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (item.isDirectory) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return sdf.format(timestamp)
}
