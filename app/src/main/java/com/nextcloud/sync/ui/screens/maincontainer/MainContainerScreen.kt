package com.nextcloud.sync.ui.screens.maincontainer

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
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
    var selectedTab by remember { mutableStateOf(BottomNavDestination.Sync) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when (selectedTab) {
                        BottomNavDestination.LocalFiles -> "Local Files"
                        BottomNavDestination.RemoteFiles -> "Remote Files"
                        BottomNavDestination.Sync -> "Nextcloud Sync"
                    })
                },
                actions = {
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
                RemoteFileManagerScreen(
                    navController = navController,
                    context = context,
                    modifier = Modifier.padding(paddingValues)
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
