package com.inknironapps.libraryiq.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.inknironapps.libraryiq.data.local.entity.BookCollectionCrossRef
import com.inknironapps.libraryiq.data.local.entity.Collection
import com.inknironapps.libraryiq.data.local.entity.CollectionWithBooks
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {

    @Query("SELECT * FROM collections ORDER BY dateCreated DESC")
    fun getAllCollections(): Flow<List<Collection>>

    @Query("SELECT * FROM collections WHERE id = :collectionId")
    fun getCollectionById(collectionId: String): Flow<Collection?>

    @Transaction
    @Query("SELECT * FROM collections WHERE id = :collectionId")
    fun getCollectionWithBooks(collectionId: String): Flow<CollectionWithBooks?>

    @Transaction
    @Query("SELECT * FROM collections ORDER BY dateCreated DESC")
    fun getAllCollectionsWithBooks(): Flow<List<CollectionWithBooks>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: Collection)

    @Update
    suspend fun updateCollection(collection: Collection)

    @Delete
    suspend fun deleteCollection(collection: Collection)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBookToCollection(crossRef: BookCollectionCrossRef)

    @Delete
    suspend fun removeBookFromCollection(crossRef: BookCollectionCrossRef)

    @Query("DELETE FROM book_collection_cross_ref WHERE bookId = :bookId AND collectionId = :collectionId")
    suspend fun removeBookFromCollectionById(bookId: String, collectionId: String)

    @Query("SELECT collectionId FROM book_collection_cross_ref WHERE bookId = :bookId")
    fun getCollectionIdsForBook(bookId: String): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM collections WHERE name = :name")
    suspend fun countByName(name: String): Int

    @Query("SELECT * FROM collections WHERE name = :name LIMIT 1")
    suspend fun getCollectionByName(name: String): Collection?

    @Query("SELECT COUNT(*) > 0 FROM book_collection_cross_ref WHERE bookId = :bookId AND collectionId = :collectionId")
    suspend fun isBookInCollection(bookId: String, collectionId: String): Boolean
}
