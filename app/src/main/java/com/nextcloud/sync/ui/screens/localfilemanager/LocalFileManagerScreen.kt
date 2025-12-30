package com.nextcloud.sync.ui.screens.localfilemanager

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.nextcloud.sync.models.database.entities.FolderEntity
import com.nextcloud.sync.ui.components.ErrorDialog
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Screen for browsing local files in sync folders.
 * Shows folder list, then files within selected folder.
 */
@Composable
fun LocalFileManagerScreen(
    navController: NavHostController,
    context: Context,
    modifier: Modifier = Modifier,
    viewModel: LocalFileManagerViewModel = viewModel(
        factory = LocalFileManagerViewModel.Factory(context)
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
                    onDismiss = { viewModel.onEvent(LocalFileManagerEvent.ClearError) }
                )
            }
            uiState.selectedFolder != null -> {
                FileListContent(
                    uiState = uiState,
                    onEvent = viewModel::onEvent
                )
            }
            uiState.folders.isEmpty() -> {
                EmptyFoldersState()
            }
            else -> {
                FolderListContent(
                    folders = uiState.folders,
                    onFolderClick = { viewModel.onEvent(LocalFileManagerEvent.SelectFolder(it)) }
                )
            }
        }
    }
}

@Composable
private fun FolderListContent(
    folders: List<FolderEntity>,
    onFolderClick: (FolderEntity) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(folders) { folder ->
            FolderCard(
                folder = folder,
                onClick = { onFolderClick(folder) }
            )
        }
    }
}

@Composable
private fun FolderCard(
    folder: FolderEntity,
    onClick: () -> Unit
) {
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
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.remotePath.substringAfterLast('/').ifEmpty { "Root" },
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = folder.remotePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileListContent(
    uiState: LocalFileManagerUiState,
    onEvent: (LocalFileManagerEvent) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Header with back button and multi-select toggle
        TopAppBar(
            title = {
                val folderName = uiState.selectedFolder?.remotePath?.substringAfterLast('/')?.ifEmpty { "Root" } ?: "Files"
                Text(folderName)
            },
            navigationIcon = {
                IconButton(onClick = { onEvent(LocalFileManagerEvent.NavigateBack) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { onEvent(LocalFileManagerEvent.ToggleMultiSelectMode) }) {
                    Icon(
                        imageVector = if (uiState.isMultiSelectMode) Icons.Default.Close else Icons.Default.CheckCircle,
                        contentDescription = if (uiState.isMultiSelectMode) "Exit multi-select" else "Multi-select",
                        tint = if (uiState.isMultiSelectMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        )

        if (uiState.files.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
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
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Files will appear here once synced",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // File list
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.files) { file ->
                    LocalFileCard(
                        file = file,
                        isSelected = uiState.selectedFiles.contains(file.path),
                        isMultiSelectMode = uiState.isMultiSelectMode,
                        onClick = {
                            if (uiState.isMultiSelectMode) {
                                onEvent(LocalFileManagerEvent.ToggleSelection(file.path))
                            } else {
                                onEvent(LocalFileManagerEvent.FileClicked(file))
                            }
                        },
                        onDownload = { onEvent(LocalFileManagerEvent.DownloadFile(file.path)) },
                        onDelete = { onEvent(LocalFileManagerEvent.DeleteFile(file.path)) },
                        onRename = { newName -> onEvent(LocalFileManagerEvent.RenameFile(file.path, newName)) },
                        onCopy = { dest -> onEvent(LocalFileManagerEvent.CopyFile(file.path, dest)) },
                        onMove = { dest -> onEvent(LocalFileManagerEvent.MoveFile(file.path, dest)) }
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
                        TextButton(onClick = { onEvent(LocalFileManagerEvent.ExitMultiSelect) }) {
                            Text("Cancel")
                        }
                        Text(
                            "${uiState.selectedFiles.size} selected",
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalFileCard(
    file: LocalFileItem,
    isSelected: Boolean,
    isMultiSelectMode: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onCopy: (String) -> Unit,
    onMove: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }

    // Rename dialog
    if (showRenameDialog) {
        RenameFileDialog(
            currentName = file.name,
            onConfirm = { newName ->
                showRenameDialog = false
                if (newName.isNotBlank() && newName != file.name) {
                    onRename(newName)
                }
            },
            onDismiss = { showRenameDialog = false }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete File") },
            text = { Text("Are you sure you want to delete \"${file.name}\"? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Copy destination dialog
    if (showCopyDialog) {
        DestinationPickerDialog(
            title = "Copy to",
            fileName = file.name,
            onConfirm = { destination ->
                showCopyDialog = false
                onCopy(destination)
            },
            onDismiss = { showCopyDialog = false }
        )
    }

    // Move destination dialog
    if (showMoveDialog) {
        DestinationPickerDialog(
            title = "Move to",
            fileName = file.name,
            onConfirm = { destination ->
                showMoveDialog = false
                onMove(destination)
            },
            onDismiss = { showMoveDialog = false }
        )
    }

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
                if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = if (file.isDirectory)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (!file.isDirectory) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = formatSize(file.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatDate(file.lastModified),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Sync status indicator
            if (!file.isDirectory && !isMultiSelectMode) {
                Icon(
                    when (file.syncStatus) {
                        "SYNCED" -> Icons.Default.CheckCircle
                        "PENDING" -> Icons.Default.Sync
                        "ERROR" -> Icons.Default.Error
                        else -> Icons.Default.HelpOutline
                    },
                    contentDescription = file.syncStatus,
                    tint = when (file.syncStatus) {
                        "SYNCED" -> MaterialTheme.colorScheme.primary
                        "PENDING" -> MaterialTheme.colorScheme.secondary
                        "ERROR" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            // More menu (only when not in multi-select mode)
            if (!isMultiSelectMode && !file.isDirectory) {
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
                                onDownload()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Download, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                showMenu = false
                                showRenameDialog = true
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Edit, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                showMenu = false
                                showDeleteDialog = true
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy") },
                            onClick = {
                                showMenu = false
                                showCopyDialog = true
                            },
                            leadingIcon = {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Move") },
                            onClick = {
                                showMenu = false
                                showMoveDialog = true
                            },
                            leadingIcon = {
                                Icon(Icons.Default.DriveFileMove, contentDescription = null)
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
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename File") },
        text = {
            Column {
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        error = when {
                            it.isBlank() -> "Filename cannot be empty"
                            it.contains("/") || it.contains("\\") -> "Invalid characters in filename"
                            else -> null
                        }
                    },
                    label = { Text("New filename") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (error == null && newName.isNotBlank()) {
                                onConfirm(newName.trim())
                            }
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newName.trim()) },
                enabled = error == null && newName.isNotBlank()
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
private fun DestinationPickerDialog(
    title: String,
    fileName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var destinationPath by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = "File: $fileName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = destinationPath,
                    onValueChange = {
                        destinationPath = it
                        error = when {
                            it.isBlank() -> "Destination path cannot be empty"
                            else -> null
                        }
                    },
                    label = { Text("Destination folder path") },
                    placeholder = { Text("/path/to/folder") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (error == null && destinationPath.isNotBlank()) {
                                onConfirm(destinationPath.trim())
                            }
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Enter the full path to the destination folder",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(destinationPath.trim()) },
                enabled = error == null && destinationPath.isNotBlank()
            ) {
                Text(title.substringBefore(" ")) // "Copy" or "Move"
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
private fun EmptyFoldersState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.FolderOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "No sync folders configured",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Add a sync folder to see local files here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
