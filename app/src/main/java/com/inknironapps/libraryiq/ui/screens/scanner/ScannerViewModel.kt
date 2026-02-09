package com.inknironapps.libraryiq.ui.screens.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.repository.BookRepository
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
    val isScanning: Boolean = true
)

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val bookRepository: BookRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private var lastScannedIsbn: String? = null
    private var lookupJob: Job? = null

    fun onBarcodeDetected(rawValue: String) {
        // Avoid duplicate scans of the same barcode
        if (rawValue == lastScannedIsbn) return
        lastScannedIsbn = rawValue

        // Cancel any in-flight lookup so it doesn't interfere
        lookupJob?.cancel()

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
                _uiState.update { it.copy(isSaved = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to save: ${e.message}") }
            }
        }
    }

    fun scanAgain() {
        lastScannedIsbn = null
        _uiState.value = ScannerUiState()
    }
}
