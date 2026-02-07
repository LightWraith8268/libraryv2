package com.lightwraith8268.libraryiq.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lightwraith8268.libraryiq.data.local.AppDatabase
import com.lightwraith8268.libraryiq.data.remote.FirestoreSync
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val isSignedIn: Boolean = false,
    val userEmail: String? = null,
    val libraryCode: String? = null,
    val isSyncEnabled: Boolean = false,
    val email: String = "",
    val password: String = "",
    val joinCode: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val isSignUpMode: Boolean = false,
    val showClearDataDialog: Boolean = false,
    val dataCleared: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val firestoreSync: FirestoreSync,
    private val database: AppDatabase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshState()
        viewModelScope.launch {
            firestoreSync.observeAuthState().collect {
                refreshState()
            }
        }
    }

    private fun refreshState() {
        _uiState.update {
            it.copy(
                isSignedIn = firestoreSync.isSignedIn,
                userEmail = firestoreSync.userEmail,
                libraryCode = firestoreSync.libraryCode,
                isSyncEnabled = firestoreSync.isSyncEnabled
            )
        }
    }

    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value) }
    fun onJoinCodeChange(value: String) = _uiState.update { it.copy(joinCode = value) }
    fun toggleSignUpMode() = _uiState.update {
        it.copy(isSignUpMode = !it.isSignUpMode, error = null)
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
    fun clearMessage() = _uiState.update { it.copy(message = null) }

    fun signIn() {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = "Email and password are required") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = firestoreSync.signIn(state.email.trim(), state.password)
            result.fold(
                onSuccess = {
                    refreshState()
                    _uiState.update { it.copy(isLoading = false, password = "") }
                    if (firestoreSync.isSyncEnabled) {
                        firestoreSync.startListening()
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "Sign in failed")
                    }
                }
            )
        }
    }

    fun signUp() {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = "Email and password are required") }
            return
        }
        if (state.password.length < 6) {
            _uiState.update { it.copy(error = "Password must be at least 6 characters") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = firestoreSync.signUp(state.email.trim(), state.password)
            result.fold(
                onSuccess = {
                    refreshState()
                    _uiState.update { it.copy(isLoading = false, password = "") }
                    if (firestoreSync.isSyncEnabled) {
                        firestoreSync.startListening()
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "Sign up failed")
                    }
                }
            )
        }
    }

    fun signOut() {
        firestoreSync.signOut()
        refreshState()
    }

    fun createLibrary() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            val result = firestoreSync.createLibrary()
            result.fold(
                onSuccess = { code ->
                    refreshState()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = "Library created! Share code: $code"
                        )
                    }
                    firestoreSync.startListening()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "Failed to create library")
                    }
                }
            )
        }
    }

    fun joinLibrary() {
        val code = _uiState.value.joinCode.trim()
        if (code.isBlank()) {
            _uiState.update { it.copy(error = "Enter a library code") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            val result = firestoreSync.joinLibrary(code)
            result.fold(
                onSuccess = {
                    refreshState()
                    _uiState.update {
                        it.copy(isLoading = false, joinCode = "", message = "Joined library!")
                    }
                    firestoreSync.startListening()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "Failed to join")
                    }
                }
            )
        }
    }

    fun leaveLibrary() {
        firestoreSync.leaveLibrary()
        refreshState()
        _uiState.update { it.copy(message = "Left shared library") }
    }

    fun showClearDataDialog() = _uiState.update { it.copy(showClearDataDialog = true) }
    fun hideClearDataDialog() = _uiState.update { it.copy(showClearDataDialog = false) }

    fun clearAllData() {
        viewModelScope.launch {
            firestoreSync.stopListening()
            database.clearAllTables()
            if (firestoreSync.isSyncEnabled) {
                firestoreSync.startListening()
            }
            _uiState.update {
                it.copy(
                    showClearDataDialog = false,
                    dataCleared = true,
                    message = "All local data cleared"
                )
            }
        }
    }
}
