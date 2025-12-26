package com.nextcloud.sync.ui.screens.editfolder

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.sync.models.repository.FolderRepository
import com.nextcloud.sync.utils.UriPathHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditFolderUiState(
    val localFolderName: String = "",
    val localFolderPath: String = "",
    val remotePath: String = "/",
    val twoWaySync: Boolean = true,
    val wifiOnly: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val shouldNavigateBack: Boolean = false
)

sealed class EditFolderEvent {
    data class RemotePathSelected(val path: String) : EditFolderEvent()
    object SelectRemoteFolderClicked : EditFolderEvent()
    data class TwoWaySyncChanged(val enabled: Boolean) : EditFolderEvent()
    data class WifiOnlyChanged(val enabled: Boolean) : EditFolderEvent()
    object SaveClicked : EditFolderEvent()
    object MessageDismissed : EditFolderEvent()
}

class EditFolderViewModel(
    private val folderId: Long,
    private val folderRepository: FolderRepository,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditFolderUiState())
    val uiState: StateFlow<EditFolderUiState> = _uiState.asStateFlow()

    init {
        loadFolder()
    }

    fun onEvent(event: EditFolderEvent) {
        when (event) {
            is EditFolderEvent.RemotePathSelected -> {
                _uiState.update { it.copy(remotePath = event.path) }
            }
            is EditFolderEvent.SelectRemoteFolderClicked -> {
                // Navigation handled by screen
            }
            is EditFolderEvent.TwoWaySyncChanged -> {
                _uiState.update { it.copy(twoWaySync = event.enabled) }
            }
            is EditFolderEvent.WifiOnlyChanged -> {
                _uiState.update { it.copy(wifiOnly = event.enabled) }
            }
            is EditFolderEvent.SaveClicked -> saveChanges()
            is EditFolderEvent.MessageDismissed -> {
                _uiState.update { it.copy(successMessage = null, errorMessage = null) }
            }
        }
    }

    private fun loadFolder() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                val folder = folderRepository.getFolderById(folderId)
                if (folder == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Folder not found",
                            shouldNavigateBack = true
                        )
                    }
                    return@launch
                }

                // Get local folder display name and storage location
                val localFolderName = UriPathHelper.getDisplayName(context, folder.localPath)
                val storageLocation = UriPathHelper.getStorageLocation(folder.localPath)

                _uiState.update {
                    it.copy(
                        localFolderName = localFolderName,
                        localFolderPath = storageLocation,
                        remotePath = folder.remotePath,
                        twoWaySync = folder.twoWaySync,
                        wifiOnly = folder.wifiOnly,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load folder: ${e.message}"
                    )
                }
            }
        }
    }

    private fun saveChanges() {
        viewModelScope.launch {
            try {
                val folder = folderRepository.getFolderById(folderId) ?: return@launch

                val updatedFolder = folder.copy(
                    remotePath = _uiState.value.remotePath,
                    twoWaySync = _uiState.value.twoWaySync,
                    wifiOnly = _uiState.value.wifiOnly
                )

                folderRepository.update(updatedFolder)

                _uiState.update {
                    it.copy(
                        successMessage = "Sync settings updated",
                        shouldNavigateBack = true
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to save changes: ${e.message}")
                }
            }
        }
    }
}
