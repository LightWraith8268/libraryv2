package com.lightwraith8268.libraryiq.ui.screens.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.lightwraith8268.libraryiq.R
import com.lightwraith8268.libraryiq.data.billing.BillingManager
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
    val hasProAccess: Boolean = false,
    val joinCode: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val firestoreSync: FirestoreSync,
    private val billingManager: BillingManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        refreshState()
        viewModelScope.launch {
            firestoreSync.observeAuthState().collect {
                billingManager.refreshAdminStatus()
                refreshState()
            }
        }
        viewModelScope.launch {
            billingManager.isSubscribed.collect { refreshState() }
        }
        viewModelScope.launch {
            billingManager.isAdmin.collect { refreshState() }
        }
    }

    private fun refreshState() {
        billingManager.refreshAdminStatus()
        _uiState.update {
            it.copy(
                isSignedIn = firestoreSync.isSignedIn,
                userEmail = firestoreSync.userEmail,
                libraryCode = firestoreSync.libraryCode,
                hasProAccess = billingManager.hasProAccess
            )
        }
    }

    fun onJoinCodeChange(value: String) = _uiState.update { it.copy(joinCode = value) }

    fun getGoogleSignInIntent(activity: Activity) = GoogleSignIn.getClient(
        activity,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(activity.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
    ).signInIntent

    fun handleGoogleSignInResult(idToken: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = firestoreSync.signInWithGoogle(idToken)
            result.fold(
                onSuccess = {
                    billingManager.refreshAdminStatus()
                    refreshState()
                    _uiState.update { it.copy(isLoading = false) }
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

    fun signOut(activity: Activity) {
        firestoreSync.signOut()
        GoogleSignIn.getClient(
            activity,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        ).signOut()
        billingManager.refreshAdminStatus()
        refreshState()
    }

    fun createLibrary() {
        if (!billingManager.hasProAccess) {
            _uiState.update { it.copy(error = "A subscription is required to create a library") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, message = null) }
            val result = firestoreSync.createLibrary()
            result.fold(
                onSuccess = { code ->
                    refreshState()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            message = "Library created! Share code: $code with others to join."
                        )
                    }
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
        _uiState.update { it.copy(message = "Left library") }
    }

    fun launchSubscription(activity: Activity) {
        billingManager.launchSubscription(activity)
    }
}
