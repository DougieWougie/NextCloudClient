package com.nextcloud.sync.ui.screens.maincontainer

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.models.repository.FolderRepository
import com.nextcloud.sync.ui.navigation.Screen
import com.nextcloud.sync.ui.screens.main.MainScreen
import com.nextcloud.sync.ui.screens.main.MainViewModel
import com.nextcloud.sync.ui.screens.localfilemanager.LocalFileManagerScreen
import com.nextcloud.sync.ui.screens.remotefilemanager.RemoteFileManagerScreen
import androidx.lifecycle.ViewModelProvider

/**
 * Main container screen with Material3 bottom navigation.
 *
 * Hosts three tabs:
 * - Local Files: Browse files in sync folders
 * - Remote Files: Browse all Nextcloud files with multi-select
 * - Sync: Sync folder management (current MainScreen)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.MainContainerScreen(
    navController: NavHostController,
    context: Context
) {
    var selectedTab by remember { mutableStateOf(BottomNavDestination.LocalFiles) }
    var remoteCurrentPath by remember { mutableStateOf("/") }
    var remoteCanNavigateUp by remember { mutableStateOf(false) }
    var remoteIsMultiSelectMode by remember { mutableStateOf(false) }
    var remoteSelectedCount by remember { mutableStateOf(0) }

    // Create Remote File Manager ViewModel lazily - only when Remote Files tab is selected
    // This prevents unnecessary network requests on app startup
    var remoteFileManagerViewModel: com.nextcloud.sync.ui.screens.remotefilemanager.RemoteFileManagerViewModel? by remember { mutableStateOf(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when (selectedTab) {
                        BottomNavDestination.LocalFiles -> "Local Files"
                        BottomNavDestination.RemoteFiles -> {
                            if (remoteIsMultiSelectMode) {
                                "$remoteSelectedCount selected"
                            } else if (remoteCurrentPath == "/") {
                                "Remote Files"
                            } else {
                                remoteCurrentPath.substringAfterLast('/')
                            }
                        }
                        BottomNavDestination.Sync -> "Nextcloud Sync"
                    })
                },
                navigationIcon = {
                    when {
                        // Show close button in multi-select mode on Remote Files tab
                        selectedTab == BottomNavDestination.RemoteFiles && remoteIsMultiSelectMode -> {
                            IconButton(onClick = {
                                remoteFileManagerViewModel?.onEvent(
                                    com.nextcloud.sync.ui.screens.remotefilemanager.RemoteFileManagerEvent.ExitMultiSelect
                                )
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Exit multi-select"
                                )
                            }
                        }
                        // Show back button on Remote Files tab when not at root
                        selectedTab == BottomNavDestination.RemoteFiles && remoteCanNavigateUp -> {
                            IconButton(onClick = {
                                remoteFileManagerViewModel?.onEvent(
                                    com.nextcloud.sync.ui.screens.remotefilemanager.RemoteFileManagerEvent.NavigateUp
                                )
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Navigate up"
                                )
                            }
                        }
                    }
                },
                actions = {
                    // Show multi-select button on Remote Files tab
                    if (selectedTab == BottomNavDestination.RemoteFiles && !remoteIsMultiSelectMode) {
                        IconButton(onClick = {
                            remoteFileManagerViewModel?.onEvent(
                                com.nextcloud.sync.ui.screens.remotefilemanager.RemoteFileManagerEvent.ToggleMultiSelect
                            )
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Multi-select")
                        }
                    }

                    // Show Add Folder button only on Sync tab
                    if (selectedTab == BottomNavDestination.Sync) {
                        IconButton(onClick = { navController.navigate(Screen.AddFolder.route) }) {
                            Icon(Icons.Default.Add, contentDescription = "Add folder")
                        }
                    }
                    IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == BottomNavDestination.LocalFiles,
                    onClick = { selectedTab = BottomNavDestination.LocalFiles },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("Local") }
                )
                NavigationBarItem(
                    selected = selectedTab == BottomNavDestination.RemoteFiles,
                    onClick = { selectedTab = BottomNavDestination.RemoteFiles },
                    icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
                    label = { Text("Remote") }
                )
                NavigationBarItem(
                    selected = selectedTab == BottomNavDestination.Sync,
                    onClick = { selectedTab = BottomNavDestination.Sync },
                    icon = { Icon(Icons.Default.Sync, contentDescription = null) },
                    label = { Text("Sync") }
                )
            }
        }
    ) { paddingValues ->
        when (selectedTab) {
            BottomNavDestination.LocalFiles -> {
                LocalFileManagerScreen(
                    navController = navController,
                    context = context,
                    modifier = Modifier.padding(paddingValues)
                )
            }
            BottomNavDestination.RemoteFiles -> {
                // Create ViewModel lazily - only on first access to Remote Files tab
                val viewModel = remoteFileManagerViewModel ?: viewModel<com.nextcloud.sync.ui.screens.remotefilemanager.RemoteFileManagerViewModel>(
                    factory = com.nextcloud.sync.ui.screens.remotefilemanager.RemoteFileManagerViewModel.Factory(context)
                ).also { remoteFileManagerViewModel = it }

                RemoteFileManagerScreen(
                    navController = navController,
                    context = context,
                    modifier = Modifier.padding(paddingValues),
                    viewModel = viewModel,
                    onNavigationStateChanged = { currentPath, canNavigateUp ->
                        remoteCurrentPath = currentPath
                        remoteCanNavigateUp = canNavigateUp
                    },
                    onMultiSelectStateChanged = { isMultiSelect, selectedCount ->
                        remoteIsMultiSelectMode = isMultiSelect
                        remoteSelectedCount = selectedCount
                    }
                )
            }
            BottomNavDestination.Sync -> {
                val db = AppDatabase.getInstance(context)
                val folderRepository = FolderRepository(db.folderDao())
                val accountRepository = AccountRepository(db.accountDao())

                val mainViewModel: MainViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return MainViewModel(folderRepository, accountRepository, context) as T
                        }
                    }
                )

                // Use AnimatedVisibility to provide AnimatedVisibilityScope for MainScreen
                AnimatedVisibility(visible = true) {
                    MainScreen(
                        viewModel = mainViewModel,
                        navController = navController,
                        animatedVisibilityScope = this@AnimatedVisibility,
                        onAddFolderClick = { navController.navigate(Screen.AddFolder.route) },
                        modifier = Modifier.padding(paddingValues)
                    )
                }
            }
        }
    }
}

enum class BottomNavDestination {
    LocalFiles,
    RemoteFiles,
    Sync
}
