package com.inknironapps.libraryiq.ui.navigation

sealed class Screen(val route: String) {
    data object Library : Screen("library?filterAuthor={filterAuthor}&filterSeries={filterSeries}") {
        const val BASE_ROUTE = "library"
        fun createRoute(filterAuthor: String? = null, filterSeries: String? = null): String {
            val params = mutableListOf<String>()
            filterAuthor?.let { params.add("filterAuthor=${android.net.Uri.encode(it)}") }
            filterSeries?.let { params.add("filterSeries=${android.net.Uri.encode(it)}") }
            return if (params.isEmpty()) BASE_ROUTE else "$BASE_ROUTE?${params.joinToString("&")}"
        }
    }
    data object Spinner : Screen("spinner")
    data object Collections : Screen("collections")
    data object Settings : Screen("settings")
    data object AddBook : Screen("add_book?isbn={isbn}") {
        fun createRoute(isbn: String? = null) =
            if (isbn != null) "add_book?isbn=$isbn" else "add_book"
    }
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
