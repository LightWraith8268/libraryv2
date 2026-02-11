package com.inknironapps.libraryiq.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.inknironapps.libraryiq.R
import com.inknironapps.libraryiq.data.billing.BillingManager
import com.inknironapps.libraryiq.data.local.AppDatabase
import com.inknironapps.libraryiq.data.remote.FirestoreSync
import com.inknironapps.libraryiq.data.repository.BookRepository
import com.inknironapps.libraryiq.data.update.AppUpdateManager
import com.inknironapps.libraryiq.data.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class SettingsUiState(
    val isSignedIn: Boolean = false,
    val userEmail: String? = null,
    val libraryCode: String? = null,
    val isSyncEnabled: Boolean = false,
    val hasProAccess: Boolean = false,
    val isSubscribed: Boolean = false,
    val isAdmin: Boolean = false,
    val isLibraryCreator: Boolean = false,
    val subscriptionPrice: String? = null,
    val joinCode: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val showClearDataDialog: Boolean = false,
    val dataCleared: Boolean = false,
    val updateInfo: UpdateInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val isSideloaded: Boolean = false,
    val isRefreshingLibrary: Boolean = false,
    val refreshProgress: Int = 0,
    val refreshTotal: Int = 0,
    val updateMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val firestoreSync: FirestoreSync,
    private val billingManager: BillingManager,
    private val database: AppDatabase,
    private val bookRepository: BookRepository,
    private val appUpdateManager: AppUpdateManager,
    val libraryPreferences: com.inknironapps.libraryiq.ui.screens.library.LibraryPreferences,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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
        viewModelScope.launch {
            billingManager.productDetails.collect { details ->
                val price = details?.subscriptionOfferDetails?.firstOrNull()
                    ?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
                _uiState.update { it.copy(subscriptionPrice = price) }
            }
        }
    }

    private fun refreshState() {
        billingManager.refreshAdminStatus()
        _uiState.update {
            it.copy(
                isSignedIn = firestoreSync.isSignedIn,
                userEmail = firestoreSync.userEmail,
                libraryCode = firestoreSync.libraryCode,
                isSyncEnabled = firestoreSync.isSyncEnabled,
                hasProAccess = billingManager.hasProAccess,
                isSubscribed = billingManager.isSubscribed.value,
                isAdmin = billingManager.isAdmin.value,
                isLibraryCreator = firestoreSync.isLibraryCreator,
                isSideloaded = appUpdateManager.isSideloaded
            )
        }
    }

    fun onJoinCodeChange(value: String) = _uiState.update { it.copy(joinCode = value) }
    fun clearError() = _uiState.update { it.copy(error = null) }
    fun clearMessage() = _uiState.update { it.copy(message = null) }
    fun showSignInError(message: String) = _uiState.update { it.copy(error = message, isLoading = false) }

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

                    // Restore library code from Firestore if not stored locally
                    val restoredCode = firestoreSync.restoreLibraryCode()
                    if (restoredCode != null) {
                        firestoreSync.startListening()
                    }

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
        _uiState.update { it.copy(message = "Left library") }
    }

    fun launchSubscription(activity: Activity) {
        billingManager.launchSubscription(activity)
    }

    fun restorePurchases() {
        billingManager.refresh()
        _uiState.update { it.copy(message = "Checking for existing purchases...") }
    }

    fun forceSync() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, message = "Syncing...") }
            val result = firestoreSync.forceSync()
            result.fold(
                onSuccess = { changes ->
                    _uiState.update {
                        it.copy(isLoading = false, message = "Sync complete ($changes changes)")
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "Sync failed: ${e.message}")
                    }
                }
            )
        }
    }

    fun refreshLibraryMetadata() {
        viewModelScope.launch {
            val books = withContext(Dispatchers.IO) {
                database.bookDao().getAllBooksList()
            }
            val booksWithIsbn = books.filter { !it.isbn.isNullOrBlank() }
            if (booksWithIsbn.isEmpty()) {
                _uiState.update { it.copy(message = "No books with ISBNs to refresh") }
                return@launch
            }
            _uiState.update {
                it.copy(
                    isRefreshingLibrary = true,
                    refreshProgress = 0,
                    refreshTotal = booksWithIsbn.size,
                    message = "Refreshing metadata: 0/${booksWithIsbn.size}..."
                )
            }
            var updated = 0
            for ((index, book) in booksWithIsbn.withIndex()) {
                try {
                    val result = bookRepository.lookupByIsbnSkipLocal(book.isbn!!)
                    if (result.book != null) {
                        val fresh = result.book
                        val merged = book.copy(
                            title = if (fresh.title != "Unknown Title") fresh.title else book.title,
                            author = if (fresh.author != "Unknown Author") fresh.author else book.author,
                            description = fresh.description ?: book.description,
                            coverUrl = fresh.coverUrl ?: book.coverUrl,
                            pageCount = fresh.pageCount ?: book.pageCount,
                            publisher = fresh.publisher ?: book.publisher,
                            publishedDate = fresh.publishedDate ?: book.publishedDate,
                            isbn10 = fresh.isbn10 ?: book.isbn10,
                            series = fresh.series ?: book.series,
                            seriesNumber = fresh.seriesNumber ?: book.seriesNumber,
                            genre = fresh.genre ?: book.genre,
                            language = fresh.language ?: book.language,
                            format = book.format ?: fresh.format,
                            subjects = fresh.subjects ?: book.subjects,
                            asin = fresh.asin ?: book.asin,
                            goodreadsId = fresh.goodreadsId ?: book.goodreadsId,
                            openLibraryId = fresh.openLibraryId ?: book.openLibraryId,
                            hardcoverId = fresh.hardcoverId ?: book.hardcoverId,
                            edition = fresh.edition ?: book.edition,
                            originalTitle = fresh.originalTitle ?: book.originalTitle,
                            originalLanguage = fresh.originalLanguage ?: book.originalLanguage
                        )
                        bookRepository.updateBook(merged)
                        updated++
                    }
                } catch (_: Exception) {
                    // Skip failed books, continue with next
                }
                _uiState.update {
                    it.copy(
                        refreshProgress = index + 1,
                        message = "Refreshing metadata: ${index + 1}/${booksWithIsbn.size}..."
                    )
                }
            }
            _uiState.update {
                it.copy(
                    isRefreshingLibrary = false,
                    refreshProgress = 0,
                    refreshTotal = 0,
                    message = "Metadata refreshed for $updated of ${booksWithIsbn.size} books"
                )
            }
        }
    }

    fun showClearDataDialog() = _uiState.update { it.copy(showClearDataDialog = true) }
    fun hideClearDataDialog() = _uiState.update { it.copy(showClearDataDialog = false) }

    fun clearAllData() {
        viewModelScope.launch {
            firestoreSync.leaveLibrary()
            database.clearAllTables()
            refreshState()
            _uiState.update {
                it.copy(
                    showClearDataDialog = false,
                    dataCleared = true,
                    message = "All local data cleared and disconnected from library"
                )
            }
        }
    }

    fun exportLibraryCsv(activity: Activity) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(message = "Exporting...") }
                val csvFile = withContext(Dispatchers.IO) {
                    val books = database.bookDao().getAllBooksList()
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val file = File(context.cacheDir, "LibraryIQ_export_$timestamp.csv")
                    file.bufferedWriter().use { writer ->
                        writer.write("Title,Author,ISBN,ISBN-10,Reading Status,Rating,Series,Series Number,Genre,Page Count,Publisher,Published Date,Date Added,Notes,Tags")
                        writer.newLine()
                        for (book in books) {
                            writer.write(listOf(
                                book.title.csvEscape(),
                                book.author.csvEscape(),
                                book.isbn.orEmpty(),
                                book.isbn10.orEmpty(),
                                book.readingStatus.name,
                                book.rating?.toString().orEmpty(),
                                book.series?.csvEscape().orEmpty(),
                                book.seriesNumber.orEmpty(),
                                book.genre?.csvEscape().orEmpty(),
                                book.pageCount?.toString().orEmpty(),
                                book.publisher?.csvEscape().orEmpty(),
                                book.publishedDate.orEmpty(),
                                book.dateAdded.toString(),
                                book.notes?.csvEscape().orEmpty(),
                                book.tags?.csvEscape().orEmpty()
                            ).joinToString(","))
                            writer.newLine()
                        }
                    }
                    file
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    csvFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(shareIntent, "Export Library"))
                _uiState.update { it.copy(message = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Export failed: ${e.message}") }
            }
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingUpdate = true, updateMessage = null) }
            try {
                val info = appUpdateManager.checkForUpdate()
                _uiState.update {
                    it.copy(
                        isCheckingUpdate = false,
                        updateInfo = info,
                        updateMessage = if (info == null) "No updates available at this time"
                        else if (!info.isNewer) "You're on the latest version"
                        else null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isCheckingUpdate = false,
                        updateMessage = "Could not check for updates: ${e.message}"
                    )
                }
            }
        }
    }

    fun downloadUpdate() {
        val info = _uiState.value.updateInfo ?: return
        _uiState.update { it.copy(updateMessage = "Downloading update...", updateInfo = null) }
        viewModelScope.launch {
            val success = appUpdateManager.downloadAndInstall(info)
            _uiState.update {
                it.copy(updateMessage = if (success) null else "Download failed. Please try again.")
            }
        }
    }

    fun dismissUpdate() {
        _uiState.update { it.copy(updateInfo = null) }
    }

    private fun String.csvEscape(): String {
        return if (contains(',') || contains('"') || contains('\n')) {
            "\"${replace("\"", "\"\"")}\""
        } else this
    }
}
