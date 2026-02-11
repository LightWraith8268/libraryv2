package com.inknironapps.libraryiq.data.repository

import com.inknironapps.libraryiq.data.local.dao.CollectionDao
import com.inknironapps.libraryiq.data.local.entity.BookCollectionCrossRef
import com.inknironapps.libraryiq.data.local.entity.Collection
import com.inknironapps.libraryiq.data.local.entity.CollectionWithBooks
import com.inknironapps.libraryiq.data.remote.FirestoreSync
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollectionRepository @Inject constructor(
    private val collectionDao: CollectionDao,
    private val firestoreSync: FirestoreSync
) {
    fun getAllCollections(): Flow<List<Collection>> = collectionDao.getAllCollections()

    fun getCollectionWithBooks(collectionId: String): Flow<CollectionWithBooks?> =
        collectionDao.getCollectionWithBooks(collectionId)

    fun getAllCollectionsWithBooks(): Flow<List<CollectionWithBooks>> =
        collectionDao.getAllCollectionsWithBooks()

    fun getCollectionIdsForBook(bookId: String): Flow<List<String>> =
        collectionDao.getCollectionIdsForBook(bookId)

    suspend fun createCollection(collection: Collection) {
        collectionDao.insertCollection(collection)
        firestoreSync.pushCollection(collection)
    }

    suspend fun updateCollection(collection: Collection) {
        val updated = collection.copy(dateModified = System.currentTimeMillis())
        collectionDao.updateCollection(updated)
        firestoreSync.pushCollection(updated)
    }

    suspend fun deleteCollection(collection: Collection) {
        collectionDao.deleteCollection(collection)
        firestoreSync.deleteCollection(collection.id)
    }

    suspend fun addBookToCollection(bookId: String, collectionId: String) {
        val crossRef = BookCollectionCrossRef(bookId, collectionId)
        collectionDao.addBookToCollection(crossRef)
        firestoreSync.pushBookCollectionRef(crossRef)
    }

    suspend fun removeBookFromCollection(bookId: String, collectionId: String) {
        collectionDao.removeBookFromCollectionById(bookId, collectionId)
        firestoreSync.deleteBookCollectionRef(bookId, collectionId)
    }

    suspend fun ensureDefaultCollections() {
        // Rename old "To Buy" to "Want to Buy" if it exists
        val oldToBuy = collectionDao.getCollectionByName("To Buy")
        if (oldToBuy != null) {
            updateCollection(oldToBuy.copy(name = "Want to Buy"))
        }

        val defaults = listOf(
            "Favorites" to "Your favorite books",
            "Want to Buy" to "Books you want to purchase"
        )
        for ((name, description) in defaults) {
            if (collectionDao.countByName(name) == 0) {
                createCollection(Collection(name = name, description = description))
            }
        }
    }

    suspend fun getWantToBuyCollectionId(): String? {
        return collectionDao.getCollectionByName("Want to Buy")?.id
    }
}
