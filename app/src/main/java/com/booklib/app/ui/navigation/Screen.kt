package com.booklib.app.ui.navigation

sealed class Screen(val route: String) {
    data object Library : Screen("library")
    data object Collections : Screen("collections")
    data object Settings : Screen("settings")
    data object AddBook : Screen("add_book")
    data object Scanner : Screen("scanner")
    data object Auth : Screen("auth")

    data object BookDetail : Screen("book_detail/{bookId}") {
        fun createRoute(bookId: String) = "book_detail/$bookId"
    }

    data object CollectionDetail : Screen("collection_detail/{collectionId}") {
        fun createRoute(collectionId: String) = "collection_detail/$collectionId"
    }

    data object AddBookToCollection : Screen("add_book_to_collection/{collectionId}") {
        fun createRoute(collectionId: String) = "add_book_to_collection/$collectionId"
    }
}
