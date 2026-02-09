package com.inknironapps.libraryiq.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.inknironapps.libraryiq.data.local.dao.BookDao
import com.inknironapps.libraryiq.data.local.dao.CollectionDao
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.local.entity.BookCollectionCrossRef
import com.inknironapps.libraryiq.data.local.entity.Collection
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
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
 * Each user signs in with their Google account.
 * One user creates a shared library (generates a 6-char code).
 * The other user joins with that code.
 * Both devices sync data under: libraries/{libraryCode}/
 *
 * Book metadata (title, author, isbn, notes, etc.) is shared across all members.
 * Per-user data (readingStatus, rating, reading dates, current page) is stored
 * separately under: libraries/{libraryCode}/userStatus/{userId}/books/{bookId}
 */
@Singleton
class FirestoreSync @Inject constructor(
    private val bookDao: BookDao,
    private val collectionDao: CollectionDao,
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MAX_MEMBERS = 10
    }

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("libraryiq_sync", Context.MODE_PRIVATE)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var booksListener: ListenerRegistration? = null
    private var collectionsListener: ListenerRegistration? = null
    private var crossRefsListener: ListenerRegistration? = null
    private var userStatusListener: ListenerRegistration? = null

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

    // Per-user reading status stored under userStatus/{userId}/books/{bookId}
    private fun userStatusBooksRef(userId: String) =
        firestore.collection("libraries").document(libraryCode!!)
            .collection("userStatus").document(userId).collection("books")

    // --- Auth ---

    suspend fun signInWithGoogle(idToken: String): Result<Unit> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(credential).await()
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

            // Create the library document with members array for security rules
            firestore.collection("libraries").document(code).set(
                mapOf(
                    "createdBy" to userId,
                    "createdAt" to System.currentTimeMillis(),
                    "members" to listOf(userId)
                )
            ).await()

            libraryCode = code
            Result.success(code)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Joins an existing shared library with the given code.
     * Enforces a maximum of MAX_MEMBERS per library.
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

            // Check member cap
            val members = doc.get("members") as? List<*> ?: emptyList<String>()
            val userId = getUserId()!!
            if (!members.contains(userId) && members.size >= MAX_MEMBERS) {
                return Result.failure(Exception("This library has reached its maximum of $MAX_MEMBERS members."))
            }

            // Add self to members array
            firestore.collection("libraries").document(normalizedCode).update(
                "members", com.google.firebase.firestore.FieldValue.arrayUnion(userId)
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

    /**
     * After sign-in, checks if the user is already a member of a library
     * in Firestore and restores the library code if found.
     * This handles the case where the user reinstalls the app or
     * clears data but still has an active library.
     */
    suspend fun restoreLibraryCode(): String? {
        if (!isSignedIn) return null
        if (!libraryCode.isNullOrBlank()) return libraryCode // Already have one

        val userId = getUserId() ?: return null
        return try {
            val querySnapshot = firestore.collection("libraries")
                .whereArrayContains("members", userId)
                .limit(1)
                .get()
                .await()

            val doc = querySnapshot.documents.firstOrNull()
            if (doc != null) {
                val code = doc.id
                libraryCode = code
                code
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun generateLibraryCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Avoids ambiguous chars (0/O, 1/I)
        return (1..6).map { chars.random() }.joinToString("")
    }

    // --- Sync operations ---

    suspend fun pushBook(book: Book) {
        if (!isSyncEnabled) return
        try {
            // Push shared metadata (no per-user fields)
            libraryBooksRef().document(book.id)
                .set(bookToSharedMap(book), SetOptions.merge()).await()
            // Push per-user status
            val userId = getUserId() ?: return
            userStatusBooksRef(userId).document(book.id)
                .set(bookToUserStatusMap(book), SetOptions.merge()).await()
        } catch (_: Exception) {
        }
    }

    /** Push only per-user fields (reading status, rating, dates, current page). */
    suspend fun pushUserStatus(book: Book) {
        if (!isSyncEnabled) return
        val userId = getUserId() ?: return
        try {
            userStatusBooksRef(userId).document(book.id)
                .set(bookToUserStatusMap(book), SetOptions.merge()).await()
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
            // Also delete per-user status for current user
            val userId = getUserId() ?: return
            userStatusBooksRef(userId).document(bookId).delete().await()
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
        val userId = getUserId() ?: return
        try {
            val batch = firestore.batch()
            books.forEach { book ->
                batch.set(
                    libraryBooksRef().document(book.id),
                    bookToSharedMap(book),
                    SetOptions.merge()
                )
                batch.set(
                    userStatusBooksRef(userId).document(book.id),
                    bookToUserStatusMap(book),
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
        val userId = getUserId() ?: return

        // Listen for shared book metadata - preserve local per-user fields
        booksListener = libraryBooksRef().addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            scope.launch {
                snapshot.documents.forEach { doc ->
                    val data = doc.data ?: return@forEach
                    val sharedBook = mapToSharedBook(doc.id, data) ?: return@forEach
                    // Merge with existing local book to preserve per-user fields
                    val existing = bookDao.getBookByIdDirect(doc.id)
                    // Preserve per-user fields from local (reading status + rating)
                    val merged = if (existing != null) {
                        sharedBook.copy(
                            readingStatus = existing.readingStatus,
                            rating = existing.rating,
                            dateStarted = existing.dateStarted,
                            dateFinished = existing.dateFinished,
                            currentPage = existing.currentPage
                        )
                    } else {
                        sharedBook
                    }
                    bookDao.insertBook(merged)
                }
            }
        }

        // Listen for current user's per-user reading status
        userStatusListener = userStatusBooksRef(userId).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null) return@addSnapshotListener
            scope.launch {
                snapshot.documents.forEach { doc ->
                    val data = doc.data ?: return@forEach
                    val bookId = doc.id
                    val existing = bookDao.getBookByIdDirect(bookId) ?: return@forEach
                    val updated = existing.copy(
                        readingStatus = try {
                            ReadingStatus.valueOf(data["readingStatus"] as? String ?: existing.readingStatus.name)
                        } catch (_: Exception) {
                            existing.readingStatus
                        },
                        rating = (data["rating"] as? Number)?.toFloat() ?: existing.rating,
                        dateStarted = (data["dateStarted"] as? Number)?.toLong() ?: existing.dateStarted,
                        dateFinished = (data["dateFinished"] as? Number)?.toLong() ?: existing.dateFinished,
                        currentPage = (data["currentPage"] as? Number)?.toInt() ?: existing.currentPage
                    )
                    bookDao.updateBook(updated)
                }
            }
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
        userStatusListener?.remove()
        booksListener = null
        collectionsListener = null
        crossRefsListener = null
        userStatusListener = null
    }

    // --- Data mapping ---

    /** Shared book metadata (visible to all library members). */
    private fun bookToSharedMap(book: Book): Map<String, Any?> = mapOf(
        "title" to book.title,
        "author" to book.author,
        "isbn" to book.isbn,
        "isbn10" to book.isbn10,
        "description" to book.description,
        "coverUrl" to book.coverUrl,
        "pageCount" to book.pageCount,
        "publisher" to book.publisher,
        "publishedDate" to book.publishedDate,
        "notes" to book.notes,
        "series" to book.series,
        "seriesNumber" to book.seriesNumber,
        "genre" to book.genre,
        "language" to book.language,
        "format" to book.format,
        "subjects" to book.subjects,
        "tags" to book.tags,
        "dateAdded" to book.dateAdded,
        "dateModified" to book.dateModified,
        // Extended metadata
        "asin" to book.asin,
        "goodreadsId" to book.goodreadsId,
        "openLibraryId" to book.openLibraryId,
        "hardcoverId" to book.hardcoverId,
        "edition" to book.edition,
        "originalTitle" to book.originalTitle,
        "originalLanguage" to book.originalLanguage
    )

    /** Per-user fields (private to each library member). */
    private fun bookToUserStatusMap(book: Book): Map<String, Any?> = mapOf(
        "readingStatus" to book.readingStatus.name,
        "rating" to book.rating,
        "dateStarted" to book.dateStarted,
        "dateFinished" to book.dateFinished,
        "currentPage" to book.currentPage
    )

    private fun collectionToMap(collection: Collection): Map<String, Any?> = mapOf(
        "name" to collection.name,
        "description" to collection.description,
        "dateCreated" to collection.dateCreated,
        "dateModified" to collection.dateModified
    )

    /** Maps shared Firestore data to a Book with default per-user fields. */
    private fun mapToSharedBook(id: String, data: Map<String, Any?>): Book? {
        return try {
            // Handle backward compat: old data may have readingStatus in shared doc
            val legacyStatus = try {
                ReadingStatus.valueOf(data["readingStatus"] as? String ?: "UNREAD")
            } catch (_: Exception) {
                ReadingStatus.UNREAD
            }

            Book(
                id = id,
                title = data["title"] as? String ?: return null,
                author = data["author"] as? String ?: "",
                isbn = data["isbn"] as? String,
                isbn10 = data["isbn10"] as? String,
                description = data["description"] as? String,
                coverUrl = data["coverUrl"] as? String,
                pageCount = (data["pageCount"] as? Number)?.toInt(),
                publisher = data["publisher"] as? String,
                publishedDate = data["publishedDate"] as? String,
                readingStatus = legacyStatus,
                rating = (data["rating"] as? Number)?.toFloat(), // backward compat: old data may have rating here
                notes = data["notes"] as? String,
                series = data["series"] as? String,
                seriesNumber = data["seriesNumber"] as? String,
                genre = data["genre"] as? String,
                language = data["language"] as? String,
                format = data["format"] as? String,
                subjects = data["subjects"] as? String,
                tags = data["tags"] as? String,
                dateAdded = (data["dateAdded"] as? Number)?.toLong()
                    ?: System.currentTimeMillis(),
                dateModified = (data["dateModified"] as? Number)?.toLong()
                    ?: System.currentTimeMillis(),
                asin = data["asin"] as? String,
                goodreadsId = data["goodreadsId"] as? String,
                openLibraryId = data["openLibraryId"] as? String,
                hardcoverId = data["hardcoverId"] as? String,
                edition = data["edition"] as? String,
                originalTitle = data["originalTitle"] as? String,
                originalLanguage = data["originalLanguage"] as? String
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
