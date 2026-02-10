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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

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
    val statusFilters: Set<ReadingStatus> = setOf(ReadingStatus.UNREAD),
    val manualMode: Boolean = false,
    val autoMarkReading: Boolean = true,
    val autoRemove: Boolean = true,
    val manualBookIds: Set<String> = emptySet(),
    val allBooks: List<Book> = emptyList()
)

private const val PREFS_NAME = "spinner_prefs"
private const val KEY_STATUS_FILTERS = "status_filters"
private const val KEY_MANUAL_MODE = "manual_mode"
private const val KEY_AUTO_MARK_READING = "auto_mark_reading"
private const val KEY_AUTO_REMOVE = "auto_remove"
private const val KEY_MANUAL_BOOK_IDS = "manual_book_ids"
private const val KEY_EXCLUDED_BOOK_IDS = "excluded_book_ids"
private const val MAX_WHEEL_SEGMENTS = 36

@HiltViewModel
class SpinnerViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    @ApplicationContext context: Context
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(SpinnerUiState())
    val uiState: StateFlow<SpinnerUiState> = _uiState.asStateFlow()

    private var currentWinner: Book? = null

    private val _statusFilters = MutableStateFlow(loadStatusFilters())
    private val _manualMode = MutableStateFlow(prefs.getBoolean(KEY_MANUAL_MODE, false))
    private val _manualBookIds = MutableStateFlow(loadStringSet(KEY_MANUAL_BOOK_IDS))
    private val _excludedBookIds = MutableStateFlow(loadStringSet(KEY_EXCLUDED_BOOK_IDS))

    init {
        _uiState.update {
            it.copy(
                statusFilters = _statusFilters.value,
                manualMode = _manualMode.value,
                autoMarkReading = prefs.getBoolean(KEY_AUTO_MARK_READING, true),
                autoRemove = prefs.getBoolean(KEY_AUTO_REMOVE, true),
                manualBookIds = _manualBookIds.value
            )
        }

        viewModelScope.launch {
            combine(
                bookRepository.getAllBooks(),
                _statusFilters,
                _manualMode,
                _manualBookIds,
                _excludedBookIds
            ) { allBooks, statuses, manual, manualIds, excludedIds ->
                val eligible = filterBooks(allBooks, statuses, manual, manualIds, excludedIds)
                val wheel = buildWheel(eligible)
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
        val eligible = filterBooks(
            state.allBooks, state.statusFilters, state.manualMode,
            state.manualBookIds, _excludedBookIds.value
        )
        if (eligible.size < 2) {
            if (eligible.size == 1) {
                currentWinner = eligible.first()
                _uiState.update {
                    it.copy(
                        selectedBook = eligible.first(),
                        showResult = true
                    )
                }
            }
            return
        }

        val winner = eligible.random()
        currentWinner = winner

        // Wheel is purely decorative — just spin a random amount
        val fullSpins = (5 + Random.nextInt(4)) * 360f
        val randomOffset = Random.nextFloat() * 360f
        val targetRotation = fullSpins + randomOffset

        _uiState.update {
            it.copy(
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
    }

    /** User accepted the result (Close or View Details). Apply auto-mark and auto-remove. */
    fun acceptResult() {
        val winner = currentWinner ?: return
        val state = _uiState.value

        if (state.autoMarkReading && winner.readingStatus != ReadingStatus.READING) {
            viewModelScope.launch {
                bookRepository.updateReadingStatus(
                    winner.copy(
                        readingStatus = ReadingStatus.READING,
                        dateStarted = winner.dateStarted ?: System.currentTimeMillis(),
                        dateModified = System.currentTimeMillis()
                    )
                )
            }
        }

        if (state.autoRemove) {
            if (state.manualMode) {
                val updated = state.manualBookIds - winner.id
                _manualBookIds.value = updated
                saveStringSet(KEY_MANUAL_BOOK_IDS, updated)
                _uiState.update { it.copy(manualBookIds = updated) }
            } else if (!state.autoMarkReading) {
                val updated = _excludedBookIds.value + winner.id
                _excludedBookIds.value = updated
                saveStringSet(KEY_EXCLUDED_BOOK_IDS, updated)
            }
        }

        _uiState.update { it.copy(showResult = false, selectedBook = null) }
    }

    /** User rejected the result (Spin Again). No auto-mark or auto-remove applied. */
    fun dismissResult() {
        _uiState.update { it.copy(showResult = false, selectedBook = null) }
    }

    fun toggleSettings() {
        _uiState.update { it.copy(showSettings = !it.showSettings) }
    }

    fun toggleStatusFilter(status: ReadingStatus) {
        val current = _statusFilters.value
        val updated = if (status in current) current - status else current + status
        _statusFilters.value = updated
        saveStatusFilters(updated)
        _uiState.update { it.copy(statusFilters = updated) }
    }

    fun setManualMode(enabled: Boolean) {
        _manualMode.value = enabled
        prefs.edit().putBoolean(KEY_MANUAL_MODE, enabled).apply()
        _uiState.update { it.copy(manualMode = enabled) }
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
        statusFilters: Set<ReadingStatus>,
        manualMode: Boolean,
        manualIds: Set<String>,
        excludedIds: Set<String>
    ): List<Book> {
        val base = when {
            manualMode -> allBooks.filter { it.id in manualIds }
            statusFilters.isEmpty() -> allBooks // no filters = all books
            else -> allBooks.filter { it.readingStatus in statusFilters }
        }
        return base.filter { it.id !in excludedIds }
    }

    private fun buildWheel(eligible: List<Book>): List<Book> {
        return eligible.shuffled().take(MAX_WHEEL_SEGMENTS)
    }

    private fun loadStatusFilters(): Set<ReadingStatus> {
        val names = prefs.getStringSet(KEY_STATUS_FILTERS, null)
            ?: return setOf(ReadingStatus.UNREAD) // default
        return names.mapNotNull {
            try { ReadingStatus.valueOf(it) } catch (_: Exception) { null }
        }.toSet()
    }

    private fun saveStatusFilters(filters: Set<ReadingStatus>) {
        prefs.edit().putStringSet(KEY_STATUS_FILTERS, filters.map { it.name }.toSet()).apply()
    }

    private fun loadStringSet(key: String): Set<String> =
        prefs.getStringSet(key, emptySet()) ?: emptySet()

    private fun saveStringSet(key: String, set: Set<String>) =
        prefs.edit().putStringSet(key, set).apply()
}
