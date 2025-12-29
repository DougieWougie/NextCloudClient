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
            is LocalFileManagerEvent.LongPress -> enterMultiSelect(event.filePath)
            is LocalFileManagerEvent.ToggleSelection -> toggleSelection(event.filePath)
            is LocalFileManagerEvent.ExitMultiSelect -> exitMultiSelect()
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
                        syncStatus = file.syncStatus.name
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

    private fun enterMultiSelect(filePath: String) {
        _uiState.update {
            it.copy(
                isMultiSelectMode = true,
                selectedFiles = setOf(filePath)
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
    val syncStatus: String
)

// Events
sealed class LocalFileManagerEvent {
    data class SelectFolder(val folder: FolderEntity) : LocalFileManagerEvent()
    object NavigateBack : LocalFileManagerEvent()
    data class LongPress(val filePath: String) : LocalFileManagerEvent()
    data class ToggleSelection(val filePath: String) : LocalFileManagerEvent()
    object ExitMultiSelect : LocalFileManagerEvent()
    object ClearError : LocalFileManagerEvent()
}
