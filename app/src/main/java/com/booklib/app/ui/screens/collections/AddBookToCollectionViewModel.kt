package com.booklib.app.ui.screens.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.booklib.app.data.local.entity.Book
import com.booklib.app.data.repository.BookRepository
import com.booklib.app.data.repository.CollectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddBookToCollectionViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val collectionRepository: CollectionRepository
) : ViewModel() {

    val allBooks: StateFlow<List<Book>> = bookRepository.getAllBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _booksInCollection = MutableStateFlow<List<Book>>(emptyList())
    val booksInCollection: StateFlow<List<Book>> = _booksInCollection

    private var collectionId: String = ""

    fun loadCollection(id: String) {
        collectionId = id
        viewModelScope.launch {
            collectionRepository.getCollectionWithBooks(id).collect { cwb ->
                _booksInCollection.value = cwb?.books ?: emptyList()
            }
        }
    }

    fun addBook(bookId: String) {
        viewModelScope.launch {
            collectionRepository.addBookToCollection(bookId, collectionId)
        }
    }

    fun removeBook(bookId: String) {
        viewModelScope.launch {
            collectionRepository.removeBookFromCollection(bookId, collectionId)
        }
    }
}
