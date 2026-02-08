package com.inknironapps.libraryiq.ui.screens.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inknironapps.libraryiq.data.local.entity.Collection
import com.inknironapps.libraryiq.data.local.entity.CollectionWithBooks
import com.inknironapps.libraryiq.data.repository.CollectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val collectionRepository: CollectionRepository
) : ViewModel() {

    val collectionsWithBooks: StateFlow<List<CollectionWithBooks>> =
        collectionRepository.getAllCollectionsWithBooks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _showCreateDialog = MutableStateFlow(false)
    val showCreateDialog: StateFlow<Boolean> = _showCreateDialog.asStateFlow()

    private val _newCollectionName = MutableStateFlow("")
    val newCollectionName: StateFlow<String> = _newCollectionName.asStateFlow()

    private val _newCollectionDescription = MutableStateFlow("")
    val newCollectionDescription: StateFlow<String> = _newCollectionDescription.asStateFlow()

    fun showCreateDialog() { _showCreateDialog.value = true }
    fun hideCreateDialog() {
        _showCreateDialog.value = false
        _newCollectionName.value = ""
        _newCollectionDescription.value = ""
    }

    fun onNameChange(name: String) { _newCollectionName.value = name }
    fun onDescriptionChange(desc: String) { _newCollectionDescription.value = desc }

    fun createCollection() {
        val name = _newCollectionName.value.trim()
        if (name.isBlank()) return

        viewModelScope.launch {
            collectionRepository.createCollection(
                Collection(
                    name = name,
                    description = _newCollectionDescription.value.trim().ifBlank { null }
                )
            )
            hideCreateDialog()
        }
    }
}
