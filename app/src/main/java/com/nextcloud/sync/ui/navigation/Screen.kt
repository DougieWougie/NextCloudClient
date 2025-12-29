package com.nextcloud.sync.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    object Login : Screen("login")

    data class WebLogin(val serverUrl: String) : Screen("web_login/{serverUrl}") {
        companion object {
            const val ROUTE = "web_login/{serverUrl}"
            fun createRoute(serverUrl: String) = "web_login/${Uri.encode(serverUrl)}"
        }
    }

    data class TwoFactor(val accountId: Long) : Screen("two_factor/{accountId}") {
        companion object {
            const val ROUTE = "two_factor/{accountId}"
            fun createRoute(accountId: Long) = "two_factor/$accountId"
        }
    }

    object MainContainer : Screen("main_container")
    object AddFolder : Screen("add_folder")

    data class EditFolder(val folderId: Long) : Screen("edit_folder/{folderId}") {
        companion object {
            const val ROUTE = "edit_folder/{folderId}"
            fun createRoute(folderId: Long) = "edit_folder/$folderId"
        }
    }

    data class FileBrowser(val currentPath: String) : Screen("file_browser") {
        companion object {
            const val ROUTE = "file_browser?currentPath={currentPath}"
            fun createRoute(currentPath: String = "/") =
                "file_browser?currentPath=${Uri.encode(currentPath)}"
        }
    }

    object ConflictResolution : Screen("conflict_resolution")
    object Settings : Screen("settings")
}
