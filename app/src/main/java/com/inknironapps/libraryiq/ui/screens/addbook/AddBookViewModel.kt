package com.inknironapps.libraryiq.ui.screens.addbook

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import com.inknironapps.libraryiq.data.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddBookUiState(
    val title: String = "",
    val author: String = "",
    val isbn: String = "",
    val description: String = "",
    val pageCount: String = "",
    val publisher: String = "",
    val publishedDate: String = "",
    val coverUrl: String = "",
    val series: String = "",
    val seriesNumber: String = "",
    val language: String = "",
    val readingStatus: ReadingStatus = ReadingStatus.UNREAD,
    val isLoading: Boolean = false,
    val isLookingUp: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AddBookViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddBookUiState())
    val uiState: StateFlow<AddBookUiState> = _uiState.asStateFlow()

    init {
        val isbn = savedStateHandle.get<String>("isbn")
        if (!isbn.isNullOrBlank()) {
            _uiState.update { it.copy(isbn = isbn) }
            lookupIsbn()
        }
    }

    fun onTitleChange(value: String) = _uiState.update { it.copy(title = value) }
    fun onAuthorChange(value: String) = _uiState.update { it.copy(author = value) }
    fun onIsbnChange(value: String) = _uiState.update { it.copy(isbn = value) }
    fun onDescriptionChange(value: String) = _uiState.update { it.copy(description = value) }
    fun onPageCountChange(value: String) = _uiState.update { it.copy(pageCount = value) }
    fun onPublisherChange(value: String) = _uiState.update { it.copy(publisher = value) }
    fun onPublishedDateChange(value: String) = _uiState.update { it.copy(publishedDate = value) }
    fun onCoverUrlChange(value: String) = _uiState.update { it.copy(coverUrl = value) }
    fun onSeriesChange(value: String) = _uiState.update { it.copy(series = value) }
    fun onSeriesNumberChange(value: String) = _uiState.update { it.copy(seriesNumber = value) }
    fun onLanguageChange(value: String) = _uiState.update { it.copy(language = value) }
    fun onReadingStatusChange(status: ReadingStatus) =
        _uiState.update { it.copy(readingStatus = status) }

    fun lookupIsbn() {
        val isbn = _uiState.value.isbn.trim()
        if (isbn.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLookingUp = true, error = null) }
            try {
                val result = bookRepository.lookupByIsbn(isbn)
                val book = result.book
                if (book != null) {
                    _uiState.update {
                        it.copy(
                            title = book.title,
                            author = book.author,
                            description = book.description ?: "",
                            pageCount = book.pageCount?.toString() ?: "",
                            publisher = book.publisher ?: "",
                            publishedDate = book.publishedDate ?: "",
                            coverUrl = book.coverUrl ?: "",
                            series = book.series ?: "",
                            seriesNumber = book.seriesNumber ?: "",
                            language = book.language ?: "",
                            isLookingUp = false
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLookingUp = false,
                            error = "No book found for this ISBN\n${result.diagnostics}"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLookingUp = false, error = "Lookup failed: ${e.message}")
                }
            }
        }
    }

    fun lookupByTitle() {
        val title = _uiState.value.title.trim()
        if (title.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLookingUp = true, error = null) }
            try {
                val author = _uiState.value.author.trim().ifBlank { null }
                val result = bookRepository.lookupByTitle(title, author)
                val book = result.book
                if (book != null) {
                    _uiState.update {
                        it.copy(
                            title = book.title,
                            author = book.author,
                            isbn = book.isbn ?: it.isbn,
                            description = book.description ?: it.description,
                            pageCount = book.pageCount?.toString() ?: it.pageCount,
                            publisher = book.publisher ?: it.publisher,
                            publishedDate = book.publishedDate ?: it.publishedDate,
                            coverUrl = book.coverUrl ?: it.coverUrl,
                            series = book.series ?: it.series,
                            seriesNumber = book.seriesNumber ?: it.seriesNumber,
                            language = book.language ?: it.language,
                            isLookingUp = false
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLookingUp = false,
                            error = "No book found for \"$title\"\n${result.diagnostics}"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLookingUp = false, error = "Title search failed: ${e.message}")
                }
            }
        }
    }

    fun saveBook() {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.update { it.copy(error = "Title is required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val book = Book(
                    title = state.title.trim(),
                    author = state.author.trim().ifBlank { "Unknown Author" },
                    isbn = state.isbn.trim().ifBlank { null },
                    description = state.description.trim().ifBlank { null },
                    pageCount = state.pageCount.toIntOrNull(),
                    publisher = state.publisher.trim().ifBlank { null },
                    publishedDate = state.publishedDate.trim().ifBlank { null },
                    coverUrl = state.coverUrl.trim().ifBlank { null },
                    series = state.series.trim().ifBlank { null },
                    seriesNumber = state.seriesNumber.trim().ifBlank { null },
                    language = state.language.trim().ifBlank { null },
                    readingStatus = state.readingStatus
                )
                bookRepository.addBook(book)
                _uiState.update { it.copy(isLoading = false, isSaved = true) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to save: ${e.message}")
                }
            }
        }
    }
}
