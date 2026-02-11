package com.inknironapps.libraryiq.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.inknironapps.libraryiq.data.update.WhatsNewInfo
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inknironapps.libraryiq.ui.screens.addbook.AddBookScreen
import com.inknironapps.libraryiq.ui.screens.auth.AuthScreen
import com.inknironapps.libraryiq.ui.screens.bookdetail.BookDetailScreen
import com.inknironapps.libraryiq.ui.screens.collections.AddBookToCollectionScreen
import com.inknironapps.libraryiq.ui.screens.collections.CollectionDetailScreen
import com.inknironapps.libraryiq.ui.screens.collections.CollectionsScreen
import com.inknironapps.libraryiq.ui.screens.library.LibraryScreen
import com.inknironapps.libraryiq.ui.screens.scanner.ScannerScreen
import com.inknironapps.libraryiq.ui.screens.settings.SettingsScreen
import com.inknironapps.libraryiq.ui.screens.spinner.SpinnerScreen
import com.inknironapps.libraryiq.ui.screens.stats.StatsScreen

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector,
    val baseRoute: String = screen.route
)

/**
 * Safe bottom-tab navigation that won't crash on back stack edge cases.
 * Falls back to a plain navigate if popUpTo fails.
 */
private fun NavController.navigateSafe(route: String) {
    try {
        navigate(route) {
            popUpTo(graph.findStartDestination().id) {
                inclusive = false
            }
            launchSingleTop = true
        }
    } catch (_: Exception) {
        // Fallback: navigate without popUpTo if back stack is in a bad state
        try {
            navigate(route) {
                launchSingleTop = true
            }
        } catch (_: Exception) {
            // Last resort: ignore — stay on current screen rather than crash
        }
    }
}

val bottomNavItems = listOf(
    BottomNavItem(Screen.Library, "Library", Icons.Default.LibraryBooks, Screen.Library.BASE_ROUTE),
    BottomNavItem(Screen.Spinner, "Spinner", Icons.Default.Casino),
    BottomNavItem(Screen.Collections, "Collections", Icons.Default.CollectionsBookmark),
    BottomNavItem(Screen.Stats, "Stats", Icons.Default.BarChart),
    BottomNavItem(Screen.Settings, "Settings", Icons.Default.Settings)
)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // What's New dialog
    val whatsNewViewModel: WhatsNewViewModel = hiltViewModel()
    val whatsNew by whatsNewViewModel.whatsNew.collectAsStateWithLifecycle()
    whatsNew?.let { info ->
        WhatsNewDialog(info = info, onDismiss = whatsNewViewModel::dismissWhatsNew)
    }

    val showBottomBar = currentDestination?.route in listOf(
        Screen.Library.route,
        Screen.Spinner.route,
        Screen.Collections.route,
        Screen.Stats.route,
        Screen.Settings.route
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                                navController.navigateSafe(item.baseRoute)
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Library.BASE_ROUTE,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(
                route = Screen.Library.route,
                arguments = listOf(
                    navArgument("filterAuthor") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("filterSeries") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) {
                LibraryScreen(navController = navController)
            }

            composable(Screen.Spinner.route) {
                SpinnerScreen(navController = navController)
            }

            composable(Screen.Collections.route) {
                CollectionsScreen(navController = navController)
            }

            composable(Screen.Stats.route) {
                StatsScreen()
            }

            composable(Screen.Settings.route) {
                SettingsScreen(navController = navController)
            }

            composable(
                route = Screen.AddBook.route,
                arguments = listOf(navArgument("isbn") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                })
            ) {
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

@Composable
private fun WhatsNewDialog(info: WhatsNewInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "What's New in v${info.versionName}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Parse markdown-style release notes into readable text
                val lines = info.releaseNotes.lines()
                for (line in lines) {
                    val trimmed = line.trim()
                    when {
                        trimmed.startsWith("### ") -> {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = trimmed.removePrefix("### "),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        trimmed.startsWith("- ") -> {
                            Text(
                                text = trimmed,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        trimmed.isNotBlank() -> {
                            Text(
                                text = trimmed,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}
