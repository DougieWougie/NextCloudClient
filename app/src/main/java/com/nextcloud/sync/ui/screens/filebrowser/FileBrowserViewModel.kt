package com.nextcloud.sync.ui.screens.filebrowser

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.utils.EncryptionUtil
import com.nextcloud.sync.utils.HiddenFilesPreference
import com.nextcloud.sync.utils.PathValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FolderItem(
    val name: String,
    val path: String
)

data class FileBrowserUiState(
    val currentPath: String = "/",
    val breadcrumbs: List<BreadcrumbItem> = listOf(BreadcrumbItem("Home", "/")),
    val folders: List<FolderItem> = emptyList(),
    val filteredFolders: List<FolderItem> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val isEmpty: Boolean = false,
    val emptyStateMessage: String = "No folders found",
    val errorMessage: String? = null,
    val showCreateFolderDialog: Boolean = false,
    val newFolderName: String = "",
    val newFolderError: String? = null,
    val shouldNavigateBack: Boolean = false,
    val selectedPath: String? = null
)

data class BreadcrumbItem(
    val label: String,
    val path: String
)

sealed class FileBrowserEvent {
    data class FolderClicked(val folderName: String) : FileBrowserEvent()
    data class BreadcrumbClicked(val path: String) : FileBrowserEvent()
    data class SearchQueryChanged(val query: String) : FileBrowserEvent()
    object SelectCurrentFolderClicked : FileBrowserEvent()
    object CreateFolderClicked : FileBrowserEvent()
    data class NewFolderNameChanged(val name: String) : FileBrowserEvent()
    object ConfirmCreateFolder : FileBrowserEvent()
    object DismissCreateFolderDialog : FileBrowserEvent()
    object NavigateUp : FileBrowserEvent()
    object ErrorDismissed : FileBrowserEvent()
}

class FileBrowserViewModel(
    private val initialPath: String,
    private val accountRepository: AccountRepository,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState(currentPath = initialPath))
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    private lateinit var webDavClient: WebDavClient
    private val folderStack = mutableListOf<String>()
    private var userEmail: String? = null

    init {
        loadAccount()
    }

    fun onEvent(event: FileBrowserEvent) {
        when (event) {
            is FileBrowserEvent.FolderClicked -> navigateToFolder(event.folderName)
            is FileBrowserEvent.BreadcrumbClicked -> navigateToPath(event.path)
            is FileBrowserEvent.SearchQueryChanged -> {
                _uiState.update { it.copy(searchQuery = event.query) }
                filterFolders()
            }
            is FileBrowserEvent.SelectCurrentFolderClicked -> selectCurrentFolder()
            is FileBrowserEvent.CreateFolderClicked -> {
                _uiState.update { it.copy(showCreateFolderDialog = true, newFolderName = "", newFolderError = null) }
            }
            is FileBrowserEvent.NewFolderNameChanged -> {
                _uiState.update { it.copy(newFolderName = event.name, newFolderError = null) }
            }
            is FileBrowserEvent.ConfirmCreateFolder -> createFolder()
            is FileBrowserEvent.DismissCreateFolderDialog -> {
                _uiState.update { it.copy(showCreateFolderDialog = false, newFolderName = "", newFolderError = null) }
            }
            is FileBrowserEvent.NavigateUp -> navigateUp()
            is FileBrowserEvent.ErrorDismissed -> {
                _uiState.update { it.copy(errorMessage = null) }
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

                // Store user email for hidden directory filtering
                userEmail = account.username

                val password = EncryptionUtil.decryptPassword(account.passwordEncrypted)
                val authToken = account.authTokenEncrypted?.let { EncryptionUtil.decryptPassword(it) } ?: password
                webDavClient = WebDavClient(context, account.serverUrl, account.username, authToken)

                // Initialize breadcrumbs based on initial path
                updateBreadcrumbs(_uiState.value.currentPath)

                loadFolders()
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

    private fun loadFolders() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                val webDavFolders = webDavClient.listFolders(_uiState.value.currentPath)
                val showHidden = HiddenFilesPreference.getShowHidden(context)

                // Filter hidden folders based on preference
                val folders = webDavFolders.mapNotNull { folder ->
                    // Filter hidden folders if needed
                    if (HiddenFilesPreference.shouldFilter(folder.name, showHidden, userEmail)) {
                        null
                    } else {
                        FolderItem(folder.name, folder.path)
                    }
                }

                _uiState.update {
                    it.copy(
                        folders = folders,
                        isLoading = false
                    )
                }

                filterFolders()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load folders: ${e.message}"
                    )
                }
            }
        }
    }

    private fun navigateToFolder(folderName: String) {
        // Sanitize folder name to prevent directory traversal
        val sanitizedName = PathValidator.sanitizeFileName(folderName)
        if (sanitizedName == null) {
            _uiState.update { it.copy(errorMessage = "Invalid folder name") }
            return
        }

        folderStack.add(_uiState.value.currentPath)
        val newPath = if (_uiState.value.currentPath.endsWith("/")) {
            "${_uiState.value.currentPath}$sanitizedName"
        } else {
            "${_uiState.value.currentPath}/$sanitizedName"
        }

        _uiState.update {
            it.copy(
                currentPath = newPath,
                searchQuery = "" // Clear search when navigating
            )
        }

        updateBreadcrumbs(newPath)
        loadFolders()
    }

    private fun navigateUp() {
        if (folderStack.isNotEmpty()) {
            val previousPath = folderStack.removeAt(folderStack.size - 1)
            _uiState.update {
                it.copy(
                    currentPath = previousPath,
                    searchQuery = "" // Clear search when navigating
                )
            }

            updateBreadcrumbs(previousPath)
            loadFolders()
        }
    }

    private fun navigateToPath(targetPath: String) {
        // Calculate how many levels to go back
        val currentSegments = _uiState.value.currentPath.split("/").filter { it.isNotEmpty() }
        val targetSegments = targetPath.split("/").filter { it.isNotEmpty() }

        val levelsBack = currentSegments.size - targetSegments.size

        // Update folder stack
        repeat(levelsBack) {
            if (folderStack.isNotEmpty()) {
                folderStack.removeAt(folderStack.size - 1)
            }
        }

        _uiState.update {
            it.copy(
                currentPath = targetPath,
                searchQuery = "" // Clear search when navigating
            )
        }

        updateBreadcrumbs(targetPath)
        loadFolders()
    }

    private fun updateBreadcrumbs(path: String) {
        val segments = path.split("/").filter { it.isNotEmpty() }
        val breadcrumbs = mutableListOf(BreadcrumbItem("Home", "/"))

        var pathBuilder = ""
        segments.forEach { segment ->
            pathBuilder += "/$segment"
            breadcrumbs.add(BreadcrumbItem(segment, pathBuilder))
        }

        _uiState.update { it.copy(breadcrumbs = breadcrumbs) }
    }

    private fun filterFolders() {
        val filtered = if (_uiState.value.searchQuery.isEmpty()) {
            _uiState.value.folders
        } else {
            _uiState.value.folders.filter { folder ->
                folder.name.contains(_uiState.value.searchQuery, ignoreCase = true)
            }
        }

        val emptyMessage = if (_uiState.value.searchQuery.isNotEmpty()) {
            "No folders match \"${_uiState.value.searchQuery}\""
        } else {
            "No folders found"
        }

        _uiState.update {
            it.copy(
                filteredFolders = filtered,
                isEmpty = filtered.isEmpty(),
                emptyStateMessage = emptyMessage
            )
        }
    }

    private fun selectCurrentFolder() {
        _uiState.update {
            it.copy(
                selectedPath = it.currentPath,
                shouldNavigateBack = true
            )
        }
    }

    private fun createFolder() {
        val folderName = _uiState.value.newFolderName.trim()
        if (folderName.isEmpty()) {
            _uiState.update { it.copy(newFolderError = "Folder name cannot be empty") }
            return
        }

        val newFolderPath = if (_uiState.value.currentPath.endsWith("/")) {
            "${_uiState.value.currentPath}$folderName"
        } else {
            "${_uiState.value.currentPath}/$folderName"
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, showCreateFolderDialog = false) }

            try {
                val success = webDavClient.createDirectory(newFolderPath)

                if (success) {
                    _uiState.update { it.copy(isLoading = false, newFolderName = "") }
                    loadFolders() // Reload to show the new folder
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to create folder"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Error: ${e.message}"
                    )
                }
            }
        }
    }

    fun canNavigateUp(): Boolean = folderStack.isNotEmpty()
}
