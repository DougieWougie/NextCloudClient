package com.nextcloud.sync.ui.screens.localfilemanager

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nextcloud.sync.controllers.fileops.LocalFileManagerController
import com.nextcloud.sync.models.data.SyncStatus
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.database.entities.FolderEntity
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.models.repository.FileRepository
import com.nextcloud.sync.models.repository.FolderRepository
import com.nextcloud.sync.utils.SafeLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LocalFileManagerViewModel(
    private val controller: LocalFileManagerController,
    private val folderRepository: FolderRepository,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalFileManagerUiState())
    val uiState: StateFlow<LocalFileManagerUiState> = _uiState.asStateFlow()

    init {
        loadFolders()
    }

    fun onEvent(event: LocalFileManagerEvent) {
        when (event) {
            is LocalFileManagerEvent.SelectFolder -> selectFolder(event.folder)
            is LocalFileManagerEvent.NavigateBack -> navigateBack()
            is LocalFileManagerEvent.ToggleMultiSelectMode -> toggleMultiSelectMode()
            is LocalFileManagerEvent.ToggleSelection -> toggleSelection(event.filePath)
            is LocalFileManagerEvent.ExitMultiSelect -> exitMultiSelect()
            is LocalFileManagerEvent.FileClicked -> handleFileClick(event.file)
            is LocalFileManagerEvent.DownloadFile -> downloadFile(event.filePath)
            is LocalFileManagerEvent.OpenFile -> openFile(event.filePath)
            is LocalFileManagerEvent.DeleteFile -> deleteFile(event.filePath)
            is LocalFileManagerEvent.RenameFile -> renameFile(event.filePath, event.newName)
            is LocalFileManagerEvent.CopyFile -> copyFile(event.filePath, event.destinationPath)
            is LocalFileManagerEvent.MoveFile -> moveFile(event.filePath, event.destinationPath)
            is LocalFileManagerEvent.ClearError -> clearError()
        }
    }

    private fun loadFolders() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                val folders = folderRepository.getSyncEnabledFolders()
                _uiState.update {
                    it.copy(
                        folders = folders,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                SafeLogger.e("LocalFileManagerViewModel", "Failed to load folders", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load folders: ${e.message}"
                    )
                }
            }
        }
    }

    private fun selectFolder(folder: FolderEntity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                // Get user email for hidden directory filtering
                val userEmail = folderRepository.getFolderById(folder.id)?.let { f ->
                    // We can't get email from folder, so we'll need to get it from account
                    // For now, pass null - could be enhanced to fetch from account
                    null
                }

                val controllerFiles = controller.listFilesInFolder(folder.id, userEmail)
                // Map controller's LocalFileItem to UI LocalFileItem
                val uiFiles = controllerFiles.map { file ->
                    LocalFileItem(
                        name = file.name,
                        path = file.path,
                        size = file.size,
                        lastModified = file.lastModified,
                        isDirectory = file.isDirectory,
                        syncStatus = file.syncStatus.name,
                        mimeType = file.mimeType
                    )
                }
                _uiState.update {
                    it.copy(
                        selectedFolder = folder,
                        files = uiFiles,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                SafeLogger.e("LocalFileManagerViewModel", "Failed to load files", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load files: ${e.message}"
                    )
                }
            }
        }
    }

    private fun navigateBack() {
        _uiState.update {
            it.copy(
                selectedFolder = null,
                files = emptyList(),
                isMultiSelectMode = false,
                selectedFiles = emptySet()
            )
        }
    }

    private fun toggleMultiSelectMode() {
        _uiState.update {
            it.copy(
                isMultiSelectMode = !it.isMultiSelectMode,
                selectedFiles = if (!it.isMultiSelectMode) emptySet() else it.selectedFiles
            )
        }
    }

    private fun toggleSelection(filePath: String) {
        _uiState.update {
            val newSelection = if (it.selectedFiles.contains(filePath)) {
                it.selectedFiles - filePath
            } else {
                it.selectedFiles + filePath
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

    private fun handleFileClick(file: LocalFileItem) {
        // If file is synced (downloaded), open it; otherwise download it
        if (file.syncStatus == SyncStatus.SYNCED.name) {
            openFile(file.path)
        } else {
            downloadFile(file.path)
        }
    }

    private fun downloadFile(filePath: String) {
        viewModelScope.launch {
            try {
                val folderId = _uiState.value.selectedFolder?.id ?: return@launch
                val success = controller.downloadFile(filePath, folderId)
                if (success) {
                    // Refresh file list to show updated status
                    _uiState.value.selectedFolder?.let { selectFolder(it) }
                } else {
                    _uiState.update { it.copy(errorMessage = "Failed to mark file for download") }
                }
            } catch (e: Exception) {
                SafeLogger.e("LocalFileManagerViewModel", "Failed to download file", e)
                _uiState.update { it.copy(errorMessage = "Failed to download: ${e.message}") }
            }
        }
    }

    private fun openFile(filePath: String) {
        try {
            // Find the file to get its MIME type
            val file = _uiState.value.files.find { it.path == filePath }
            if (file == null) {
                _uiState.update { it.copy(errorMessage = "File not found") }
                return
            }

            val intent = controller.createOpenFileIntent(filePath, file.mimeType)
            if (intent != null) {
                context.startActivity(intent)
            } else {
                _uiState.update { it.copy(errorMessage = "No app found to open this file") }
            }
        } catch (e: Exception) {
            SafeLogger.e("LocalFileManagerViewModel", "Failed to open file", e)
            _uiState.update { it.copy(errorMessage = "Failed to open: ${e.message}") }
        }
    }

    private fun deleteFile(filePath: String) {
        viewModelScope.launch {
            try {
                val success = controller.deleteFile(filePath)
                if (success) {
                    // Refresh file list
                    _uiState.value.selectedFolder?.let { selectFolder(it) }
                } else {
                    _uiState.update { it.copy(errorMessage = "Failed to delete file") }
                }
            } catch (e: Exception) {
                SafeLogger.e("LocalFileManagerViewModel", "Failed to delete file", e)
                _uiState.update { it.copy(errorMessage = "Failed to delete: ${e.message}") }
            }
        }
    }

    private fun renameFile(filePath: String, newName: String) {
        viewModelScope.launch {
            try {
                val success = controller.renameFile(filePath, newName)
                if (success) {
                    // Refresh file list
                    _uiState.value.selectedFolder?.let { selectFolder(it) }
                } else {
                    _uiState.update { it.copy(errorMessage = "Failed to rename file") }
                }
            } catch (e: Exception) {
                SafeLogger.e("LocalFileManagerViewModel", "Failed to rename file", e)
                _uiState.update { it.copy(errorMessage = "Failed to rename: ${e.message}") }
            }
        }
    }

    private fun copyFile(filePath: String, destinationPath: String) {
        viewModelScope.launch {
            try {
                val success = controller.copyFile(filePath, destinationPath)
                if (success) {
                    // Optionally refresh file list
                    _uiState.value.selectedFolder?.let { selectFolder(it) }
                } else {
                    _uiState.update { it.copy(errorMessage = "Failed to copy file") }
                }
            } catch (e: Exception) {
                SafeLogger.e("LocalFileManagerViewModel", "Failed to copy file", e)
                _uiState.update { it.copy(errorMessage = "Failed to copy: ${e.message}") }
            }
        }
    }

    private fun moveFile(filePath: String, destinationPath: String) {
        viewModelScope.launch {
            try {
                val success = controller.moveFile(filePath, destinationPath)
                if (success) {
                    // Refresh file list
                    _uiState.value.selectedFolder?.let { selectFolder(it) }
                } else {
                    _uiState.update { it.copy(errorMessage = "Failed to move file") }
                }
            } catch (e: Exception) {
                SafeLogger.e("LocalFileManagerViewModel", "Failed to move file", e)
                _uiState.update { it.copy(errorMessage = "Failed to move: ${e.message}") }
            }
        }
    }

    private fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = AppDatabase.getInstance(context)
            val accountRepository = AccountRepository(db.accountDao())
            val folderRepository = FolderRepository(db.folderDao())
            val fileRepository = FileRepository(db.fileDao())

            val controller = LocalFileManagerController(
                fileRepository = fileRepository,
                folderRepository = folderRepository,
                context = context
            )

            return LocalFileManagerViewModel(controller, folderRepository, context) as T
        }
    }
}

// UI State
data class LocalFileManagerUiState(
    val folders: List<FolderEntity> = emptyList(),
    val selectedFolder: FolderEntity? = null,
    val files: List<LocalFileItem> = emptyList(),
    val isMultiSelectMode: Boolean = false,
    val selectedFiles: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

data class LocalFileItem(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val syncStatus: String,
    val mimeType: String = "application/octet-stream"
)

// Events
sealed class LocalFileManagerEvent {
    data class SelectFolder(val folder: FolderEntity) : LocalFileManagerEvent()
    object NavigateBack : LocalFileManagerEvent()
    object ToggleMultiSelectMode : LocalFileManagerEvent()
    data class ToggleSelection(val filePath: String) : LocalFileManagerEvent()
    object ExitMultiSelect : LocalFileManagerEvent()
    data class FileClicked(val file: LocalFileItem) : LocalFileManagerEvent()
    data class DownloadFile(val filePath: String) : LocalFileManagerEvent()
    data class OpenFile(val filePath: String) : LocalFileManagerEvent()
    data class DeleteFile(val filePath: String) : LocalFileManagerEvent()
    data class RenameFile(val filePath: String, val newName: String) : LocalFileManagerEvent()
    data class CopyFile(val filePath: String, val destinationPath: String) : LocalFileManagerEvent()
    data class MoveFile(val filePath: String, val destinationPath: String) : LocalFileManagerEvent()
    object ClearError : LocalFileManagerEvent()
}
