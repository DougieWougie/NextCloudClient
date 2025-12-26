package com.nextcloud.sync.ui.screens.filebrowser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nextcloud.sync.ui.components.AppTopBar
import com.nextcloud.sync.ui.components.EmptyState
import com.nextcloud.sync.ui.components.ErrorDialog
import com.nextcloud.sync.ui.components.LoadingIndicator
import com.nextcloud.sync.ui.components.RemoteFolderItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel,
    onNavigateBack: (String?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // Handle back navigation
    BackHandler(enabled = viewModel.canNavigateUp()) {
        viewModel.onEvent(FileBrowserEvent.NavigateUp)
    }

    // Handle navigation back with selected path
    LaunchedEffect(uiState.shouldNavigateBack) {
        if (uiState.shouldNavigateBack) {
            onNavigateBack(uiState.selectedPath)
        }
    }

    // Show error dialog
    if (uiState.errorMessage != null) {
        ErrorDialog(
            message = uiState.errorMessage!!,
            onDismiss = { viewModel.onEvent(FileBrowserEvent.ErrorDismissed) }
        )
    }

    // Show create folder dialog
    if (uiState.showCreateFolderDialog) {
        CreateFolderDialog(
            folderName = uiState.newFolderName,
            error = uiState.newFolderError,
            onFolderNameChange = { viewModel.onEvent(FileBrowserEvent.NewFolderNameChanged(it)) },
            onConfirm = { viewModel.onEvent(FileBrowserEvent.ConfirmCreateFolder) },
            onDismiss = { viewModel.onEvent(FileBrowserEvent.DismissCreateFolderDialog) }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                AppTopBar(
                    title = "Select Folder",
                    onNavigateBack = { onNavigateBack(null) },
                    scrollBehavior = scrollBehavior
                )

                // Search bar
                SearchBar(
                    query = uiState.searchQuery,
                    onQueryChange = { viewModel.onEvent(FileBrowserEvent.SearchQueryChanged(it)) },
                    onSearch = { /* Optional: handle search submit */ },
                    active = false,
                    onActiveChange = { },
                    placeholder = { Text("Search folders") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onEvent(FileBrowserEvent.SearchQueryChanged("")) }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {}

                // Breadcrumb navigation
                if (uiState.breadcrumbs.size > 1) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.breadcrumbs) { breadcrumb ->
                            val isLast = breadcrumb == uiState.breadcrumbs.last()
                            FilterChip(
                                selected = isLast,
                                onClick = {
                                    if (!isLast) {
                                        viewModel.onEvent(FileBrowserEvent.BreadcrumbClicked(breadcrumb.path))
                                    }
                                },
                                label = { Text(breadcrumb.label) }
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            BottomAppBar {
                Button(
                    onClick = { viewModel.onEvent(FileBrowserEvent.SelectCurrentFolderClicked) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Text("Select \"${uiState.currentPath}\"")
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.onEvent(FileBrowserEvent.CreateFolderClicked) }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create folder")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (uiState.isLoading) {
            LoadingIndicator()
        } else if (uiState.isEmpty) {
            EmptyState(
                message = uiState.emptyStateMessage,
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(uiState.filteredFolders) { folder ->
                    RemoteFolderItem(
                        folder = folder,
                        onClick = { viewModel.onEvent(FileBrowserEvent.FolderClicked(folder.name)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateFolderDialog(
    folderName: String,
    error: String?,
    onFolderNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Folder") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = onFolderNameChange,
                label = { Text("Folder name") },
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
