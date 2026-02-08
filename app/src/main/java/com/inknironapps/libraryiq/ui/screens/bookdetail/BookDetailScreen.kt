package com.inknironapps.libraryiq.ui.screens.bookdetail

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import com.inknironapps.libraryiq.ui.components.ReadingStatusChip

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
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // --- Reading Status ---
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
                        MetadataRow("Author", book.author)

                        if (!book.series.isNullOrBlank()) {
                            MetadataRow("Series", buildString {
                                append(book.series)
                                if (!book.seriesNumber.isNullOrBlank()) {
                                    append(" #${book.seriesNumber}")
                                }
                            })
                        }

                        book.publisher?.let { MetadataRow("Publisher", it) }
                        book.publishedDate?.let { MetadataRow("Published", it) }
                        book.pageCount?.let { MetadataRow("Pages", it.toString()) }

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

                        MetadataRow(
                            "Added",
                            java.text.SimpleDateFormat(
                                "MMM d, yyyy",
                                java.util.Locale.getDefault()
                            ).format(java.util.Date(book.dateAdded))
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
