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
    val editDescription: String = "",
    val editPageCount: String = "",
    val editPublisher: String = "",
    val editPublishedDate: String = "",
    val editSeries: String = "",
    val editSeriesNumber: String = "",
    val editLanguage: String = "",
    val editFormat: String = "",
    val editGenre: String = "",
    val editSubjects: String = "",
    val editEdition: String = "",
    val editIsbn: String = "",
    val editIsbn10: String = "",
    val editOriginalTitle: String = "",
    val editOriginalLanguage: String = "",
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
                    val b = bookWithCollections.book
                    _uiState.value = _uiState.value.copy(
                        book = b,
                        collections = bookWithCollections.collections,
                        editTitle = b.title,
                        editAuthor = b.author,
                        editDescription = b.description ?: "",
                        editPageCount = b.pageCount?.toString() ?: "",
                        editPublisher = b.publisher ?: "",
                        editPublishedDate = b.publishedDate ?: "",
                        editSeries = b.series ?: "",
                        editSeriesNumber = b.seriesNumber ?: "",
                        editLanguage = b.language ?: "",
                        editFormat = b.format ?: "",
                        editGenre = b.genre ?: "",
                        editSubjects = b.subjects ?: "",
                        editEdition = b.edition ?: "",
                        editIsbn = b.isbn ?: "",
                        editIsbn10 = b.isbn10 ?: "",
                        editOriginalTitle = b.originalTitle ?: "",
                        editOriginalLanguage = b.originalLanguage ?: "",
                        editNotes = b.notes ?: "",
                        editTags = b.tags ?: ""
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
        val b = current.book
        _uiState.value = current.copy(
            isEditing = !current.isEditing,
            editTitle = b?.title ?: "",
            editAuthor = b?.author ?: "",
            editDescription = b?.description ?: "",
            editPageCount = b?.pageCount?.toString() ?: "",
            editPublisher = b?.publisher ?: "",
            editPublishedDate = b?.publishedDate ?: "",
            editSeries = b?.series ?: "",
            editSeriesNumber = b?.seriesNumber ?: "",
            editLanguage = b?.language ?: "",
            editFormat = b?.format ?: "",
            editGenre = b?.genre ?: "",
            editSubjects = b?.subjects ?: "",
            editEdition = b?.edition ?: "",
            editIsbn = b?.isbn ?: "",
            editIsbn10 = b?.isbn10 ?: "",
            editOriginalTitle = b?.originalTitle ?: "",
            editOriginalLanguage = b?.originalLanguage ?: "",
            editNotes = b?.notes ?: "",
            editTags = b?.tags ?: ""
        )
    }

    fun onEditTitleChange(value: String) { _uiState.value = _uiState.value.copy(editTitle = value) }
    fun onEditAuthorChange(value: String) { _uiState.value = _uiState.value.copy(editAuthor = value) }
    fun onEditDescriptionChange(value: String) { _uiState.value = _uiState.value.copy(editDescription = value) }
    fun onEditPageCountChange(value: String) { _uiState.value = _uiState.value.copy(editPageCount = value) }
    fun onEditPublisherChange(value: String) { _uiState.value = _uiState.value.copy(editPublisher = value) }
    fun onEditPublishedDateChange(value: String) { _uiState.value = _uiState.value.copy(editPublishedDate = value) }
    fun onEditSeriesChange(value: String) { _uiState.value = _uiState.value.copy(editSeries = value) }
    fun onEditSeriesNumberChange(value: String) { _uiState.value = _uiState.value.copy(editSeriesNumber = value) }
    fun onEditLanguageChange(value: String) { _uiState.value = _uiState.value.copy(editLanguage = value) }
    fun onEditFormatChange(value: String) { _uiState.value = _uiState.value.copy(editFormat = value) }
    fun onEditGenreChange(value: String) { _uiState.value = _uiState.value.copy(editGenre = value) }
    fun onEditSubjectsChange(value: String) { _uiState.value = _uiState.value.copy(editSubjects = value) }
    fun onEditEditionChange(value: String) { _uiState.value = _uiState.value.copy(editEdition = value) }
    fun onEditIsbnChange(value: String) { _uiState.value = _uiState.value.copy(editIsbn = value) }
    fun onEditIsbn10Change(value: String) { _uiState.value = _uiState.value.copy(editIsbn10 = value) }
    fun onEditOriginalTitleChange(value: String) { _uiState.value = _uiState.value.copy(editOriginalTitle = value) }
    fun onEditOriginalLanguageChange(value: String) { _uiState.value = _uiState.value.copy(editOriginalLanguage = value) }
    fun onEditNotesChange(value: String) { _uiState.value = _uiState.value.copy(editNotes = value) }
    fun onEditTagsChange(value: String) { _uiState.value = _uiState.value.copy(editTags = value) }

    fun saveEdits() {
        val book = _uiState.value.book ?: return
        val s = _uiState.value
        viewModelScope.launch {
            bookRepository.updateBook(
                book.copy(
                    title = s.editTitle.trim(),
                    author = s.editAuthor.trim(),
                    description = s.editDescription.trim().ifBlank { null },
                    pageCount = s.editPageCount.trim().toIntOrNull(),
                    publisher = s.editPublisher.trim().ifBlank { null },
                    publishedDate = s.editPublishedDate.trim().ifBlank { null },
                    series = s.editSeries.trim().ifBlank { null },
                    seriesNumber = s.editSeriesNumber.trim().ifBlank { null },
                    language = s.editLanguage.trim().ifBlank { null },
                    format = s.editFormat.trim().ifBlank { null },
                    genre = s.editGenre.trim().ifBlank { null },
                    subjects = s.editSubjects.trim().ifBlank { null },
                    edition = s.editEdition.trim().ifBlank { null },
                    isbn = s.editIsbn.trim().ifBlank { null },
                    isbn10 = s.editIsbn10.trim().ifBlank { null },
                    originalTitle = s.editOriginalTitle.trim().ifBlank { null },
                    originalLanguage = s.editOriginalLanguage.trim().ifBlank { null },
                    notes = s.editNotes.trim().ifBlank { null },
                    tags = s.editTags.trim().ifBlank { null }
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
                    // Merge: prefer fresh metadata for most fields since user explicitly refreshed.
                    // Keep user-editable fields (reading status, rating, notes, dates) untouched.
                    // If user manually chose a cover, keep it.
                    // Series: use fresh data directly — if the APIs say no series, clear it.
                    // This ensures stale/incorrect series data gets corrected on refresh.
                    val freshSeries = fresh.series?.trim()?.ifBlank { null }
                    val freshSeriesNumber = fresh.seriesNumber?.trim()?.ifBlank { null }
                    val updated = book.copy(
                        title = if (fresh.title != "Unknown Title") fresh.title else book.title,
                        author = if (fresh.author != "Unknown Author") fresh.author else book.author,
                        description = fresh.description ?: book.description,
                        coverUrl = if (book.coverManuallySet) book.coverUrl else (fresh.coverUrl ?: book.coverUrl),
                        pageCount = fresh.pageCount ?: book.pageCount,
                        publisher = fresh.publisher ?: book.publisher,
                        publishedDate = fresh.publishedDate ?: book.publishedDate,
                        isbn10 = fresh.isbn10 ?: book.isbn10,
                        series = freshSeries,
                        seriesNumber = freshSeriesNumber,
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
                        originalLanguage = fresh.originalLanguage ?: book.originalLanguage,
                        metadataSources = fresh.metadataSources ?: book.metadataSources
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
            bookRepository.updateBook(book.copy(coverUrl = url, coverManuallySet = true))
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
