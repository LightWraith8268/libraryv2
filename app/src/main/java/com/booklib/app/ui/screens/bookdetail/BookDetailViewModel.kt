package com.booklib.app.ui.screens.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.booklib.app.data.local.entity.Book
import com.booklib.app.data.local.entity.BookWithCollections
import com.booklib.app.data.local.entity.Collection
import com.booklib.app.data.local.entity.ReadingStatus
import com.booklib.app.data.repository.BookRepository
import com.booklib.app.data.repository.CollectionRepository
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
