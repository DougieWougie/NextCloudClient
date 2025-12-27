package com.nextcloud.sync.ui.navigation

import android.content.Context
import android.net.Uri
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nextcloud.sync.controllers.auth.TwoFactorController
import com.nextcloud.sync.controllers.sync.ConflictResolutionController
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.network.NextcloudAuthenticator
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.models.repository.ConflictRepository
import com.nextcloud.sync.models.repository.FileRepository
import com.nextcloud.sync.models.repository.FolderRepository
import com.nextcloud.sync.ui.screens.conflicts.ConflictResolutionScreen
import com.nextcloud.sync.ui.screens.conflicts.ConflictResolutionViewModel
import com.nextcloud.sync.ui.screens.login.LoginScreen
import com.nextcloud.sync.ui.screens.login.LoginViewModel
import com.nextcloud.sync.ui.screens.twofactor.TwoFactorScreen
import com.nextcloud.sync.ui.screens.twofactor.TwoFactorViewModel
import com.nextcloud.sync.ui.screens.settings.SettingsScreen
import com.nextcloud.sync.ui.screens.settings.SettingsViewModel
import com.nextcloud.sync.ui.screens.editfolder.EditFolderScreen
import com.nextcloud.sync.ui.screens.editfolder.EditFolderViewModel
import com.nextcloud.sync.ui.screens.editfolder.EditFolderEvent
import com.nextcloud.sync.ui.screens.addfolder.AddFolderScreen
import com.nextcloud.sync.ui.screens.addfolder.AddFolderViewModel
import com.nextcloud.sync.ui.screens.addfolder.AddFolderEvent
import com.nextcloud.sync.ui.screens.filebrowser.FileBrowserScreen
import com.nextcloud.sync.ui.screens.filebrowser.FileBrowserViewModel
import com.nextcloud.sync.ui.screens.main.MainScreen
import com.nextcloud.sync.ui.screens.main.MainViewModel
import com.nextcloud.sync.ui.screens.weblogin.WebLoginScreen
import com.nextcloud.sync.ui.screens.weblogin.WebLoginViewModel
import com.nextcloud.sync.utils.AuthRateLimiter
import com.nextcloud.sync.utils.EncryptionUtil

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NavGraph(
    navController: NavHostController,
    context: Context,
    startDestination: String = Screen.Login.route
) {
    val db = AppDatabase.getInstance(context)
    val accountRepository = AccountRepository(db.accountDao())

    SharedTransitionLayout {
        NavHost(
            navController = navController,
            startDestination = startDestination
        ) {
        // Login Screen
        composable(Screen.Login.route) {
            val viewModel = LoginViewModel(accountRepository)
            LoginScreen(
                viewModel = viewModel,
                onNavigateToMain = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNavigateToWebLogin = { serverUrl ->
                    navController.navigate(Screen.WebLogin.createRoute(serverUrl))
                }
            )
        }

        // Two-Factor Screen
        composable(
            route = Screen.TwoFactor.ROUTE,
            arguments = listOf(navArgument("accountId") { type = NavType.LongType })
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getLong("accountId") ?: 0L
            val rateLimiter = AuthRateLimiter.getInstance(context)
            val authenticator = NextcloudAuthenticator(context)
            val twoFactorController = TwoFactorController(accountRepository, authenticator, rateLimiter)
            val viewModel = TwoFactorViewModel(accountId, twoFactorController)

            TwoFactorScreen(
                viewModel = viewModel,
                onNavigateToMain = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        // Conflict Resolution Screen
        composable(Screen.ConflictResolution.route) {
            // Note: ConflictController needs account context
            // For now, we'll create a simple implementation
            // In a real app, you'd get the active account first
            val conflictRepository = ConflictRepository(db.conflictDao())
            val fileRepository = FileRepository(db.fileDao())

            // This is a temporary placeholder - in real implementation,
            // we would get the account and create proper WebDavClient
            // For now, ConflictResolutionViewModel will handle initialization
            val viewModel = ConflictResolutionViewModel(
                ConflictResolutionController(
                    conflictRepository,
                    fileRepository,
                    WebDavClient(context, "", "", "") // Placeholder
                )
            )

            ConflictResolutionScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.navigateUp() }
            )
        }

        // Settings Screen
        composable(Screen.Settings.route) {
            val viewModel = SettingsViewModel(accountRepository, context)
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.navigateUp() },
                onNavigateToLogin = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Edit Folder Screen
        composable(
            route = Screen.EditFolder.ROUTE,
            arguments = listOf(navArgument("folderId") { type = NavType.LongType })
        ) { backStackEntry ->
            val folderId = backStackEntry.arguments?.getLong("folderId") ?: 0L
            val folderRepository = FolderRepository(db.folderDao())

            // Use viewModel with factory to properly scope the ViewModel
            // This ensures the ViewModel survives navigation to FileBrowser and back
            val viewModel: EditFolderViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return EditFolderViewModel(folderId, folderRepository, context) as T
                    }
                }
            )

            // Observe result from FileBrowser using LaunchedEffect
            val savedStateHandle = backStackEntry.savedStateHandle
            androidx.compose.runtime.LaunchedEffect(savedStateHandle) {
                savedStateHandle.getStateFlow<String?>("selected_path", null).collect { selectedPath ->
                    selectedPath?.let {
                        viewModel.onEvent(EditFolderEvent.RemotePathSelected(it))
                        savedStateHandle.remove<String>("selected_path")
                    }
                }
            }

            EditFolderScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.navigateUp() },
                onNavigateToFileBrowser = { currentPath ->
                    navController.navigate(Screen.FileBrowser.createRoute(currentPath))
                },
                animatedVisibilityScope = this,
                folderId = folderId
            )
        }

        // Add Folder Screen
        composable(Screen.AddFolder.route) { backStackEntry ->
            val folderRepository = FolderRepository(db.folderDao())

            // Use viewModel with factory to properly scope the ViewModel
            // This ensures the ViewModel survives navigation to FileBrowser and back
            val viewModel: AddFolderViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return AddFolderViewModel(folderRepository, accountRepository, context) as T
                    }
                }
            )

            // Observe result from FileBrowser using LaunchedEffect
            val savedStateHandle = backStackEntry.savedStateHandle
            androidx.compose.runtime.LaunchedEffect(savedStateHandle) {
                savedStateHandle.getStateFlow<String?>("selected_path", null).collect { selectedPath ->
                    selectedPath?.let {
                        viewModel.onEvent(AddFolderEvent.RemotePathSelected(it))
                        savedStateHandle.remove<String>("selected_path")
                    }
                }
            }

            AddFolderScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.navigateUp() },
                onNavigateToFileBrowser = { currentPath ->
                    navController.navigate(Screen.FileBrowser.createRoute(currentPath))
                }
            )
        }

        // File Browser Screen
        composable(
            route = Screen.FileBrowser.ROUTE,
            arguments = listOf(navArgument("currentPath") {
                type = NavType.StringType
                defaultValue = "/"
            })
        ) { backStackEntry ->
            val currentPath = backStackEntry.arguments?.getString("currentPath") ?: "/"
            val viewModel = FileBrowserViewModel(currentPath, accountRepository, context)

            FileBrowserScreen(
                viewModel = viewModel,
                onNavigateBack = { selectedPath ->
                    // If a path was selected, pass it back to the previous screen
                    selectedPath?.let {
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("selected_path", it)
                    }
                    navController.navigateUp()
                }
            )
        }

        // Main Screen
        composable(Screen.Main.route) {
            val folderRepository = FolderRepository(db.folderDao())
            val viewModel = MainViewModel(folderRepository, accountRepository, context)

            MainScreen(
                viewModel = viewModel,
                navController = navController,
                animatedVisibilityScope = this
            )
        }

        // Web Login Screen
        composable(
            route = Screen.WebLogin.ROUTE,
            arguments = listOf(navArgument("serverUrl") { type = NavType.StringType })
        ) { backStackEntry ->
            val serverUrl = backStackEntry.arguments?.getString("serverUrl") ?: ""
            val viewModel = WebLoginViewModel(
                serverUrl = Uri.decode(serverUrl),
                accountRepository = accountRepository,
                context = context
            )

            WebLoginScreen(
                viewModel = viewModel,
                onNavigateToMain = {
                    navController.navigate(Screen.Main.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.navigateUp() }
            )
        }
        }
    }
}
