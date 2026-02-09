package com.inknironapps.libraryiq.ui.screens.bookdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import com.inknironapps.libraryiq.ui.components.ReadingStatusChip
import com.inknironapps.libraryiq.ui.navigation.Screen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailScreen(
    bookId: String,
    navController: NavController,
    viewModel: BookDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCollectionMenu by remember { mutableStateOf(false) }
    var showPageDialog by remember { mutableStateOf(false) }

    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    LaunchedEffect(uiState.isDeleted) {
        if (uiState.isDeleted) {
            navController.popBackStack()
        }
    }

    val book = uiState.book

    Column(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
        TopAppBar(
            title = { Text(if (uiState.isEditing) "Edit Book" else "Book Details") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (book != null) {
                    if (uiState.isEditing) {
                        IconButton(onClick = viewModel::saveEdits) {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                    } else {
                        if (uiState.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp).padding(2.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(onClick = viewModel::refreshMetadata) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh metadata")
                            }
                        }
                        IconButton(onClick = viewModel::toggleEditing) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        )

        if (book == null) {
            Text(
                text = "Loading...",
                modifier = Modifier.padding(16.dp)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // --- Cover and basic info ---
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (!book.coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = book.coverUrl,
                            contentDescription = "Cover",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 120.dp, height = 180.dp)
                                .clip(MaterialTheme.shapes.medium)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        if (uiState.isEditing) {
                            OutlinedTextField(
                                value = uiState.editTitle,
                                onValueChange = viewModel::onEditTitleChange,
                                label = { Text("Title") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = uiState.editAuthor,
                                onValueChange = viewModel::onEditAuthorChange,
                                label = { Text("Author") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = book.title,
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = book.author,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    navController.navigate(
                                        Screen.Library.createRoute(filterAuthor = book.author)
                                    )
                                }
                            )

                            // Series info
                            if (!book.series.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = buildString {
                                        append(book.series)
                                        if (!book.seriesNumber.isNullOrBlank()) {
                                            append(" #${book.seriesNumber}")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.clickable {
                                        navController.navigate(
                                            Screen.Library.createRoute(filterSeries = book.series)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                // --- Star Rating ---
                HorizontalDivider()
                Text(
                    text = "Rating",
                    style = MaterialTheme.typography.titleMedium
                )
                StarRatingBar(
                    rating = book.rating ?: 0f,
                    onRatingChanged = { viewModel.updateRating(if (it == 0f) null else it) }
                )

                // --- Reading Status ---
                HorizontalDivider()
                Text(
                    text = "Reading Status",
                    style = MaterialTheme.typography.titleMedium
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ReadingStatus.entries.forEach { status ->
                        ReadingStatusChip(
                            status = status,
                            selected = book.readingStatus == status,
                            onClick = { viewModel.updateReadingStatus(status) }
                        )
                    }
                }

                // --- Reading Progress ---
                if (book.readingStatus == ReadingStatus.READING && book.pageCount != null && book.pageCount > 0) {
                    val currentPage = book.currentPage ?: 0
                    val progress = (currentPage.toFloat() / book.pageCount).coerceIn(0f, 1f)
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Page $currentPage of ${book.pageCount}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(onClick = { showPageDialog = true }) {
                                Text("Update")
                            }
                        }
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${(progress * 100).toInt()}% complete",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (book.readingStatus == ReadingStatus.READING) {
                    TextButton(onClick = { showPageDialog = true }) {
                        Text("Set Current Page")
                    }
                }

                // --- Reading Dates ---
                val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
                if (book.dateStarted != null || book.dateFinished != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            book.dateStarted?.let {
                                MetadataRow("Started", dateFormat.format(Date(it)))
                            }
                            book.dateFinished?.let {
                                MetadataRow("Finished", dateFormat.format(Date(it)))
                            }
                        }
                    }
                }

                // --- Description ---
                if (!book.description.isNullOrBlank()) {
                    HorizontalDivider()
                    Text(
                        text = "Description",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = book.description,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // --- Full Metadata ---
                HorizontalDivider()
                Text(
                    text = "Book Details",
                    style = MaterialTheme.typography.titleMedium
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ClickableMetadataRow(
                            label = "Author",
                            value = book.author,
                            onClick = {
                                navController.navigate(
                                    Screen.Library.createRoute(filterAuthor = book.author)
                                )
                            }
                        )

                        if (!book.series.isNullOrBlank()) {
                            ClickableMetadataRow(
                                label = "Series",
                                value = buildString {
                                    append(book.series)
                                    if (!book.seriesNumber.isNullOrBlank()) {
                                        append(" #${book.seriesNumber}")
                                    }
                                },
                                onClick = {
                                    navController.navigate(
                                        Screen.Library.createRoute(filterSeries = book.series)
                                    )
                                }
                            )
                        }

                        book.publisher?.let { MetadataRow("Publisher", it) }
                        book.publishedDate?.let { MetadataRow("Published", it) }
                        book.pageCount?.let { MetadataRow("Pages", it.toString()) }
                        book.edition?.let { MetadataRow("Edition", it) }

                        if (!book.isbn.isNullOrBlank()) {
                            MetadataRow("ISBN-13", book.isbn)
                        }
                        if (!book.isbn10.isNullOrBlank()) {
                            MetadataRow("ISBN-10", book.isbn10)
                        }

                        book.language?.let { MetadataRow("Language", it) }
                        book.format?.let { MetadataRow("Format", it) }
                        book.genre?.let { MetadataRow("Genre", it) }

                        if (!book.subjects.isNullOrBlank()) {
                            MetadataRow("Subjects", book.subjects)
                        }

                        // Extended identifiers
                        book.asin?.let { MetadataRow("ASIN", it) }
                        book.goodreadsId?.let { MetadataRow("Goodreads", it) }
                        book.openLibraryId?.let { MetadataRow("OpenLibrary", it) }
                        book.hardcoverId?.let { MetadataRow("Hardcover", it) }

                        // Original title/language
                        book.originalTitle?.let { MetadataRow("Orig. Title", it) }
                        book.originalLanguage?.let { MetadataRow("Orig. Lang.", it) }

                        MetadataRow(
                            "Added",
                            dateFormat.format(Date(book.dateAdded))
                        )
                    }
                }

                OutlinedButton(
                    onClick = viewModel::refreshMetadata,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isRefreshing && !book.isbn.isNullOrBlank()
                ) {
                    if (uiState.isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Refreshing...")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Refresh Metadata")
                    }
                }

                uiState.refreshMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // --- Tags ---
                HorizontalDivider()
                Text(
                    text = "Tags",
                    style = MaterialTheme.typography.titleMedium
                )
                if (uiState.isEditing) {
                    OutlinedTextField(
                        value = uiState.editTags,
                        onValueChange = viewModel::onEditTagsChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("fiction, sci-fi, favorites...") },
                        singleLine = true
                    )
                } else {
                    if (!book.tags.isNullOrBlank()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            book.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
                                .forEach { tag ->
                                    AssistChip(
                                        onClick = { },
                                        label = { Text(tag) }
                                    )
                                }
                        }
                    } else {
                        Text(
                            text = "No tags. Tap edit to add some.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // --- Notes ---
                HorizontalDivider()
                Text(
                    text = "Notes",
                    style = MaterialTheme.typography.titleMedium
                )
                if (uiState.isEditing) {
                    OutlinedTextField(
                        value = uiState.editNotes,
                        onValueChange = viewModel::onEditNotesChange,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 5,
                        placeholder = { Text("Add notes about this book...") }
                    )
                } else {
                    Text(
                        text = book.notes ?: "No notes yet. Tap edit to add some.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (book.notes == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                }

                // --- Collections ---
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Collections",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Column {
                        TextButton(onClick = { showCollectionMenu = true }) {
                            Text("Manage")
                        }
                        DropdownMenu(
                            expanded = showCollectionMenu,
                            onDismissRequest = { showCollectionMenu = false }
                        ) {
                            val bookCollectionIds = uiState.collections.map { it.id }.toSet()
                            uiState.allCollections.forEach { collection ->
                                val isInCollection = collection.id in bookCollectionIds
                                DropdownMenuItem(
                                    text = {
                                        Row {
                                            if (isInCollection) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                            }
                                            Text(collection.name)
                                        }
                                    },
                                    onClick = {
                                        if (isInCollection) {
                                            viewModel.removeFromCollection(collection.id)
                                        } else {
                                            viewModel.addToCollection(collection.id)
                                        }
                                    }
                                )
                            }
                            if (uiState.allCollections.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No collections yet") },
                                    onClick = { showCollectionMenu = false }
                                )
                            }
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    uiState.collections.forEach { collection ->
                        AssistChip(
                            onClick = { },
                            label = { Text(collection.name) }
                        )
                    }
                    if (uiState.collections.isEmpty()) {
                        Text(
                            text = "Not in any collections",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Book") },
            text = { Text("Are you sure you want to remove \"${book?.title}\" from your library?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteBook()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Current page dialog
    if (showPageDialog) {
        var pageText by remember { mutableStateOf(book?.currentPage?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { showPageDialog = false },
            title = { Text("Update Current Page") },
            text = {
                OutlinedTextField(
                    value = pageText,
                    onValueChange = { pageText = it.filter { c -> c.isDigit() } },
                    label = { Text("Page number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val page = pageText.toIntOrNull()
                    viewModel.updateCurrentPage(page)
                    showPageDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPageDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StarRatingBar(
    rating: Float,
    onRatingChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 1..5) {
            val icon = when {
                rating >= i -> Icons.Default.Star
                rating >= i - 0.5f -> Icons.Default.StarHalf
                else -> Icons.Default.StarBorder
            }
            Icon(
                imageVector = icon,
                contentDescription = "$i star",
                tint = if (rating >= i - 0.5f) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier
                    .size(36.dp)
                    .clickable { onRatingChanged(if (rating == i.toFloat()) 0f else i.toFloat()) }
            )
        }
        if (rating > 0f) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${"%.1f".format(rating)}/5",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(90.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ClickableMetadataRow(label: String, value: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(90.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
        )
    }
}
