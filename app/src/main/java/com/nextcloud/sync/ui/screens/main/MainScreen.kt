package com.nextcloud.sync.ui.screens.main

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.nextcloud.sync.R
import com.nextcloud.sync.ui.components.ConfirmationDialog
import com.nextcloud.sync.ui.components.EmptyState
import com.nextcloud.sync.ui.components.LoadingIndicator
import com.nextcloud.sync.ui.components.SyncFolderCard
import com.nextcloud.sync.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.MainScreen(
    viewModel: MainViewModel,
    navController: NavHostController,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuExpanded by remember { mutableStateOf(false) }

    // Show snackbar messages
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onEvent(MainEvent.MessageDismissed)
        }
    }

    // Refresh on resume
    LaunchedEffect(Unit) {
        viewModel.onEvent(MainEvent.Refresh)
    }

    // Delete confirmation dialog
    if (uiState.showDeleteDialog) {
        ConfirmationDialog(
            title = "Delete Sync Folder",
            message = "Are you sure you want to remove this folder from sync? Local files will not be deleted.",
            confirmText = "Delete",
            dismissText = "Cancel",
            onConfirm = { viewModel.onEvent(MainEvent.ConfirmDeleteFolder) },
            onDismiss = { viewModel.onEvent(MainEvent.DismissDeleteDialog) }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.AddFolder.route) }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add folder"
                        )
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options"
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                menuExpanded = false
                                navController.navigate(Screen.Settings.route)
                            }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (!uiState.isEmpty) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.onEvent(MainEvent.SyncAllClicked) },
                    icon = {
                        if (uiState.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(2.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null)
                        }
                    },
                    text = { Text("Sync All") }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (uiState.isLoading) {
            LoadingIndicator()
        } else if (uiState.isEmpty) {
            EmptyStateWithAddButton(
                onAddFolderClick = { navController.navigate(Screen.AddFolder.route) },
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Last sync time card
                item {
                    SyncStatusCard(
                        lastSyncTime = uiState.lastSyncTime,
                        isSyncing = uiState.isSyncing
                    )
                }

                // Folder list
                items(uiState.folders) { folder ->
                    SyncFolderCard(
                        folder = folder,
                        onFolderClick = {
                            viewModel.onEvent(MainEvent.FolderClicked(folder))
                        },
                        onSyncClick = {
                            viewModel.onEvent(MainEvent.SyncFolderClicked(folder))
                        },
                        onEditClick = {
                            navController.navigate(Screen.EditFolder.createRoute(folder.id))
                        },
                        onDeleteClick = {
                            viewModel.onEvent(MainEvent.DeleteFolderClicked(folder))
                        },
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncStatusCard(
    lastSyncTime: String,
    isSyncing: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = if (isSyncing) "Syncing..." else lastSyncTime,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .align(Alignment.CenterHorizontally),
                    strokeWidth = 3.dp
                )
            }
        }
    }
}

@Composable
private fun EmptyStateWithAddButton(
    onAddFolderClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "No sync folders configured",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Add a folder to start syncing with Nextcloud",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            ExtendedFloatingActionButton(
                onClick = onAddFolderClick,
                modifier = Modifier.padding(top = 24.dp),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add Folder") }
            )
        }
    }
}
