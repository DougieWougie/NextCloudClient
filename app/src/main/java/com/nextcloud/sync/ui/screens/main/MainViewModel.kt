package com.nextcloud.sync.ui.screens.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.sync.R
import com.nextcloud.sync.models.database.entities.FolderEntity
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.models.repository.FolderRepository
import com.nextcloud.sync.services.workers.SyncWorker
import com.nextcloud.sync.utils.UriPathHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MainUiState(
    val folders: List<FolderEntity> = emptyList(),
    val lastSyncTime: String = "Never synced",
    val isSyncing: Boolean = false,
    val isEmpty: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val folderToDelete: FolderEntity? = null,
    val successMessage: String? = null,
    val isLoading: Boolean = true
)

sealed class MainEvent {
    data class FolderClicked(val folder: FolderEntity) : MainEvent()
    data class SyncFolderClicked(val folder: FolderEntity) : MainEvent()
    data class EditFolderClicked(val folder: FolderEntity) : MainEvent()
    data class DeleteFolderClicked(val folder: FolderEntity) : MainEvent()
    object ConfirmDeleteFolder : MainEvent()
    object DismissDeleteDialog : MainEvent()
    object SyncAllClicked : MainEvent()
    object AddFolderClicked : MainEvent()
    object SettingsClicked : MainEvent()
    object MessageDismissed : MainEvent()
    object Refresh : MainEvent()
}

class MainViewModel(
    private val folderRepository: FolderRepository,
    private val accountRepository: AccountRepository,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        loadFolders()
        loadLastSyncTime()
    }

    fun onEvent(event: MainEvent) {
        when (event) {
            is MainEvent.FolderClicked -> {
                // Future: Navigate to folder details
            }
            is MainEvent.SyncFolderClicked -> syncSingleFolder(event.folder)
            is MainEvent.EditFolderClicked -> {
                // Navigation handled by screen
            }
            is MainEvent.DeleteFolderClicked -> {
                _uiState.update {
                    it.copy(
                        showDeleteDialog = true,
                        folderToDelete = event.folder
                    )
                }
            }
            is MainEvent.ConfirmDeleteFolder -> deleteFolder()
            is MainEvent.DismissDeleteDialog -> {
                _uiState.update {
                    it.copy(
                        showDeleteDialog = false,
                        folderToDelete = null
                    )
                }
            }
            is MainEvent.SyncAllClicked -> startSyncAll()
            is MainEvent.AddFolderClicked -> {
                // Navigation handled by screen
            }
            is MainEvent.SettingsClicked -> {
                // Navigation handled by screen
            }
            is MainEvent.MessageDismissed -> {
                _uiState.update { it.copy(successMessage = null) }
            }
            is MainEvent.Refresh -> {
                loadFolders()
                loadLastSyncTime()
            }
        }
    }

    private fun loadFolders() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                val account = accountRepository.getActiveAccount()
                if (account != null) {
                    val folders = folderRepository.getFoldersByAccount(account.id)
                    _uiState.update {
                        it.copy(
                            folders = folders,
                            isEmpty = folders.isEmpty(),
                            isLoading = false
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            folders = emptyList(),
                            isEmpty = true,
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        folders = emptyList(),
                        isEmpty = true,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun loadLastSyncTime() {
        viewModelScope.launch {
            val account = accountRepository.getActiveAccount()
            val timeString = if (account?.lastSync != null) {
                val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", Locale.getDefault())
                "Last synced: ${dateFormat.format(Date(account.lastSync))}"
            } else {
                context.getString(R.string.never_synced)
            }

            _uiState.update { it.copy(lastSyncTime = timeString) }
        }
    }

    private fun startSyncAll() {
        viewModelScope.launch {
            if (_uiState.value.folders.isEmpty()) {
                _uiState.update {
                    it.copy(successMessage = "No folders configured. Add a folder to sync.")
                }
                return@launch
            }

            _uiState.update { it.copy(isSyncing = true) }
            SyncWorker.scheduleImmediate(context)

            _uiState.update {
                it.copy(
                    successMessage = "Sync started for ${_uiState.value.folders.size} folder(s)"
                )
            }

            // Hide progress after delay (simulating sync completion)
            delay(5000)
            _uiState.update { it.copy(isSyncing = false) }
            loadLastSyncTime()
            _uiState.update {
                it.copy(successMessage = "Sync completed")
            }
        }
    }

    private fun syncSingleFolder(folder: FolderEntity) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            SyncWorker.scheduleImmediate(context)

            val localFolderName = UriPathHelper.getDisplayName(context, folder.localPath)
            _uiState.update {
                it.copy(successMessage = "Syncing $localFolderName...")
            }

            // Hide progress after delay (simulating sync completion)
            delay(5000)
            _uiState.update { it.copy(isSyncing = false) }
            loadLastSyncTime()
            _uiState.update {
                it.copy(successMessage = "Sync completed for $localFolderName")
            }
        }
    }

    private fun deleteFolder() {
        viewModelScope.launch {
            _uiState.value.folderToDelete?.let { folder ->
                folderRepository.delete(folder)
                _uiState.update {
                    it.copy(
                        showDeleteDialog = false,
                        folderToDelete = null,
                        successMessage = "Folder removed from sync"
                    )
                }
                loadFolders()
            }
        }
    }
}
