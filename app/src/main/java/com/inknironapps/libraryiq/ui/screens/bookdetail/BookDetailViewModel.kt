package com.inknironapps.libraryiq.ui.screens.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.local.entity.BookWithCollections
import com.inknironapps.libraryiq.data.local.entity.Collection
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import com.inknironapps.libraryiq.data.repository.BookRepository
import com.inknironapps.libraryiq.data.repository.CollectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BookDetailUiState(
    val book: Book? = null,
    val collections: List<Collection> = emptyList(),
    val allCollections: List<Collection> = emptyList(),
    val isEditing: Boolean = false,
    val isDeleted: Boolean = false,
    val isRefreshing: Boolean = false,
    val refreshMessage: String? = null,
    val editTitle: String = "",
    val editAuthor: String = "",
    val editNotes: String = ""
)

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val collectionRepository: CollectionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    fun loadBook(bookId: String) {
        viewModelScope.launch {
            bookRepository.getBookWithCollections(bookId).collect { bookWithCollections ->
                if (bookWithCollections != null) {
                    _uiState.value = _uiState.value.copy(
                        book = bookWithCollections.book,
                        collections = bookWithCollections.collections,
                        editTitle = bookWithCollections.book.title,
                        editAuthor = bookWithCollections.book.author,
                        editNotes = bookWithCollections.book.notes ?: ""
                    )
                }
            }
        }

        viewModelScope.launch {
            collectionRepository.getAllCollections().collect { all ->
                _uiState.value = _uiState.value.copy(allCollections = all)
            }
        }
    }

    fun updateReadingStatus(status: ReadingStatus) {
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            bookRepository.updateBook(book.copy(readingStatus = status))
        }
    }

    fun toggleEditing() {
        val current = _uiState.value
        _uiState.value = current.copy(
            isEditing = !current.isEditing,
            editTitle = current.book?.title ?: "",
            editAuthor = current.book?.author ?: "",
            editNotes = current.book?.notes ?: ""
        )
    }

    fun onEditTitleChange(value: String) {
        _uiState.value = _uiState.value.copy(editTitle = value)
    }

    fun onEditAuthorChange(value: String) {
        _uiState.value = _uiState.value.copy(editAuthor = value)
    }

    fun onEditNotesChange(value: String) {
        _uiState.value = _uiState.value.copy(editNotes = value)
    }

    fun saveEdits() {
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            bookRepository.updateBook(
                book.copy(
                    title = _uiState.value.editTitle.trim(),
                    author = _uiState.value.editAuthor.trim(),
                    notes = _uiState.value.editNotes.trim().ifBlank { null }
                )
            )
            _uiState.value = _uiState.value.copy(isEditing = false)
        }
    }

    fun refreshMetadata() {
        val book = _uiState.value.book ?: return
        val isbn = book.isbn
        if (isbn.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(
                refreshMessage = "No ISBN available to look up"
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, refreshMessage = null)
            try {
                // Temporarily remove from local DB cache so lookupByIsbn doesn't short-circuit
                val result = bookRepository.lookupByIsbnSkipLocal(isbn)
                if (result.book != null) {
                    val fresh = result.book
                    // Merge: keep user-set fields, fill in missing metadata
                    val updated = book.copy(
                        title = if (book.title == "Unknown Title" && fresh.title != "Unknown Title") fresh.title else book.title,
                        author = if (book.author == "Unknown Author" && fresh.author != "Unknown Author") fresh.author else book.author,
                        description = book.description ?: fresh.description,
                        coverUrl = book.coverUrl ?: fresh.coverUrl,
                        pageCount = book.pageCount ?: fresh.pageCount,
                        publisher = book.publisher ?: fresh.publisher,
                        publishedDate = book.publishedDate ?: fresh.publishedDate,
                        isbn10 = book.isbn10 ?: fresh.isbn10,
                        series = book.series ?: fresh.series,
                        seriesNumber = book.seriesNumber ?: fresh.seriesNumber,
                        genre = book.genre ?: fresh.genre,
                        language = book.language ?: fresh.language,
                        format = book.format ?: fresh.format,
                        subjects = book.subjects ?: fresh.subjects
                    )
                    bookRepository.updateBook(updated)
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        refreshMessage = "Metadata updated\n${result.diagnostics}"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        refreshMessage = "No new data found\n${result.diagnostics}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    refreshMessage = "Refresh failed: ${e.message}"
                )
            }
        }
    }

    fun clearRefreshMessage() {
        _uiState.value = _uiState.value.copy(refreshMessage = null)
    }

    fun deleteBook() {
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            bookRepository.deleteBook(book)
            _uiState.value = _uiState.value.copy(isDeleted = true)
        }
    }

    fun addToCollection(collectionId: String) {
        val bookId = _uiState.value.book?.id ?: return
        viewModelScope.launch {
            collectionRepository.addBookToCollection(bookId, collectionId)
        }
    }

    fun removeFromCollection(collectionId: String) {
        val bookId = _uiState.value.book?.id ?: return
        viewModelScope.launch {
            collectionRepository.removeBookFromCollection(bookId, collectionId)
        }
    }
}
