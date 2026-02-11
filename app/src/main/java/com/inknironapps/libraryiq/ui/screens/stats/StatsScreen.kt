package com.inknironapps.libraryiq.ui.screens.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inknironapps.libraryiq.ui.theme.StatusRead
import com.inknironapps.libraryiq.ui.theme.StatusReading
import com.inknironapps.libraryiq.ui.theme.StatusUnread
import com.inknironapps.libraryiq.ui.theme.StatusWantToBuy
import com.inknironapps.libraryiq.ui.theme.StatusWantToRead
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    viewModel: StatsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Stats") })

        if (uiState.isLoading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // --- Overview ---
                Text("Library Overview", style = MaterialTheme.typography.titleLarge)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        label = "Total Books",
                        value = uiState.totalBooks.toString(),
                        icon = { Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(24.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "Pages Read",
                        value = formatNumber(uiState.totalPagesRead),
                        icon = { Icon(Icons.Default.AutoStories, contentDescription = null, modifier = Modifier.size(24.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (uiState.averageRating != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatCard(
                            label = "Avg Rating",
                            value = "%.1f".format(uiState.averageRating),
                            icon = { Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) },
                            modifier = Modifier.weight(1f)
                        )
                        val year = Calendar.getInstance().get(Calendar.YEAR)
                        StatCard(
                            label = "Read in $year",
                            value = uiState.booksFinishedThisYear.toString(),
                            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusRead, modifier = Modifier.size(24.dp)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // --- Reading Status Breakdown ---
                HorizontalDivider()
                Text("Reading Status", style = MaterialTheme.typography.titleLarge)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StatusBar("Read", uiState.readCount, uiState.totalBooks, StatusRead)
                        StatusBar("Reading", uiState.readingCount, uiState.totalBooks, StatusReading)
                        StatusBar("Unread", uiState.unreadCount, uiState.totalBooks, StatusUnread)
                        StatusBar("Want to Read", uiState.wantToReadCount, uiState.totalBooks, StatusWantToRead)
                        StatusBar("Want to Buy", uiState.wantToBuyCount, uiState.totalBooks, StatusWantToBuy)
                    }
                }

                // --- Top Authors ---
                if (uiState.topAuthors.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Top Authors", style = MaterialTheme.typography.titleLarge)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.topAuthors.forEach { (author, count) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(author, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text(
                                        "$count book${if (count != 1) "s" else ""}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // --- Top Genres ---
                if (uiState.topGenres.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Top Genres", style = MaterialTheme.typography.titleLarge)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.topGenres.forEach { (genre, count) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(genre, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text(
                                        "$count book${if (count != 1) "s" else ""}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // --- This Year ---
                HorizontalDivider()
                val year = Calendar.getInstance().get(Calendar.YEAR)
                Text("$year Activity", style = MaterialTheme.typography.titleLarge)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        label = "Added",
                        value = uiState.booksAddedThisYear.toString(),
                        icon = { Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(24.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        label = "Finished",
                        value = uiState.booksFinishedThisYear.toString(),
                        icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusRead, modifier = Modifier.size(24.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            icon()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatusBar(
    label: String,
    count: Int,
    total: Int,
    color: androidx.compose.ui.graphics.Color
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "$count",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        if (total > 0) {
            LinearProgressIndicator(
                progress = { count.toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
                color = color,
                trackColor = color.copy(alpha = 0.12f)
            )
        }
    }
}

private fun formatNumber(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 1_000 -> "%.1fK".format(n / 1_000f)
    else -> n.toString()
}
