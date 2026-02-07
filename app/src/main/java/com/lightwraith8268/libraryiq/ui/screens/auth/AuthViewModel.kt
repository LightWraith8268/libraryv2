package com.lightwraith8268.libraryiq.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lightwraith8268.libraryiq.data.remote.FirestoreSync
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val isSignedIn: Boolean = false,
    val userEmail: String? = null,
    val libraryCode: String? = null,
    val email: String = "",
    val password: String = "",
    val joinCode: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val isSignUpMode: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val firestoreSync: FirestoreSync
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        refreshState()
        viewModelScope.launch {
            firestoreSync.observeAuthState().collect { isSignedIn ->
                refreshState()
            }
        }
    }

    private fun refreshState() {
        _uiState.update {
            it.copy(
                isSignedIn = firestoreSync.isSignedIn,
                userEmail = firestoreSync.userEmail,
                libraryCode = firestoreSync.libraryCode
            )
        }
    }

    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value) }
    fun onJoinCodeChange(value: String) = _uiState.update { it.copy(joinCode = value) }
    fun toggleSignUpMode() = _uiState.update {
        it.copy(isSignUpMode = !it.isSignUpMode, error = null)
    }

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
                            message = "Library created! Share this code: $code"
                        )
                    }
                    // Start syncing
                    firestoreSync.startListening()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = e.message ?: "Failed to create library"
                        )
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
                        it.copy(
                            isLoading = false,
                            joinCode = "",
                            message = "Joined library successfully!"
                        )
                    }
                    // Start syncing
                    firestoreSync.startListening()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = e.message ?: "Failed to join library"
                        )
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
}
