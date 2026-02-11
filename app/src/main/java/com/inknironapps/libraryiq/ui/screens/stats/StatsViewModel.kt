package com.inknironapps.libraryiq.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inknironapps.libraryiq.data.local.dao.AuthorCount
import com.inknironapps.libraryiq.data.local.dao.BookDao
import com.inknironapps.libraryiq.data.local.dao.GenreCount
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class StatsUiState(
    val totalBooks: Int = 0,
    val readCount: Int = 0,
    val readingCount: Int = 0,
    val unreadCount: Int = 0,
    val wantToReadCount: Int = 0,
    val wantToBuyCount: Int = 0,
    val averageRating: Float? = null,
    val totalPagesRead: Int = 0,
    val topAuthors: List<AuthorCount> = emptyList(),
    val topGenres: List<GenreCount> = emptyList(),
    val booksAddedThisYear: Int = 0,
    val booksFinishedThisYear: Int = 0,
    val isLoading: Boolean = true
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val bookDao: BookDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    init {
        loadStats()
    }

    fun loadStats() {
        viewModelScope.launch {
            val startOfYear = Calendar.getInstance().apply {
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val read = bookDao.getCountByStatus(ReadingStatus.READ)
            val reading = bookDao.getCountByStatus(ReadingStatus.READING)
            val unread = bookDao.getCountByStatus(ReadingStatus.UNREAD)
            val wantToRead = bookDao.getCountByStatus(ReadingStatus.WANT_TO_READ)
            val wantToBuy = bookDao.getCountByStatus(ReadingStatus.WANT_TO_BUY)

            _uiState.value = StatsUiState(
                totalBooks = read + reading + unread + wantToRead + wantToBuy,
                readCount = read,
                readingCount = reading,
                unreadCount = unread,
                wantToReadCount = wantToRead,
                wantToBuyCount = wantToBuy,
                averageRating = bookDao.getAverageRating(),
                totalPagesRead = bookDao.getTotalPagesRead() ?: 0,
                topAuthors = bookDao.getTopAuthors(),
                topGenres = bookDao.getTopGenres(),
                booksAddedThisYear = bookDao.getBooksAddedSince(startOfYear),
                booksFinishedThisYear = bookDao.getBooksFinishedSince(startOfYear),
                isLoading = false
            )
        }
    }
}
