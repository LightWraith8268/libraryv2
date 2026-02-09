package com.inknironapps.libraryiq.ui.screens.spinner

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import com.inknironapps.libraryiq.data.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

enum class SpinnerFilterMode(val displayName: String) {
    UNREAD_ONLY("Unread Only"),
    WANT_TO_READ("Want to Read"),
    UNREAD_AND_WANT("Unread + Want to Read"),
    ALL_BOOKS("All Books"),
    MANUAL("Manual Selection")
}

data class SpinnerUiState(
    val wheelBooks: List<Book> = emptyList(),
    val eligibleCount: Int = 0,
    val isSpinning: Boolean = false,
    val spinTrigger: Int = 0,
    val targetRotation: Float = 0f,
    val selectedBook: Book? = null,
    val showResult: Boolean = false,
    val showSettings: Boolean = false,
    val showBookSelector: Boolean = false,
    val filterMode: SpinnerFilterMode = SpinnerFilterMode.UNREAD_ONLY,
    val autoMarkReading: Boolean = true,
    val autoRemove: Boolean = true,
    val manualBookIds: Set<String> = emptySet(),
    val allBooks: List<Book> = emptyList()
)

private const val PREFS_NAME = "spinner_prefs"
private const val KEY_FILTER_MODE = "filter_mode"
private const val KEY_AUTO_MARK_READING = "auto_mark_reading"
private const val KEY_AUTO_REMOVE = "auto_remove"
private const val KEY_MANUAL_BOOK_IDS = "manual_book_ids"
private const val KEY_EXCLUDED_BOOK_IDS = "excluded_book_ids"
private const val MAX_WHEEL_SEGMENTS = 10

@HiltViewModel
class SpinnerViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    @ApplicationContext context: Context
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(SpinnerUiState())
    val uiState: StateFlow<SpinnerUiState> = _uiState.asStateFlow()

    // Pre-determined winner for current spin
    private var currentWinner: Book? = null

    private val _filterMode = MutableStateFlow(loadFilterMode())
    private val _manualBookIds = MutableStateFlow(loadStringSet(KEY_MANUAL_BOOK_IDS))
    private val _excludedBookIds = MutableStateFlow(loadStringSet(KEY_EXCLUDED_BOOK_IDS))

    init {
        // Load saved settings
        _uiState.update {
            it.copy(
                filterMode = _filterMode.value,
                autoMarkReading = prefs.getBoolean(KEY_AUTO_MARK_READING, true),
                autoRemove = prefs.getBoolean(KEY_AUTO_REMOVE, true),
                manualBookIds = _manualBookIds.value
            )
        }

        // React to filter changes and book data updates
        viewModelScope.launch {
            combine(
                bookRepository.getAllBooks(),
                _filterMode,
                _manualBookIds,
                _excludedBookIds
            ) { allBooks, filter, manualIds, excludedIds ->
                val eligible = filterBooks(allBooks, filter, manualIds, excludedIds)
                val wheel = buildWheel(eligible, null)
                Triple(allBooks, eligible, wheel)
            }.collect { (allBooks, eligible, wheel) ->
                _uiState.update {
                    it.copy(
                        allBooks = allBooks,
                        wheelBooks = wheel,
                        eligibleCount = eligible.size
                    )
                }
            }
        }
    }

    fun spin() {
        val state = _uiState.value
        val allBooks = state.allBooks
        val eligible = filterBooks(
            allBooks, state.filterMode, state.manualBookIds, _excludedBookIds.value
        )
        if (eligible.size < 2) {
            // Need at least 2 books to spin meaningfully
            // If exactly 1, just select it directly
            if (eligible.size == 1) {
                currentWinner = eligible.first()
                _uiState.update {
                    it.copy(
                        selectedBook = eligible.first(),
                        showResult = true,
                        wheelBooks = eligible
                    )
                }
            }
            return
        }

        // Pick a random winner
        val winner = eligible.random()
        currentWinner = winner

        // Build wheel ensuring winner is visible
        val wheel = buildWheel(eligible, winner)
        val winnerIndex = wheel.indexOfFirst { it.id == winner.id }
        val segmentAngle = 360f / wheel.size

        // Calculate rotation: land pointer on winner's segment center
        // Pointer is at top (0 degrees), wheel rotates clockwise
        val winnerCenterAngle = winnerIndex * segmentAngle + segmentAngle / 2f
        val baseRotation = 360f - winnerCenterAngle
        val fullSpins = (5 + Random.nextInt(4)) * 360f // 5-8 full rotations
        val targetRotation = fullSpins + baseRotation

        _uiState.update {
            it.copy(
                wheelBooks = wheel,
                isSpinning = true,
                spinTrigger = it.spinTrigger + 1,
                targetRotation = targetRotation,
                selectedBook = null,
                showResult = false
            )
        }
    }

    fun onSpinComplete() {
        val winner = currentWinner ?: return
        _uiState.update {
            it.copy(
                isSpinning = false,
                selectedBook = winner,
                showResult = true
            )
        }

        val state = _uiState.value

        // Auto-mark as reading
        if (state.autoMarkReading && winner.readingStatus != ReadingStatus.READING) {
            viewModelScope.launch {
                bookRepository.updateBook(
                    winner.copy(
                        readingStatus = ReadingStatus.READING,
                        dateModified = System.currentTimeMillis()
                    )
                )
            }
        }

        // Auto-remove from spinner pool
        if (state.autoRemove) {
            if (state.filterMode == SpinnerFilterMode.MANUAL) {
                // Remove from manual selection
                val updated = state.manualBookIds - winner.id
                _manualBookIds.value = updated
                saveStringSet(KEY_MANUAL_BOOK_IDS, updated)
                _uiState.update { it.copy(manualBookIds = updated) }
            } else if (!state.autoMarkReading) {
                // If auto-mark is off, track excluded books explicitly
                val updated = _excludedBookIds.value + winner.id
                _excludedBookIds.value = updated
                saveStringSet(KEY_EXCLUDED_BOOK_IDS, updated)
            }
            // If autoMarkReading is on with status filter, the book naturally
            // drops out because its status changes to READING
        }
    }

    fun dismissResult() {
        _uiState.update { it.copy(showResult = false, selectedBook = null) }
    }

    fun toggleSettings() {
        _uiState.update { it.copy(showSettings = !it.showSettings) }
    }

    fun setFilterMode(mode: SpinnerFilterMode) {
        _filterMode.value = mode
        prefs.edit().putString(KEY_FILTER_MODE, mode.name).apply()
        _uiState.update { it.copy(filterMode = mode) }
    }

    fun setAutoMarkReading(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_MARK_READING, enabled).apply()
        _uiState.update { it.copy(autoMarkReading = enabled) }
    }

    fun setAutoRemove(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_REMOVE, enabled).apply()
        _uiState.update { it.copy(autoRemove = enabled) }
    }

    fun showBookSelector() {
        _uiState.update { it.copy(showBookSelector = true) }
    }

    fun hideBookSelector() {
        _uiState.update { it.copy(showBookSelector = false) }
    }

    fun toggleBookInManualList(bookId: String) {
        val current = _manualBookIds.value
        val updated = if (bookId in current) current - bookId else current + bookId
        _manualBookIds.value = updated
        saveStringSet(KEY_MANUAL_BOOK_IDS, updated)
        _uiState.update { it.copy(manualBookIds = updated) }
    }

    fun resetExcluded() {
        _excludedBookIds.value = emptySet()
        saveStringSet(KEY_EXCLUDED_BOOK_IDS, emptySet())
    }

    private fun filterBooks(
        allBooks: List<Book>,
        filter: SpinnerFilterMode,
        manualIds: Set<String>,
        excludedIds: Set<String>
    ): List<Book> {
        val base = when (filter) {
            SpinnerFilterMode.UNREAD_ONLY ->
                allBooks.filter { it.readingStatus == ReadingStatus.UNREAD }
            SpinnerFilterMode.WANT_TO_READ ->
                allBooks.filter { it.readingStatus == ReadingStatus.WANT_TO_READ }
            SpinnerFilterMode.UNREAD_AND_WANT ->
                allBooks.filter {
                    it.readingStatus == ReadingStatus.UNREAD ||
                        it.readingStatus == ReadingStatus.WANT_TO_READ
                }
            SpinnerFilterMode.ALL_BOOKS -> allBooks
            SpinnerFilterMode.MANUAL ->
                allBooks.filter { it.id in manualIds }
        }
        return base.filter { it.id !in excludedIds }
    }

    private fun buildWheel(eligible: List<Book>, winner: Book?): List<Book> {
        if (eligible.size <= MAX_WHEEL_SEGMENTS) {
            return eligible.shuffled()
        }
        // For large pools, show a random subset with the winner guaranteed
        val others = eligible.filter { it.id != winner?.id }.shuffled()
            .take(MAX_WHEEL_SEGMENTS - 1)
        val wheel = if (winner != null) others + winner else others.take(MAX_WHEEL_SEGMENTS)
        return wheel.shuffled()
    }

    private fun loadFilterMode(): SpinnerFilterMode {
        val name = prefs.getString(KEY_FILTER_MODE, null)
        return try {
            if (name != null) SpinnerFilterMode.valueOf(name) else SpinnerFilterMode.UNREAD_ONLY
        } catch (_: Exception) {
            SpinnerFilterMode.UNREAD_ONLY
        }
    }

    private fun loadStringSet(key: String): Set<String> =
        prefs.getStringSet(key, emptySet()) ?: emptySet()

    private fun saveStringSet(key: String, set: Set<String>) =
        prefs.edit().putStringSet(key, set).apply()
}
