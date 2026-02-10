package com.inknironapps.libraryiq.ui.screens.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.local.entity.Collection
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import com.inknironapps.libraryiq.data.repository.BookRepository
import com.inknironapps.libraryiq.data.repository.CollectionRepository
import com.inknironapps.libraryiq.data.repository.CoverOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val editNotes: String = "",
    val editTags: String = "",
    // Cover picker
    val showCoverPicker: Boolean = false,
    val coverOptions: List<CoverOption> = emptyList(),
    val isFetchingCovers: Boolean = false
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
                        editNotes = bookWithCollections.book.notes ?: "",
                        editTags = bookWithCollections.book.tags ?: ""
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

    /** Updates reading status - uses per-user sync (doesn't overwrite other users' status). */
    fun updateReadingStatus(status: ReadingStatus) {
        val book = _uiState.value.book ?: return
        val now = System.currentTimeMillis()
        val updated = when (status) {
            ReadingStatus.READING -> book.copy(
                readingStatus = status,
                dateStarted = book.dateStarted ?: now
            )
            ReadingStatus.READ -> book.copy(
                readingStatus = status,
                dateFinished = book.dateFinished ?: now
            )
            else -> book.copy(readingStatus = status)
        }
        viewModelScope.launch {
            bookRepository.updateReadingStatus(updated)
        }
    }

    fun updateRating(rating: Float?) {
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            bookRepository.updateReadingStatus(book.copy(rating = rating))
        }
    }

    fun updateCurrentPage(page: Int?) {
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            bookRepository.updateReadingStatus(book.copy(currentPage = page))
        }
    }

    fun toggleEditing() {
        val current = _uiState.value
        _uiState.value = current.copy(
            isEditing = !current.isEditing,
            editTitle = current.book?.title ?: "",
            editAuthor = current.book?.author ?: "",
            editNotes = current.book?.notes ?: "",
            editTags = current.book?.tags ?: ""
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

    fun onEditTagsChange(value: String) {
        _uiState.value = _uiState.value.copy(editTags = value)
    }

    fun saveEdits() {
        val book = _uiState.value.book ?: return
        viewModelScope.launch {
            bookRepository.updateBook(
                book.copy(
                    title = _uiState.value.editTitle.trim(),
                    author = _uiState.value.editAuthor.trim(),
                    notes = _uiState.value.editNotes.trim().ifBlank { null },
                    tags = _uiState.value.editTags.trim().ifBlank { null }
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
                        subjects = book.subjects ?: fresh.subjects,
                        asin = book.asin ?: fresh.asin,
                        goodreadsId = book.goodreadsId ?: fresh.goodreadsId,
                        openLibraryId = book.openLibraryId ?: fresh.openLibraryId,
                        hardcoverId = book.hardcoverId ?: fresh.hardcoverId,
                        edition = book.edition ?: fresh.edition,
                        originalTitle = book.originalTitle ?: fresh.originalTitle,
                        originalLanguage = book.originalLanguage ?: fresh.originalLanguage
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

    fun openCoverPicker() {
        val book = _uiState.value.book ?: return
        _uiState.value = _uiState.value.copy(
            showCoverPicker = true,
            coverOptions = emptyList(),
            isFetchingCovers = true
        )
        viewModelScope.launch {
            try {
                val covers = bookRepository.fetchAllCovers(book.isbn, book.title, book.author)
                _uiState.value = _uiState.value.copy(
                    coverOptions = covers,
                    isFetchingCovers = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isFetchingCovers = false)
            }
        }
    }

    fun closeCoverPicker() {
        _uiState.value = _uiState.value.copy(showCoverPicker = false)
    }

    fun selectCover(url: String) {
        val book = _uiState.value.book ?: return
        _uiState.value = _uiState.value.copy(showCoverPicker = false)
        viewModelScope.launch {
            bookRepository.updateBook(book.copy(coverUrl = url))
        }
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
