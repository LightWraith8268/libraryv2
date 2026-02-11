package com.inknironapps.libraryiq.ui.screens.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import com.inknironapps.libraryiq.data.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class SortOption(val label: String) {
    TITLE_ASC("Title A\u2192Z"),
    TITLE_DESC("Title Z\u2192A"),
    AUTHOR_ASC("Author A\u2192Z"),
    AUTHOR_DESC("Author Z\u2192A"),
    DATE_ADDED_DESC("Newest First"),
    DATE_ADDED_ASC("Oldest First"),
    RATING_DESC("Highest Rated")
}

enum class GroupOption(val label: String) {
    NONE("No Grouping"),
    STATUS("Reading Status"),
    AUTHOR("Author"),
    SERIES("Series"),
    GENRE("Genre"),
    RATING("Rating")
}

data class GroupedBooks(
    val groupName: String,
    val books: List<Book>
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val libraryPreferences: LibraryPreferences,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedFilter = MutableStateFlow<ReadingStatus?>(null)
    val selectedFilter: StateFlow<ReadingStatus?> = _selectedFilter.asStateFlow()

    private val _sortOption = MutableStateFlow(libraryPreferences.defaultSort)
    val sortOption: StateFlow<SortOption> = _sortOption.asStateFlow()

    private val _groupOption = MutableStateFlow(libraryPreferences.defaultGroup)
    val groupOption: StateFlow<GroupOption> = _groupOption.asStateFlow()

    private val _layout = MutableStateFlow(libraryPreferences.layout)
    val layout: StateFlow<LibraryLayout> = _layout.asStateFlow()

    private val _gridColumns = MutableStateFlow(libraryPreferences.gridColumns)
    val gridColumns: StateFlow<Int> = _gridColumns.asStateFlow()

    private val _showCovers = MutableStateFlow(libraryPreferences.showCovers)
    val showCovers: StateFlow<Boolean> = _showCovers.asStateFlow()

    private val _compactList = MutableStateFlow(libraryPreferences.compactList)
    val compactList: StateFlow<Boolean> = _compactList.asStateFlow()

    private val _authorFilter = MutableStateFlow(savedStateHandle.get<String>("filterAuthor"))
    val authorFilter: StateFlow<String?> = _authorFilter.asStateFlow()

    private val _seriesFilter = MutableStateFlow(savedStateHandle.get<String>("filterSeries"))
    val seriesFilter: StateFlow<String?> = _seriesFilter.asStateFlow()

    val books: StateFlow<List<Book>> = combine(
        bookRepository.getAllBooks(),
        _searchQuery,
        _selectedFilter,
        _authorFilter,
        _seriesFilter
    ) { allBooks, query, statusFilter, author, series ->
        var result = allBooks
        if (author != null) {
            result = result.filter { it.author.equals(author, ignoreCase = true) }
        }
        if (series != null) {
            result = result.filter { it.series?.equals(series, ignoreCase = true) == true }
        }
        if (statusFilter != null) {
            result = result.filter { it.readingStatus == statusFilter }
        }
        if (query.isNotBlank()) {
            val q = query.lowercase()
            result = result.filter {
                it.title.lowercase().contains(q) ||
                    it.author.lowercase().contains(q) ||
                    it.isbn?.contains(q) == true ||
                    it.isbn10?.contains(q) == true
            }
        }
        result
    }.combine(_sortOption) { bookList, sort ->
        sortBooks(bookList, sort)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val groupedBooks: StateFlow<List<GroupedBooks>> = combine(
        books,
        _groupOption
    ) { sortedBooks, group ->
        groupBooks(sortedBooks, group)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val bookCount: StateFlow<Int> = bookRepository.getBookCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onFilterSelected(status: ReadingStatus?) {
        _selectedFilter.value = if (_selectedFilter.value == status) null else status
    }

    fun onSortOptionSelected(option: SortOption) {
        _sortOption.value = option
        libraryPreferences.updateDefaultSort(option)
    }

    fun onGroupOptionSelected(option: GroupOption) {
        _groupOption.value = option
        libraryPreferences.updateDefaultGroup(option)
    }

    fun setLayout(layout: LibraryLayout) {
        _layout.value = layout
        libraryPreferences.updateLayout(layout)
    }

    fun setGridColumns(columns: Int) {
        _gridColumns.value = columns
        libraryPreferences.updateGridColumns(columns)
    }

    fun setShowCovers(show: Boolean) {
        _showCovers.value = show
        libraryPreferences.updateShowCovers(show)
    }

    fun setCompactList(compact: Boolean) {
        _compactList.value = compact
        libraryPreferences.updateCompactList(compact)
    }

    fun clearAuthorFilter() {
        _authorFilter.value = null
    }

    fun clearSeriesFilter() {
        _seriesFilter.value = null
    }

    private fun sortBooks(books: List<Book>, sort: SortOption): List<Book> = when (sort) {
        SortOption.TITLE_ASC -> books.sortedBy { it.title.lowercase() }
        SortOption.TITLE_DESC -> books.sortedByDescending { it.title.lowercase() }
        SortOption.AUTHOR_ASC -> books.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.author.lastNameFirst() })
        SortOption.AUTHOR_DESC -> books.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.author.lastNameFirst() })
        SortOption.DATE_ADDED_DESC -> books.sortedByDescending { it.dateAdded }
        SortOption.DATE_ADDED_ASC -> books.sortedBy { it.dateAdded }
        SortOption.RATING_DESC -> books.sortedByDescending { it.rating ?: 0f }
    }

    private fun groupBooks(books: List<Book>, group: GroupOption): List<GroupedBooks> = when (group) {
        GroupOption.NONE -> listOf(GroupedBooks("All Books", books))
        GroupOption.STATUS -> {
            val order = ReadingStatus.entries.toList()
            books.groupBy { it.readingStatus }
                .toSortedMap(compareBy { order.indexOf(it) })
                .map { (status, list) -> GroupedBooks(status.displayName(), list) }
        }
        GroupOption.AUTHOR -> {
            books.groupBy { it.author }
                .entries
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key.lastNameFirst() })
                .map { (author, list) -> GroupedBooks(author, list) }
        }
        GroupOption.SERIES -> {
            val withSeries = books.filter { it.series != null }
                .groupBy { it.series!! }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .map { (series, list) ->
                    GroupedBooks(series, list.sortedBy {
                        it.seriesNumber?.toFloatOrNull() ?: Float.MAX_VALUE
                    })
                }
            val standalone = books.filter { it.series == null }
            if (standalone.isNotEmpty()) {
                withSeries + GroupedBooks("Standalone", standalone)
            } else {
                withSeries
            }
        }
        GroupOption.GENRE -> {
            val withGenre = books.filter { !it.genre.isNullOrBlank() }
                .groupBy { it.genre!! }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .map { (genre, list) -> GroupedBooks(genre, list) }
            val noGenre = books.filter { it.genre.isNullOrBlank() }
            if (noGenre.isNotEmpty()) {
                withGenre + GroupedBooks("Uncategorized", noGenre)
            } else {
                withGenre
            }
        }
        GroupOption.RATING -> {
            val rated = books.filter { it.rating != null && it.rating > 0f }
                .groupBy { it.rating!!.toInt() }
                .toSortedMap(compareByDescending { it })
                .map { (stars, list) ->
                    val label = if (stars == 1) "1 Star" else "$stars Stars"
                    GroupedBooks(label, list)
                }
            val unrated = books.filter { it.rating == null || it.rating <= 0f }
            if (unrated.isNotEmpty()) {
                rated + GroupedBooks("Unrated", unrated)
            } else {
                rated
            }
        }
    }

    private fun ReadingStatus.displayName(): String = when (this) {
        ReadingStatus.UNREAD -> "Unread"
        ReadingStatus.READING -> "Reading"
        ReadingStatus.READ -> "Read"
        ReadingStatus.WANT_TO_READ -> "Want to Read"
        ReadingStatus.WANT_TO_BUY -> "Want to Buy"
    }
}

/** Rearranges "First Middle Last" → "Last, First Middle" for sorting by last name. */
private fun String.lastNameFirst(): String {
    val parts = trim().split("\\s+".toRegex())
    return if (parts.size > 1) {
        "${parts.last()}, ${parts.dropLast(1).joinToString(" ")}"
    } else {
        this
    }
}
