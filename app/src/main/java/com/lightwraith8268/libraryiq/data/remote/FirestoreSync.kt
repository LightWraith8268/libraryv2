package com.lightwraith8268.libraryiq.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.lightwraith8268.libraryiq.data.local.dao.BookDao
import com.lightwraith8268.libraryiq.data.local.dao.CollectionDao
import com.lightwraith8268.libraryiq.data.local.entity.Book
import com.lightwraith8268.libraryiq.data.local.entity.BookCollectionCrossRef
import com.lightwraith8268.libraryiq.data.local.entity.Collection
import com.lightwraith8268.libraryiq.data.local.entity.ReadingStatus
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles Firebase Auth and Firestore sync using a shared library code.
 *
 * Each user signs in with their own account (email/password).
 * One user creates a shared library (generates a 6-char code).
 * The other user joins with that code.
 * Both devices sync data under: libraries/{libraryCode}/
 */
@Singleton
class FirestoreSync @Inject constructor(
    private val bookDao: BookDao,
    private val collectionDao: CollectionDao,
    @ApplicationContext private val context: Context
) {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("libraryiq_sync", Context.MODE_PRIVATE)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var booksListener: ListenerRegistration? = null
    private var collectionsListener: ListenerRegistration? = null
    private var crossRefsListener: ListenerRegistration? = null

    val isSignedIn: Boolean
        get() = try {
            auth.currentUser != null
        } catch (e: Exception) {
            false
        }

    val userEmail: String?
        get() = try {
            auth.currentUser?.email
        } catch (e: Exception) {
            null
        }

    var libraryCode: String?
        get() = prefs.getString("library_code", null)
        private set(value) = prefs.edit().putString("library_code", value).apply()

    val isSyncEnabled: Boolean
        get() = isSignedIn && !libraryCode.isNullOrBlank()

    private fun getUserId(): String? = try {
        auth.currentUser?.uid
    } catch (e: Exception) {
        null
    }

    // References point to shared library, not individual user
    private fun libraryBooksRef() =
        firestore.collection("libraries").document(libraryCode!!).collection("books")

    private fun libraryCollectionsRef() =
        firestore.collection("libraries").document(libraryCode!!).collection("collections")

    private fun libraryCrossRefsRef() =
        firestore.collection("libraries").document(libraryCode!!).collection("bookCollections")

    // --- Auth ---

    suspend fun signUp(email: String, password: String): Result<Unit> {
        return try {
            auth.createUserWithEmailAndPassword(email, password).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signIn(email: String, password: String): Result<Unit> {
        return try {
            auth.signInWithEmailAndPassword(email, password).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut() {
        stopListening()
        try {
            auth.signOut()
        } catch (_: Exception) {
        }
    }

    fun observeAuthState(): Flow<Boolean> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser != null)
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    // --- Library code management ---

    /**
     * Creates a new shared library and returns the code.
     * The code is a 6-character alphanumeric string.
     */
    suspend fun createLibrary(): Result<String> {
        if (!isSignedIn) return Result.failure(Exception("Not signed in"))

        return try {
            val code = generateLibraryCode()
            val userId = getUserId()!!
            val email = userEmail ?: ""

            // Create the library document
            firestore.collection("libraries").document(code).set(
                mapOf(
                    "createdBy" to userId,
                    "createdAt" to System.currentTimeMillis()
                )
            ).await()

            // Add self as member
            firestore.collection("libraries").document(code)
                .collection("members").document(userId).set(
                    mapOf("email" to email, "joinedAt" to System.currentTimeMillis())
                ).await()

            libraryCode = code
            Result.success(code)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Joins an existing shared library with the given code.
     */
    suspend fun joinLibrary(code: String): Result<Unit> {
        if (!isSignedIn) return Result.failure(Exception("Not signed in"))

        return try {
            val normalizedCode = code.trim().uppercase()

            // Verify the library exists
            val doc = firestore.collection("libraries").document(normalizedCode).get().await()
            if (!doc.exists()) {
                return Result.failure(Exception("Library not found. Check the code and try again."))
            }

            // Add self as member
            val userId = getUserId()!!
            val email = userEmail ?: ""
            firestore.collection("libraries").document(normalizedCode)
                .collection("members").document(userId).set(
                    mapOf("email" to email, "joinedAt" to System.currentTimeMillis())
                ).await()

            libraryCode = normalizedCode
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun leaveLibrary() {
        stopListening()
        libraryCode = null
    }

    private fun generateLibraryCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Avoids ambiguous chars (0/O, 1/I)
        return (1..6).map { chars.random() }.joinToString("")
    }

    // --- Sync operations ---

    suspend fun pushBook(book: Book) {
        if (!isSyncEnabled) return
        try {
            libraryBooksRef().document(book.id).set(bookToMap(book), SetOptions.merge()).await()
        } catch (_: Exception) {
        }
    }

    suspend fun pushCollection(collection: Collection) {
        if (!isSyncEnabled) return
        try {
            libraryCollectionsRef().document(collection.id)
                .set(collectionToMap(collection), SetOptions.merge()).await()
        } catch (_: Exception) {
        }
    }

    suspend fun pushBookCollectionRef(crossRef: BookCollectionCrossRef) {
        if (!isSyncEnabled) return
        try {
            val docId = "${crossRef.bookId}_${crossRef.collectionId}"
            libraryCrossRefsRef().document(docId).set(
                mapOf("bookId" to crossRef.bookId, "collectionId" to crossRef.collectionId)
            ).await()
        } catch (_: Exception) {
        }
    }

    suspend fun deleteBook(bookId: String) {
        if (!isSyncEnabled) return
        try {
            libraryBooksRef().document(bookId).delete().await()
        } catch (_: Exception) {
        }
    }

    suspend fun deleteCollection(collectionId: String) {
        if (!isSyncEnabled) return
        try {
            libraryCollectionsRef().document(collectionId).delete().await()
        } catch (_: Exception) {
        }
    }

    suspend fun deleteBookCollectionRef(bookId: String, collectionId: String) {
        if (!isSyncEnabled) return
        try {
            val docId = "${bookId}_${collectionId}"
            libraryCrossRefsRef().document(docId).delete().await()
        } catch (_: Exception) {
        }
    }

    suspend fun pushAllData(
        books: List<Book>,
        collections: List<Collection>,
        crossRefs: List<BookCollectionCrossRef>
    ) {
        if (!isSyncEnabled) return
        try {
            val batch = firestore.batch()
            books.forEach { book ->
                batch.set(
                    libraryBooksRef().document(book.id),
                    bookToMap(book),
                    SetOptions.merge()
                )
            }
            collections.forEach { collection ->
                batch.set(
                    libraryCollectionsRef().document(collection.id),
                    collectionToMap(collection),
                    SetOptions.merge()
                )
            }
            crossRefs.forEach { ref ->
                val docId = "${ref.bookId}_${ref.collectionId}"
                batch.set(
                    libraryCrossRefsRef().document(docId),
                    mapOf("bookId" to ref.bookId, "collectionId" to ref.collectionId)
                )
            }
            batch.commit().await()
        } catch (_: Exception) {
        }
    }

    // --- Real-time listeners ---

    fun startListening() {
        if (!isSyncEnabled) return

        booksListener = libraryBooksRef().addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            val books = snapshot.documents.mapNotNull { doc ->
                mapToBook(doc.id, doc.data ?: return@mapNotNull null)
            }
            scope.launch { books.forEach { bookDao.insertBook(it) } }
        }

        collectionsListener = libraryCollectionsRef().addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            val collections = snapshot.documents.mapNotNull { doc ->
                mapToCollection(doc.id, doc.data ?: return@mapNotNull null)
            }
            scope.launch { collections.forEach { collectionDao.insertCollection(it) } }
        }

        crossRefsListener = libraryCrossRefsRef().addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            val refs = snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                BookCollectionCrossRef(
                    bookId = data["bookId"] as? String ?: return@mapNotNull null,
                    collectionId = data["collectionId"] as? String ?: return@mapNotNull null
                )
            }
            scope.launch { refs.forEach { collectionDao.addBookToCollection(it) } }
        }
    }

    fun stopListening() {
        booksListener?.remove()
        collectionsListener?.remove()
        crossRefsListener?.remove()
        booksListener = null
        collectionsListener = null
        crossRefsListener = null
    }

    // --- Data mapping ---

    private fun bookToMap(book: Book): Map<String, Any?> = mapOf(
        "title" to book.title,
        "author" to book.author,
        "isbn" to book.isbn,
        "isbn10" to book.isbn10,
        "description" to book.description,
        "coverUrl" to book.coverUrl,
        "pageCount" to book.pageCount,
        "publisher" to book.publisher,
        "publishedDate" to book.publishedDate,
        "readingStatus" to book.readingStatus.name,
        "rating" to book.rating,
        "notes" to book.notes,
        "series" to book.series,
        "seriesNumber" to book.seriesNumber,
        "genre" to book.genre,
        "language" to book.language,
        "format" to book.format,
        "subjects" to book.subjects,
        "dateAdded" to book.dateAdded,
        "dateModified" to book.dateModified
    )

    private fun collectionToMap(collection: Collection): Map<String, Any?> = mapOf(
        "name" to collection.name,
        "description" to collection.description,
        "dateCreated" to collection.dateCreated,
        "dateModified" to collection.dateModified
    )

    private fun mapToBook(id: String, data: Map<String, Any?>): Book? {
        return try {
            Book(
                id = id,
                title = data["title"] as? String ?: return null,
                author = data["author"] as? String ?: "",
                isbn = data["isbn"] as? String,
                description = data["description"] as? String,
                coverUrl = data["coverUrl"] as? String,
                pageCount = (data["pageCount"] as? Number)?.toInt(),
                publisher = data["publisher"] as? String,
                publishedDate = data["publishedDate"] as? String,
                readingStatus = try {
                    ReadingStatus.valueOf(data["readingStatus"] as? String ?: "UNREAD")
                } catch (_: Exception) {
                    ReadingStatus.UNREAD
                },
                rating = (data["rating"] as? Number)?.toFloat(),
                notes = data["notes"] as? String,
                series = data["series"] as? String,
                seriesNumber = data["seriesNumber"] as? String,
                genre = data["genre"] as? String,
                language = data["language"] as? String,
                format = data["format"] as? String,
                subjects = data["subjects"] as? String,
                dateAdded = (data["dateAdded"] as? Number)?.toLong()
                    ?: System.currentTimeMillis(),
                dateModified = (data["dateModified"] as? Number)?.toLong()
                    ?: System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun mapToCollection(id: String, data: Map<String, Any?>): Collection? {
        return try {
            Collection(
                id = id,
                name = data["name"] as? String ?: return null,
                description = data["description"] as? String,
                dateCreated = (data["dateCreated"] as? Number)?.toLong()
                    ?: System.currentTimeMillis(),
                dateModified = (data["dateModified"] as? Number)?.toLong()
                    ?: System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
    }
}
