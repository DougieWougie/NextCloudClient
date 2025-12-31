package com.nextcloud.sync.ui.screens.remotefilemanager

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nextcloud.sync.controllers.fileops.RemoteFileManagerController
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.models.repository.AccountRepository
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
            is RemoteFileManagerEvent.NavigateUp -> navigateUp()
            is RemoteFileManagerEvent.Refresh -> refresh()
            is RemoteFileManagerEvent.ClearError -> clearError()

            // Multi-select
            is RemoteFileManagerEvent.ToggleMultiSelect -> toggleMultiSelect()
            is RemoteFileManagerEvent.ToggleSelection -> toggleSelection(event.path)
            is RemoteFileManagerEvent.ExitMultiSelect -> exitMultiSelect()

            // File operations
            is RemoteFileManagerEvent.RequestDownload -> requestDownload(event.path)
            is RemoteFileManagerEvent.ConfirmDownloadToUri -> confirmDownloadToUri(event.path, event.uri)
            is RemoteFileManagerEvent.ShowDeleteDialog -> showDeleteDialog(event.path)
            is RemoteFileManagerEvent.ConfirmDelete -> confirmDelete(event.path)
            is RemoteFileManagerEvent.ShowRenameDialog -> showRenameDialog(event.path, event.currentName)
            is RemoteFileManagerEvent.ConfirmRename -> confirmRename(event.path, event.newName)
            is RemoteFileManagerEvent.ShowMoveDialog -> showMoveDialog(event.path)
            is RemoteFileManagerEvent.ConfirmMove -> confirmMove(event.sourcePath, event.destPath)
            is RemoteFileManagerEvent.ShowCopyDialog -> showCopyDialog(event.path)
            is RemoteFileManagerEvent.ConfirmCopy -> confirmCopy(event.sourcePath, event.destPath)
            is RemoteFileManagerEvent.DismissDialog -> dismissDialog()
        }
    }

    private fun loadFiles(path: String = "/", forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val loadingKey = if (forceRefresh) "isRefreshing" else "isLoading"
            _uiState.update {
                if (forceRefresh) {
                    it.copy(isRefreshing = true, errorMessage = null)
                } else {
                    it.copy(isLoading = true, errorMessage = null)
                }
            }

            try {
                // Get user email for hidden directory filtering
                val userEmail = accountRepository.getActiveAccount()?.username

                val items = controller.listFilesAndFolders(path, userEmail, forceRefresh)
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
                        isLoading = false,
                        isRefreshing = false
                    )
                }
            } catch (e: Exception) {
                SafeLogger.e("RemoteFileManagerViewModel", "Failed to load files", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = "Failed to load files: ${e.message}"
                    )
                }
            }
        }
    }

    private fun refresh() {
        loadFiles(_uiState.value.currentPath, forceRefresh = true)
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

    private fun navigateUp() {
        val currentPath = _uiState.value.currentPath
        if (currentPath == "/") return

        val parentPath = currentPath.substringBeforeLast('/', "/")
        val finalPath = if (parentPath.isEmpty()) "/" else parentPath
        loadFiles(finalPath)
    }

    private fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun toggleMultiSelect() {
        _uiState.update {
            it.copy(
                isMultiSelectMode = !it.isMultiSelectMode,
                selectedFiles = if (!it.isMultiSelectMode) emptySet() else it.selectedFiles
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

    private fun requestDownload(path: String) {
        // Store the path for download - UI will trigger file picker
        _uiState.update {
            it.copy(
                downloadRequestPath = path
            )
        }
    }

    private fun confirmDownloadToUri(path: String, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }

                val success = controller.downloadFileToUri(path, uri)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        downloadRequestPath = null,
                        errorMessage = if (success) null else "Download failed"
                    )
                }
            } catch (e: Exception) {
                SafeLogger.e("RemoteFileManagerViewModel", "Download failed", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        downloadRequestPath = null,
                        errorMessage = "Download failed: ${e.message}"
                    )
                }
            }
        }
    }

    private fun showDeleteDialog(path: String) {
        val fileName = path.substringAfterLast('/')
        _uiState.update {
            it.copy(
                showDeleteConfirmDialog = true,
                deleteConfirmPath = path,
                deleteConfirmName = fileName
            )
        }
    }

    private fun confirmDelete(path: String) {
        viewModelScope.launch {
            try {
                val success = controller.deleteRemoteFile(path)

                if (success) {
                    dismissDialog()
                    refresh()
                } else {
                    _uiState.update { it.copy(errorMessage = "Failed to delete file") }
                }
            } catch (e: Exception) {
                SafeLogger.e("RemoteFileManagerViewModel", "Delete failed", e)
                _uiState.update { it.copy(errorMessage = "Delete failed: ${e.message}") }
            }
        }
    }

    private fun showRenameDialog(path: String, currentName: String) {
        _uiState.update {
            it.copy(
                showRenameDialog = true,
                renameDialogPath = path,
                renameDialogCurrentName = currentName
            )
        }
    }

    private fun confirmRename(path: String, newName: String) {
        viewModelScope.launch {
            try {
                val success = controller.renameRemoteFile(path, newName)

                if (success) {
                    dismissDialog()
                    refresh()
                } else {
                    _uiState.update { it.copy(errorMessage = "Failed to rename file") }
                }
            } catch (e: Exception) {
                SafeLogger.e("RemoteFileManagerViewModel", "Rename failed", e)
                _uiState.update { it.copy(errorMessage = "Rename failed: ${e.message}") }
            }
        }
    }

    private fun showMoveDialog(path: String) {
        _uiState.update {
            it.copy(
                showMoveDialog = true,
                moveDialogSourcePath = path
            )
        }
    }

    private fun confirmMove(sourcePath: String, destPath: String) {
        viewModelScope.launch {
            try {
                val success = controller.moveRemoteFile(sourcePath, destPath)

                if (success) {
                    dismissDialog()
                    refresh()
                } else {
                    _uiState.update { it.copy(errorMessage = "Failed to move file") }
                }
            } catch (e: Exception) {
                SafeLogger.e("RemoteFileManagerViewModel", "Move failed", e)
                _uiState.update { it.copy(errorMessage = "Move failed: ${e.message}") }
            }
        }
    }

    private fun showCopyDialog(path: String) {
        _uiState.update {
            it.copy(
                showCopyDialog = true,
                copyDialogSourcePath = path
            )
        }
    }

    private fun confirmCopy(sourcePath: String, destPath: String) {
        viewModelScope.launch {
            try {
                val success = controller.copyRemoteFile(sourcePath, destPath)

                if (success) {
                    dismissDialog()
                    // Optionally navigate to destination or show success message
                } else {
                    _uiState.update { it.copy(errorMessage = "Failed to copy file") }
                }
            } catch (e: Exception) {
                SafeLogger.e("RemoteFileManagerViewModel", "Copy failed", e)
                _uiState.update { it.copy(errorMessage = "Copy failed: ${e.message}") }
            }
        }
    }

    private fun dismissDialog() {
        _uiState.update {
            it.copy(
                showRenameDialog = false,
                renameDialogPath = null,
                renameDialogCurrentName = null,
                showDeleteConfirmDialog = false,
                deleteConfirmPath = null,
                deleteConfirmName = null,
                showMoveDialog = false,
                moveDialogSourcePath = null,
                showCopyDialog = false,
                copyDialogSourcePath = null,
                downloadRequestPath = null
            )
        }
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
            val folderRepository = com.nextcloud.sync.models.repository.FolderRepository(db.folderDao())
            val fileRepository = com.nextcloud.sync.models.repository.FileRepository(db.fileDao())

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

            val controller = RemoteFileManagerController(
                webDavClient,
                folderRepository,
                fileRepository,
                context
            )

            return RemoteFileManagerViewModel(controller, accountRepository, context) as T
        }
    }
}

// UI State
data class RemoteFileManagerUiState(
    val currentPath: String = "/",
    val items: List<RemoteFileItem> = emptyList(),
    val breadcrumbs: List<Breadcrumb> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,

    // Multi-select state
    val isMultiSelectMode: Boolean = false,
    val selectedFiles: Set<String> = emptySet(),

    // Dialog state
    val showRenameDialog: Boolean = false,
    val renameDialogPath: String? = null,
    val renameDialogCurrentName: String? = null,
    val showDeleteConfirmDialog: Boolean = false,
    val deleteConfirmPath: String? = null,
    val deleteConfirmName: String? = null,
    val showMoveDialog: Boolean = false,
    val moveDialogSourcePath: String? = null,
    val showCopyDialog: Boolean = false,
    val copyDialogSourcePath: String? = null,

    // Download state - when non-null, UI should trigger file picker
    val downloadRequestPath: String? = null
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
    object NavigateUp : RemoteFileManagerEvent()
    object Refresh : RemoteFileManagerEvent()
    object ClearError : RemoteFileManagerEvent()

    // Multi-select events
    object ToggleMultiSelect : RemoteFileManagerEvent()
    data class ToggleSelection(val path: String) : RemoteFileManagerEvent()
    object ExitMultiSelect : RemoteFileManagerEvent()

    // File operation events
    data class RequestDownload(val path: String) : RemoteFileManagerEvent()
    data class ConfirmDownloadToUri(val path: String, val uri: android.net.Uri) : RemoteFileManagerEvent()
    data class ShowDeleteDialog(val path: String) : RemoteFileManagerEvent()
    data class ConfirmDelete(val path: String) : RemoteFileManagerEvent()
    data class ShowRenameDialog(val path: String, val currentName: String) : RemoteFileManagerEvent()
    data class ConfirmRename(val path: String, val newName: String) : RemoteFileManagerEvent()
    data class ShowMoveDialog(val path: String) : RemoteFileManagerEvent()
    data class ConfirmMove(val sourcePath: String, val destPath: String) : RemoteFileManagerEvent()
    data class ShowCopyDialog(val path: String) : RemoteFileManagerEvent()
    data class ConfirmCopy(val sourcePath: String, val destPath: String) : RemoteFileManagerEvent()
    object DismissDialog : RemoteFileManagerEvent()
}
