package com.inknironapps.libraryiq.ui.screens.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.repository.BookRepository
import com.inknironapps.libraryiq.ui.screens.library.LibraryPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScannerUiState(
    val scannedIsbn: String? = null,
    val lookupResult: Book? = null,
    val isLookingUp: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null,
    val diagnostics: String? = null,
    val isScanning: Boolean = true,
    val addedCount: Int = 0
)

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    val libraryPreferences: LibraryPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private var lastScannedIsbn: String? = null
    private var lookupJob: Job? = null

    fun onBarcodeDetected(rawValue: String) {
        // Only accept one barcode per scan session; ignore all others until scanAgain()
        if (lastScannedIsbn != null) return
        lastScannedIsbn = rawValue

        _uiState.update {
            it.copy(
                scannedIsbn = rawValue,
                isScanning = false,
                isLookingUp = true,
                error = null,
                lookupResult = null
            )
        }

        lookupJob = viewModelScope.launch {
            try {
                val result = bookRepository.lookupByIsbn(rawValue)
                if (result.book != null) {
                    _uiState.update {
                        it.copy(
                            lookupResult = result.book,
                            isLookingUp = false,
                            diagnostics = result.diagnostics
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLookingUp = false,
                            error = "No book found for ISBN: $rawValue",
                            diagnostics = result.diagnostics
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // Don't swallow cancellation
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLookingUp = false, error = "Lookup failed: ${e.message}")
                }
            }
        }
    }

    fun addToLibrary() {
        val book = _uiState.value.lookupResult ?: return
        viewModelScope.launch {
            try {
                bookRepository.addBook(book)
                if (libraryPreferences.continuousScan) {
                    val newCount = _uiState.value.addedCount + 1
                    lastScannedIsbn = null
                    _uiState.value = ScannerUiState(addedCount = newCount)
                } else {
                    _uiState.update { it.copy(isSaved = true) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to save: ${e.message}") }
            }
        }
    }

    fun scanAgain() {
        lastScannedIsbn = null
        _uiState.value = _uiState.value.let {
            ScannerUiState(addedCount = it.addedCount)
        }
    }
}
