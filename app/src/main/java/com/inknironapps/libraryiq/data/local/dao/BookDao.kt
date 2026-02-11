package com.inknironapps.libraryiq.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.local.entity.BookWithCollections
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY dateAdded DESC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE readingStatus = :status ORDER BY dateAdded DESC")
    fun getBooksByStatus(status: ReadingStatus): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    fun getBookById(bookId: String): Flow<Book?>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBookByIdDirect(bookId: String): Book?

    @Query("SELECT * FROM books WHERE isbn = :isbn LIMIT 1")
    suspend fun getBookByIsbn(isbn: String): Book?

    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%'")
    fun searchBooks(query: String): Flow<List<Book>>

    @Transaction
    @Query("SELECT * FROM books WHERE id = :bookId")
    fun getBookWithCollections(bookId: String): Flow<BookWithCollections?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooks(books: List<Book>)

    @Update
    suspend fun updateBook(book: Book)

    @Delete
    suspend fun deleteBook(book: Book)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBookById(bookId: String)

    @Query("SELECT COUNT(*) FROM books")
    fun getBookCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM books WHERE readingStatus = :status")
    suspend fun getCountByStatus(status: ReadingStatus): Int

    @Query("SELECT AVG(rating) FROM books WHERE rating IS NOT NULL AND rating > 0")
    suspend fun getAverageRating(): Float?

    @Query("SELECT SUM(pageCount) FROM books WHERE readingStatus = 'READ' AND pageCount IS NOT NULL")
    suspend fun getTotalPagesRead(): Int?

    @Query("SELECT author, COUNT(*) as cnt FROM books GROUP BY author ORDER BY cnt DESC LIMIT 5")
    suspend fun getTopAuthors(): List<AuthorCount>

    @Query("SELECT genre, COUNT(*) as cnt FROM books WHERE genre IS NOT NULL AND genre != '' GROUP BY genre ORDER BY cnt DESC LIMIT 5")
    suspend fun getTopGenres(): List<GenreCount>

    @Query("SELECT COUNT(*) FROM books WHERE dateAdded >= :since")
    suspend fun getBooksAddedSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM books WHERE readingStatus = 'READ' AND dateFinished >= :since")
    suspend fun getBooksFinishedSince(since: Long): Int
}

data class AuthorCount(val author: String, val cnt: Int)
data class GenreCount(val genre: String, val cnt: Int)
