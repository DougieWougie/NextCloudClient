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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
 * Screen for browsing and managing remote Nextcloud files.
 * Provides file operations: download, rename, move, copy, and delete.
 */
@Composable
fun RemoteFileManagerScreen(
    navController: NavHostController,
    context: Context,
    modifier: Modifier = Modifier,
    viewModel: RemoteFileManagerViewModel = viewModel(
        factory = RemoteFileManagerViewModel.Factory(context)
    ),
    onNavigationStateChanged: (currentPath: String, canNavigateUp: Boolean) -> Unit = { _, _ -> },
    onMultiSelectStateChanged: (isMultiSelect: Boolean, selectedCount: Int) -> Unit = { _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsState()

    // Notify parent about navigation state changes
    LaunchedEffect(uiState.currentPath) {
        onNavigationStateChanged(uiState.currentPath, uiState.currentPath != "/")
    }

    // Notify parent about multi-select state changes
    LaunchedEffect(uiState.isMultiSelectMode, uiState.selectedFiles.size) {
        onMultiSelectStateChanged(uiState.isMultiSelectMode, uiState.selectedFiles.size)
    }

    // Render dialogs
    uiState.renameDialogCurrentName?.let { currentName ->
        uiState.renameDialogPath?.let { path ->
            if (uiState.showRenameDialog) {
                RenameFileDialog(
                    currentName = currentName,
                    onConfirm = { newName ->
                        viewModel.onEvent(RemoteFileManagerEvent.ConfirmRename(path, newName))
                    },
                    onDismiss = { viewModel.onEvent(RemoteFileManagerEvent.DismissDialog) }
                )
            }
        }
    }

    uiState.deleteConfirmName?.let { fileName ->
        uiState.deleteConfirmPath?.let { path ->
            if (uiState.showDeleteConfirmDialog) {
                DeleteConfirmDialog(
                    fileName = fileName,
                    onConfirm = {
                        viewModel.onEvent(RemoteFileManagerEvent.ConfirmDelete(path))
                    },
                    onDismiss = { viewModel.onEvent(RemoteFileManagerEvent.DismissDialog) }
                )
            }
        }
    }

    uiState.moveDialogSourcePath?.let { sourcePath ->
        if (uiState.showMoveDialog) {
            val fileName = sourcePath.substringAfterLast('/')
            MoveFileDialog(
                fileName = fileName,
                currentPath = sourcePath,
                onConfirm = { destPath ->
                    viewModel.onEvent(RemoteFileManagerEvent.ConfirmMove(sourcePath, destPath))
                },
                onDismiss = { viewModel.onEvent(RemoteFileManagerEvent.DismissDialog) }
            )
        }
    }

    uiState.copyDialogSourcePath?.let { sourcePath ->
        if (uiState.showCopyDialog) {
            val fileName = sourcePath.substringAfterLast('/')
            CopyFileDialog(
                fileName = fileName,
                currentPath = sourcePath,
                onConfirm = { destPath ->
                    viewModel.onEvent(RemoteFileManagerEvent.ConfirmCopy(sourcePath, destPath))
                },
                onDismiss = { viewModel.onEvent(RemoteFileManagerEvent.DismissDialog) }
            )
        }
    }

    uiState.downloadDialogPath?.let { downloadPath ->
        if (uiState.showDownloadDialog) {
            val fileName = downloadPath.substringAfterLast('/')
            DownloadDestinationDialog(
                fileName = fileName,
                availableFolders = uiState.availableFolders,
                onConfirm = { folderId ->
                    viewModel.onEvent(RemoteFileManagerEvent.ConfirmDownload(downloadPath, folderId))
                },
                onDismiss = { viewModel.onEvent(RemoteFileManagerEvent.DismissDialog) }
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Show error dialog if there's an error
        uiState.errorMessage?.let { error ->
            ErrorDialog(
                message = error,
                onDismiss = { viewModel.onEvent(RemoteFileManagerEvent.ClearError) }
            )
        }

        when {
            uiState.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileListContent(
    uiState: RemoteFileManagerUiState,
    onEvent: (RemoteFileManagerEvent) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { onEvent(RemoteFileManagerEvent.Refresh) },
            modifier = Modifier.weight(1f)
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
                    modifier = Modifier.fillMaxSize(),
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
                                    }
                                    // For files, clicking does nothing (context menu handles operations)
                                }
                            },
                            onEvent = onEvent
                        )
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
    onEvent: (RemoteFileManagerEvent) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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

            if (item.isDirectory && !isMultiSelectMode) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (!item.isDirectory && !isMultiSelectMode) {
                // Context menu for files (only when not in multi-select mode)
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Download") },
                            onClick = {
                                showMenu = false
                                onEvent(RemoteFileManagerEvent.ShowDownloadDialog(item.path))
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Download, contentDescription = null)
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                showMenu = false
                                onEvent(RemoteFileManagerEvent.ShowRenameDialog(item.path, item.name))
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Edit, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy") },
                            onClick = {
                                showMenu = false
                                onEvent(RemoteFileManagerEvent.ShowCopyDialog(item.path))
                            },
                            leadingIcon = {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Move") },
                            onClick = {
                                showMenu = false
                                onEvent(RemoteFileManagerEvent.ShowMoveDialog(item.path))
                            },
                            leadingIcon = {
                                Icon(Icons.Default.DriveFileMove, contentDescription = null)
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                showMenu = false
                                onEvent(RemoteFileManagerEvent.ShowDeleteDialog(item.path))
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameFileDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename File") },
        text = {
            Column {
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        errorMessage = when {
                            it.isEmpty() -> "Name cannot be empty"
                            it.contains("/") || it.contains("\\") -> "Name cannot contain / or \\"
                            else -> null
                        }
                    },
                    label = { Text("New name") },
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newName) },
                enabled = errorMessage == null && newName.isNotEmpty() && newName != currentName
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DeleteConfirmDialog(
    fileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("Delete File") },
        text = {
            Text("Are you sure you want to delete \"$fileName\" from the server? This action cannot be undone.")
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun MoveFileDialog(
    fileName: String,
    currentPath: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var destPath by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move File") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Moving: $fileName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "From: $currentPath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = destPath,
                    onValueChange = {
                        destPath = it
                        errorMessage = when {
                            it.isEmpty() -> "Destination cannot be empty"
                            !it.startsWith("/") -> "Path must start with /"
                            else -> null
                        }
                    },
                    label = { Text("Destination folder path") },
                    placeholder = { Text("/destination/folder") },
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(destPath) },
                enabled = errorMessage == null && destPath.isNotEmpty()
            ) {
                Text("Move")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CopyFileDialog(
    fileName: String,
    currentPath: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var destPath by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy File") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Copying: $fileName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "From: $currentPath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = destPath,
                    onValueChange = {
                        destPath = it
                        errorMessage = when {
                            it.isEmpty() -> "Destination cannot be empty"
                            !it.startsWith("/") -> "Path must start with /"
                            else -> null
                        }
                    },
                    label = { Text("Destination folder path") },
                    placeholder = { Text("/destination/folder") },
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(destPath) },
                enabled = errorMessage == null && destPath.isNotEmpty()
            ) {
                Text("Copy")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DownloadDestinationDialog(
    fileName: String,
    availableFolders: List<com.nextcloud.sync.models.database.entities.FolderEntity>,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedFolderId by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download File") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Select destination folder for: $fileName",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (availableFolders.isEmpty()) {
                    Text(
                        "No sync folders available. Please create a sync folder first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(availableFolders) { folder ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selectedFolderId == folder.id) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                ),
                                onClick = { selectedFolderId = folder.id }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedFolderId == folder.id,
                                        onClick = { selectedFolderId = folder.id }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = folder.remotePath,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = folder.localPath,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedFolderId?.let { onConfirm(it) }
                },
                enabled = selectedFolderId != null
            ) {
                Text("Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
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
