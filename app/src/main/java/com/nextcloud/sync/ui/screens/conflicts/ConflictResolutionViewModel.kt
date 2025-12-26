package com.nextcloud.sync.ui.screens.conflicts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.sync.controllers.sync.ConflictResolutionController
import com.nextcloud.sync.models.data.ConflictResolution
import com.nextcloud.sync.models.database.entities.ConflictEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConflictResolutionUiState(
    val conflicts: List<ConflictEntity> = emptyList(),
    val isLoading: Boolean = true,
    val isResolving: Boolean = false,
    val successMessage: String? = null,
    val errorMessage: String? = null
)

sealed class ConflictResolutionEvent {
    data class ResolveConflict(val conflict: ConflictEntity, val resolution: ConflictResolution) :
        ConflictResolutionEvent()
    object MessageDismissed : ConflictResolutionEvent()
}

class ConflictResolutionViewModel(
    private val conflictController: ConflictResolutionController
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConflictResolutionUiState())
    val uiState: StateFlow<ConflictResolutionUiState> = _uiState.asStateFlow()

    init {
        loadConflicts()
    }

    fun onEvent(event: ConflictResolutionEvent) {
        when (event) {
            is ConflictResolutionEvent.ResolveConflict -> {
                resolveConflict(event.conflict, event.resolution)
            }
            is ConflictResolutionEvent.MessageDismissed -> {
                _uiState.update {
                    it.copy(successMessage = null, errorMessage = null)
                }
            }
        }
    }

    private fun loadConflicts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                val pendingConflicts = conflictController.getPendingConflicts()
                _uiState.update {
                    it.copy(conflicts = pendingConflicts, isLoading = false)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load conflicts: ${e.message}"
                    )
                }
            }
        }
    }

    private fun resolveConflict(conflict: ConflictEntity, resolution: ConflictResolution) {
        viewModelScope.launch {
            _uiState.update { it.copy(isResolving = true) }

            conflictController.resolveConflict(
                conflictId = conflict.id,
                resolution = resolution,
                callback = object : ConflictResolutionController.ConflictResolutionCallback {
                    override fun onResolutionSuccess() {
                        _uiState.update {
                            it.copy(
                                isResolving = false,
                                successMessage = "Conflict resolved"
                            )
                        }
                        loadConflicts() // Reload list
                    }

                    override fun onResolutionError(error: String) {
                        _uiState.update {
                            it.copy(
                                isResolving = false,
                                errorMessage = "Error: $error"
                            )
                        }
                    }
                }
            )
        }
    }
}
