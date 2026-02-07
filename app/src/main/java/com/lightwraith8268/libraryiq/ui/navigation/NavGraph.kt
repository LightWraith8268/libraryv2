package com.lightwraith8268.libraryiq.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lightwraith8268.libraryiq.ui.screens.addbook.AddBookScreen
import com.lightwraith8268.libraryiq.ui.screens.auth.AuthScreen
import com.lightwraith8268.libraryiq.ui.screens.bookdetail.BookDetailScreen
import com.lightwraith8268.libraryiq.ui.screens.collections.AddBookToCollectionScreen
import com.lightwraith8268.libraryiq.ui.screens.collections.CollectionDetailScreen
import com.lightwraith8268.libraryiq.ui.screens.collections.CollectionsScreen
import com.lightwraith8268.libraryiq.ui.screens.library.LibraryScreen
import com.lightwraith8268.libraryiq.ui.screens.scanner.ScannerScreen
import com.lightwraith8268.libraryiq.ui.screens.settings.SettingsScreen

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Library, "Library", Icons.Default.LibraryBooks),
    BottomNavItem(Screen.Collections, "Collections", Icons.Default.CollectionsBookmark),
    BottomNavItem(Screen.Settings, "Settings", Icons.Default.Settings)
)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = currentDestination?.route in listOf(
        Screen.Library.route,
        Screen.Collections.route,
        Screen.Settings.route
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentDestination?.hierarchy?.any {
                                it.route == item.screen.route
                            } == true,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Library.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Library.route) {
                LibraryScreen(navController = navController)
            }

            composable(Screen.Collections.route) {
                CollectionsScreen(navController = navController)
            }

            composable(Screen.Settings.route) {
                SettingsScreen(navController = navController)
            }

            composable(Screen.AddBook.route) {
                AddBookScreen(navController = navController)
            }

            composable(Screen.Scanner.route) {
                ScannerScreen(navController = navController)
            }

            composable(Screen.Auth.route) {
                AuthScreen(navController = navController)
            }

            composable(
                route = Screen.BookDetail.route,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType })
            ) { backStackEntry ->
                val bookId = backStackEntry.arguments?.getString("bookId") ?: return@composable
                BookDetailScreen(bookId = bookId, navController = navController)
            }

            composable(
                route = Screen.CollectionDetail.route,
                arguments = listOf(navArgument("collectionId") { type = NavType.StringType })
            ) { backStackEntry ->
                val collectionId =
                    backStackEntry.arguments?.getString("collectionId") ?: return@composable
                CollectionDetailScreen(
                    collectionId = collectionId,
                    navController = navController
                )
            }

            composable(
                route = Screen.AddBookToCollection.route,
                arguments = listOf(navArgument("collectionId") { type = NavType.StringType })
            ) { backStackEntry ->
                val collectionId =
                    backStackEntry.arguments?.getString("collectionId") ?: return@composable
                AddBookToCollectionScreen(
                    collectionId = collectionId,
                    navController = navController
                )
            }
        }
    }
}
