package com.booklib.app.data.local.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class CollectionWithBooks(
    @Embedded val collection: Collection,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            BookCollectionCrossRef::class,
            parentColumn = "collectionId",
            entityColumn = "bookId"
        )
    )
    val books: List<Book>
)

data class BookWithCollections(
    @Embedded val book: Book,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            BookCollectionCrossRef::class,
            parentColumn = "bookId",
            entityColumn = "collectionId"
        )
    )
    val collections: List<Collection>
)
