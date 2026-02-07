package com.booklib.app.ui.screens.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.booklib.app.data.local.entity.Book
import com.booklib.app.data.local.entity.Collection
import com.booklib.app.data.repository.BookRepository
import com.booklib.app.data.repository.CollectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionDetailUiState(
    val collection: Collection? = null,
    val books: List<Book> = emptyList(),
    val isDeleted: Boolean = false
)

@HiltViewModel
class CollectionDetailViewModel @Inject constructor(
    private val collectionRepository: CollectionRepository,
    private val bookRepository: BookRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CollectionDetailUiState())
    val uiState: StateFlow<CollectionDetailUiState> = _uiState.asStateFlow()

    fun loadCollection(collectionId: String) {
        viewModelScope.launch {
            collectionRepository.getCollectionWithBooks(collectionId).collect { cwb ->
                if (cwb != null) {
                    _uiState.update {
                        it.copy(collection = cwb.collection, books = cwb.books)
                    }
                }
            }
        }
    }

    fun removeBookFromCollection(bookId: String) {
        val collectionId = _uiState.value.collection?.id ?: return
        viewModelScope.launch {
            collectionRepository.removeBookFromCollection(bookId, collectionId)
        }
    }

    fun deleteCollection() {
        val collection = _uiState.value.collection ?: return
        viewModelScope.launch {
            collectionRepository.deleteCollection(collection)
            _uiState.update { it.copy(isDeleted = true) }
        }
    }
}
