package com.nextcloud.sync.ui.screens.remotefilemanager

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nextcloud.sync.controllers.fileops.RemoteFileManagerController
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.models.repository.IndividualFileSyncRepository
import com.nextcloud.sync.utils.EncryptionUtil
import com.nextcloud.sync.utils.SafeLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Date

class RemoteFileManagerViewModel(
    private val controller: RemoteFileManagerController,
    private val accountRepository: AccountRepository,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoteFileManagerUiState())
    val uiState: StateFlow<RemoteFileManagerUiState> = _uiState.asStateFlow()

    init {
        loadFiles()
    }

    fun onEvent(event: RemoteFileManagerEvent) {
        when (event) {
            is RemoteFileManagerEvent.NavigateToFolder -> navigateToFolder(event.folderName)
            is RemoteFileManagerEvent.NavigateToBreadcrumb -> navigateToPath(event.path)
            is RemoteFileManagerEvent.LongPress -> enterMultiSelect(event.path)
            is RemoteFileManagerEvent.ToggleSelection -> toggleSelection(event.path)
            is RemoteFileManagerEvent.ExitMultiSelect -> exitMultiSelect()
            is RemoteFileManagerEvent.AddToSync -> addSelectedToSync()
            is RemoteFileManagerEvent.ClearError -> clearError()
        }
    }

    private fun loadFiles(path: String = "/") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                // Get user email for hidden directory filtering
                val userEmail = accountRepository.getActiveAccount()?.username

                val items = controller.listFilesAndFolders(path, userEmail)
                val breadcrumbs = buildBreadcrumbs(path)

                _uiState.update {
                    it.copy(
                        currentPath = path,
                        items = items.map { item ->
                            RemoteFileItem(
                                path = item.path,
                                name = item.name,
                                size = item.size,
                                lastModified = item.lastModified.time,
                                isDirectory = item.isDirectory
                            )
                        },
                        breadcrumbs = breadcrumbs,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                SafeLogger.e("RemoteFileManagerViewModel", "Failed to load files", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load files: ${e.message}"
                    )
                }
            }
        }
    }

    private fun navigateToFolder(folderName: String) {
        val currentPath = _uiState.value.currentPath
        val newPath = if (currentPath.endsWith("/")) {
            "$currentPath$folderName"
        } else {
            "$currentPath/$folderName"
        }
        loadFiles(newPath)
    }

    private fun navigateToPath(path: String) {
        loadFiles(path)
    }

    private fun enterMultiSelect(path: String) {
        _uiState.update {
            it.copy(
                isMultiSelectMode = true,
                selectedFiles = setOf(path)
            )
        }
    }

    private fun toggleSelection(path: String) {
        _uiState.update {
            val newSelection = if (it.selectedFiles.contains(path)) {
                it.selectedFiles - path
            } else {
                it.selectedFiles + path
            }
            it.copy(selectedFiles = newSelection)
        }
    }

    private fun exitMultiSelect() {
        _uiState.update {
            it.copy(
                isMultiSelectMode = false,
                selectedFiles = emptySet()
            )
        }
    }

    private fun addSelectedToSync() {
        viewModelScope.launch {
            try {
                val account = accountRepository.getActiveAccount() ?: run {
                    _uiState.update { it.copy(errorMessage = "No account found") }
                    return@launch
                }

                val selectedPaths = _uiState.value.selectedFiles.toList()
                val localBasePath = context.filesDir.absolutePath + "/individual_sync"

                val success = controller.addFilesToSync(
                    filePaths = selectedPaths,
                    accountId = account.id,
                    localBasePath = localBasePath,
                    wifiOnly = false
                )

                if (success) {
                    exitMultiSelect()
                    // Show success feedback (could add a snackbar state)
                } else {
                    _uiState.update { it.copy(errorMessage = "Failed to add files to sync") }
                }
            } catch (e: Exception) {
                SafeLogger.e("RemoteFileManagerViewModel", "Failed to add to sync", e)
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    private fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun buildBreadcrumbs(path: String): List<Breadcrumb> {
        if (path == "/") return emptyList()

        val parts = path.split("/").filter { it.isNotEmpty() }
        val breadcrumbs = mutableListOf(Breadcrumb("/", "Home"))

        var currentPath = ""
        parts.forEach { part ->
            currentPath += "/$part"
            breadcrumbs.add(Breadcrumb(currentPath, part))
        }

        return breadcrumbs
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = AppDatabase.getInstance(context)
            val accountRepository = AccountRepository(db.accountDao())
            val individualFileSyncRepository = IndividualFileSyncRepository(db.individualFileSyncDao())

            // Get account for WebDavClient
            val account = runCatching {
                kotlinx.coroutines.runBlocking {
                    accountRepository.getActiveAccount()
                }
            }.getOrNull()

            val webDavClient = if (account != null) {
                val password = EncryptionUtil.decryptPassword(account.passwordEncrypted)
                WebDavClient(context, account.serverUrl, account.username, password)
            } else {
                // Placeholder - will show error in UI
                WebDavClient(context, "", "", "")
            }

            val controller = RemoteFileManagerController(webDavClient, individualFileSyncRepository, context)

            return RemoteFileManagerViewModel(controller, accountRepository, context) as T
        }
    }
}

// UI State
data class RemoteFileManagerUiState(
    val currentPath: String = "/",
    val items: List<RemoteFileItem> = emptyList(),
    val breadcrumbs: List<Breadcrumb> = emptyList(),
    val isMultiSelectMode: Boolean = false,
    val selectedFiles: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class RemoteFileItem(
    val path: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean
)

data class Breadcrumb(
    val path: String,
    val name: String
)

// Events
sealed class RemoteFileManagerEvent {
    data class NavigateToFolder(val folderName: String) : RemoteFileManagerEvent()
    data class NavigateToBreadcrumb(val path: String) : RemoteFileManagerEvent()
    data class LongPress(val path: String) : RemoteFileManagerEvent()
    data class ToggleSelection(val path: String) : RemoteFileManagerEvent()
    object ExitMultiSelect : RemoteFileManagerEvent()
    object AddToSync : RemoteFileManagerEvent()
    object ClearError : RemoteFileManagerEvent()
}
