package com.nextcloud.sync.ui.screens.addfolder

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.sync.models.database.entities.FolderEntity
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.models.repository.FolderRepository
import com.nextcloud.sync.utils.DefaultFolderPreference
import com.nextcloud.sync.utils.UriPathHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class AddFolderUiState(
    val serverUrl: String = "",
    val selectedLocalPath: String? = null,
    val localFolderName: String = "Not selected",
    val localFolderStorageLocation: String = "",
    val selectedRemotePath: String = "/",
    val twoWaySync: Boolean = true,
    val wifiOnly: Boolean = false,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val shouldNavigateBack: Boolean = false
)

sealed class AddFolderEvent {
    data class LocalFolderSelected(val uri: Uri, val context: Context) : AddFolderEvent()
    data class RemotePathSelected(val path: String) : AddFolderEvent()
    object SelectRemoteFolderClicked : AddFolderEvent()
    data class TwoWaySyncChanged(val enabled: Boolean) : AddFolderEvent()
    data class WifiOnlyChanged(val enabled: Boolean) : AddFolderEvent()
    object AddFolderClicked : AddFolderEvent()
    object MessageDismissed : AddFolderEvent()
}

class AddFolderViewModel(
    private val folderRepository: FolderRepository,
    private val accountRepository: AccountRepository,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddFolderUiState())
    val uiState: StateFlow<AddFolderUiState> = _uiState.asStateFlow()

    private var accountId: Long = 0

    init {
        loadAccount()
    }

    fun onEvent(event: AddFolderEvent) {
        when (event) {
            is AddFolderEvent.LocalFolderSelected -> handleLocalFolderSelection(event.uri, event.context)
            is AddFolderEvent.RemotePathSelected -> {
                _uiState.update { it.copy(selectedRemotePath = event.path) }
                // Auto-select local folder if none selected yet
                if (_uiState.value.selectedLocalPath == null) {
                    autoSelectLocalFolder(event.path)
                }
            }
            is AddFolderEvent.SelectRemoteFolderClicked -> {
                // Navigation handled by screen
            }
            is AddFolderEvent.TwoWaySyncChanged -> {
                _uiState.update { it.copy(twoWaySync = event.enabled) }
            }
            is AddFolderEvent.WifiOnlyChanged -> {
                _uiState.update { it.copy(wifiOnly = event.enabled) }
            }
            is AddFolderEvent.AddFolderClicked -> addFolder()
            is AddFolderEvent.MessageDismissed -> {
                _uiState.update { it.copy(successMessage = null, errorMessage = null) }
            }
        }
    }

    private fun loadAccount() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                val account = accountRepository.getActiveAccount()
                if (account == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "No active account found",
                            shouldNavigateBack = true
                        )
                    }
                    return@launch
                }

                accountId = account.id
                _uiState.update {
                    it.copy(
                        serverUrl = account.serverUrl,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load account: ${e.message}"
                    )
                }
            }
        }
    }

    private fun handleLocalFolderSelection(uri: Uri, context: Context) {
        try {
            // Take persistable URI permission
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            val uriString = uri.toString()
            val folderName = UriPathHelper.getDisplayName(context, uriString)
            val storageLocation = UriPathHelper.getStorageLocation(uriString)

            _uiState.update {
                it.copy(
                    selectedLocalPath = uriString,
                    localFolderName = folderName,
                    localFolderStorageLocation = storageLocation
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(errorMessage = "Failed to select folder: ${e.message}")
            }
        }
    }

    private fun autoSelectLocalFolder(remotePath: String) {
        try {
            // Extract subfolder name from remote path (e.g., "/Photos" -> "Photos")
            val pathParts = remotePath.trim('/').split('/')
            val subfolder = if (pathParts.size == 1 && pathParts[0].isEmpty()) {
                null // Root path "/"
            } else {
                pathParts.lastOrNull()
            }

            // Create path using default folder preference
            val fullPath = if (subfolder.isNullOrBlank()) {
                DefaultFolderPreference.getDefaultFolderPath(context)
            } else {
                DefaultFolderPreference.getDefaultFolderPath(context, subfolder)
            }

            // Create the directory if it doesn't exist
            val folder = File(fullPath)
            if (!folder.exists()) {
                val created = folder.mkdirs()
                if (!created && !folder.exists()) {
                    _uiState.update {
                        it.copy(errorMessage = "Failed to create local folder")
                    }
                    return
                }
            }

            // Use file path directly (app's external files directory doesn't need content URI)
            // This will work with the sync logic which handles both content:// and file paths
            val folderName = folder.name
            val storageLocation = "App Storage"

            _uiState.update {
                it.copy(
                    selectedLocalPath = fullPath,
                    localFolderName = folderName,
                    localFolderStorageLocation = storageLocation
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(errorMessage = "Failed to auto-select local folder: ${e.message}")
            }
        }
    }

    private fun addFolder() {
        val localPath = _uiState.value.selectedLocalPath
        if (localPath == null) {
            _uiState.update { it.copy(errorMessage = "Please select a local folder") }
            return
        }

        if (_uiState.value.selectedRemotePath.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please select a remote folder") }
            return
        }

        viewModelScope.launch {
            try {
                val remotePath = _uiState.value.selectedRemotePath

                // Check if a folder with this remote path already exists
                val existingByRemote = folderRepository.getFolderByRemotePath(accountId, remotePath)
                if (existingByRemote != null) {
                    _uiState.update {
                        it.copy(errorMessage = "This remote folder is already being synced")
                    }
                    return@launch
                }

                // Check if a folder with this local path already exists
                val existingByLocal = folderRepository.getFolderByLocalPath(accountId, localPath)
                if (existingByLocal != null) {
                    _uiState.update {
                        it.copy(errorMessage = "This local folder is already being synced")
                    }
                    return@launch
                }

                val folder = FolderEntity(
                    accountId = accountId,
                    localPath = localPath,
                    remotePath = remotePath,
                    syncEnabled = true,
                    twoWaySync = _uiState.value.twoWaySync,
                    wifiOnly = _uiState.value.wifiOnly
                )

                folderRepository.insert(folder)

                _uiState.update {
                    it.copy(
                        successMessage = "Folder added successfully",
                        shouldNavigateBack = true
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "Failed to add folder: ${e.message}")
                }
            }
        }
    }
}
