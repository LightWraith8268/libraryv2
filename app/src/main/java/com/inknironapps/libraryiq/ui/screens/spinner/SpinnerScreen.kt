package com.inknironapps.libraryiq.ui.screens.spinner

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.SubcomposeAsyncImage
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import com.inknironapps.libraryiq.ui.components.displayName
import com.inknironapps.libraryiq.ui.navigation.Screen

private val WheelColors = listOf(
    Color(0xFFE53935), // Red
    Color(0xFF1E88E5), // Blue
    Color(0xFF43A047), // Green
    Color(0xFFFB8C00), // Orange
    Color(0xFF8E24AA), // Purple
    Color(0xFF00ACC1), // Cyan
    Color(0xFFD81B60), // Pink
    Color(0xFF5C6BC0), // Indigo
    Color(0xFF00897B), // Teal
    Color(0xFFFFB300), // Amber
)

private val SpinEasing = CubicBezierEasing(0.0f, 0.4f, 0.15f, 1.0f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpinnerScreen(
    navController: NavController,
    viewModel: SpinnerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val rotation = remember { Animatable(0f) }
    var lastBaseRotation by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(state.spinTrigger) {
        if (state.spinTrigger > 0 && state.isSpinning) {
            val target = lastBaseRotation + state.targetRotation
            rotation.animateTo(
                targetValue = target,
                animationSpec = tween(
                    durationMillis = 4000,
                    easing = SpinEasing
                )
            )
            lastBaseRotation = target % 360f
            viewModel.onSpinComplete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Casino,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Book Spinner")
                    }
                },
                actions = {
                    Text(
                        text = "${state.eligibleCount} books",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    IconButton(onClick = { viewModel.toggleSettings() }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Spinner Settings")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            if (state.wheelBooks.isEmpty()) {
                EmptySpinnerState(
                    state = state,
                    onOpenSettings = { viewModel.toggleSettings() }
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Spacer(modifier = Modifier.weight(0.1f))

                    Box(
                        contentAlignment = Alignment.TopCenter,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    ) {
                        SpinnerWheel(
                            books = state.wheelBooks,
                            rotationDegrees = rotation.value,
                            modifier = Modifier.size(300.dp)
                        )
                        Canvas(modifier = Modifier.size(24.dp)) {
                            val path = Path().apply {
                                moveTo(size.width / 2f, size.height)
                                lineTo(0f, 0f)
                                lineTo(size.width, 0f)
                                close()
                            }
                            drawPath(path, Color(0xFFDD2C00))
                        }
                    }

                    Spacer(modifier = Modifier.weight(0.1f))

                    Button(
                        onClick = { viewModel.spin() },
                        enabled = !state.isSpinning && state.eligibleCount >= 1,
                        modifier = Modifier
                            .height(56.dp)
                            .fillMaxWidth(0.6f),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            Icons.Filled.Casino,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (state.isSpinning) "Spinning..." else "SPIN!",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Spacer(modifier = Modifier.weight(0.15f))
                }
            }
        }

        if (state.showResult && state.selectedBook != null) {
            SpinResultDialog(
                book = state.selectedBook!!,
                autoMarkReading = state.autoMarkReading,
                onAccept = { viewModel.acceptResult() },
                onViewDetails = {
                    val bookId = state.selectedBook!!.id
                    viewModel.acceptResult()
                    navController.navigate(Screen.BookDetail.createRoute(bookId))
                },
                onSpinAgain = {
                    viewModel.dismissResult()
                    viewModel.spin()
                }
            )
        }

        if (state.showSettings) {
            SpinnerSettingsSheet(
                state = state,
                onDismiss = { viewModel.toggleSettings() },
                onToggleStatus = { viewModel.toggleStatusFilter(it) },
                onManualModeChange = { viewModel.setManualMode(it) },
                onAutoMarkReadingChange = { viewModel.setAutoMarkReading(it) },
                onAutoRemoveChange = { viewModel.setAutoRemove(it) },
                onManageBooks = { viewModel.showBookSelector() },
                onResetExcluded = { viewModel.resetExcluded() }
            )
        }

        if (state.showBookSelector) {
            BookSelectorDialog(
                allBooks = state.allBooks,
                selectedIds = state.manualBookIds,
                onToggleBook = { viewModel.toggleBookInManualList(it) },
                onDismiss = { viewModel.hideBookSelector() }
            )
        }
    }
}

@Composable
private fun SpinnerWheel(
    books: List<Book>,
    rotationDegrees: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val segmentAngle = 360f / books.size

        rotate(degrees = rotationDegrees, pivot = center) {
            books.forEachIndexed { index, _ ->
                val startAngle = index * segmentAngle - 90f
                val color = WheelColors[index % WheelColors.size]

                drawArc(
                    color = color,
                    startAngle = startAngle,
                    sweepAngle = segmentAngle,
                    useCenter = true,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2)
                )

                drawArc(
                    color = Color.White.copy(alpha = 0.3f),
                    startAngle = startAngle,
                    sweepAngle = segmentAngle,
                    useCenter = true,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                )
            }

            drawCircle(
                color = Color.White,
                radius = radius * 0.12f,
                center = center
            )
            drawCircle(
                color = Color(0xFF424242),
                radius = radius * 0.1f,
                center = center
            )
        }
    }
}

@Composable
private fun EmptySpinnerState(
    state: SpinnerUiState,
    onOpenSettings: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No books in the spinner",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (state.manualMode) {
                "No books selected. Tap settings to manually pick books for the spinner."
            } else if (state.statusFilters.isEmpty()) {
                "Your library is empty. Add some books first!"
            } else {
                "No books match the selected filters. Change the filters or add more books."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open Settings")
        }
    }
}

@Composable
private fun SpinResultDialog(
    book: Book,
    autoMarkReading: Boolean,
    onAccept: () -> Unit,
    onViewDetails: () -> Unit,
    onSpinAgain: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onAccept,
        title = {
            Text(
                text = "Your next read!",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (book.coverUrl != null) {
                    SubcomposeAsyncImage(
                        model = book.coverUrl,
                        contentDescription = "Cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 100.dp, height = 150.dp)
                            .clip(MaterialTheme.shapes.medium)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "by ${book.author}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                if (book.series != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = buildString {
                            append(book.series)
                            if (book.seriesNumber != null) append(" #${book.seriesNumber}")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        textAlign = TextAlign.Center
                    )
                }

                if (autoMarkReading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Will be marked as Reading",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onViewDetails) {
                Text("View Details")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onAccept) {
                    Text("OK")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onSpinAgain) {
                    Icon(
                        Icons.Filled.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Spin Again")
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpinnerSettingsSheet(
    state: SpinnerUiState,
    onDismiss: () -> Unit,
    onToggleStatus: (ReadingStatus) -> Unit,
    onManualModeChange: (Boolean) -> Unit,
    onAutoMarkReadingChange: (Boolean) -> Unit,
    onAutoRemoveChange: (Boolean) -> Unit,
    onManageBooks: () -> Unit,
    onResetExcluded: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Spinner Settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Status filters
            Text(
                text = "Filter by Reading Status",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (state.statusFilters.isEmpty()) "No filters = all books"
                else "Select one or more statuses",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            ReadingStatus.entries.forEach { status ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !state.manualMode) {
                            onToggleStatus(status)
                        }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = status in state.statusFilters,
                        onCheckedChange = { onToggleStatus(status) },
                        enabled = !state.manualMode
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = status.displayName(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (state.manualMode)
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Manual mode
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Manual Selection",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Hand-pick specific books instead of filtering by status",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.manualMode,
                    onCheckedChange = onManualModeChange
                )
            }

            if (state.manualMode) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onManageBooks,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Select Books (${state.manualBookIds.size} selected)")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Behavior settings
            Text(
                text = "When You Accept a Spin",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Mark as Reading",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Automatically change status to Reading",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.autoMarkReading,
                    onCheckedChange = onAutoMarkReadingChange
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Remove from Spinner",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Don't show the same book again",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.autoRemove,
                    onCheckedChange = onAutoRemoveChange
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            OutlinedButton(
                onClick = onResetExcluded,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset Excluded Books")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun BookSelectorDialog(
    allBooks: List<Book>,
    selectedIds: Set<String>,
    onToggleBook: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredBooks = remember(allBooks, searchQuery) {
        if (searchQuery.isBlank()) allBooks
        else allBooks.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.author.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Select Books for Spinner")
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search books...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "${selectedIds.size} selected",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.height(400.dp)
                ) {
                    items(filteredBooks, key = { it.id }) { book ->
                        val isSelected = book.id in selectedIds
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = book.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = book.author,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingContent = {
                                if (book.coverUrl != null) {
                                    SubcomposeAsyncImage(
                                        model = book.coverUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(width = 36.dp, height = 54.dp)
                                            .clip(MaterialTheme.shapes.extraSmall)
                                    )
                                }
                            },
                            trailingContent = {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { onToggleBook(book.id) }
                                )
                            },
                            modifier = Modifier.clickable { onToggleBook(book.id) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}
