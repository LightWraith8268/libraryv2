package com.inknironapps.libraryiq.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import com.inknironapps.libraryiq.ui.theme.StatusRead
import com.inknironapps.libraryiq.ui.theme.StatusReading
import com.inknironapps.libraryiq.ui.theme.StatusUnread
import com.inknironapps.libraryiq.ui.theme.StatusWantToBuy
import com.inknironapps.libraryiq.ui.theme.StatusWantToRead

@Composable
fun BookCard(
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showCover: Boolean = true,
    compact: Boolean = false
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compact) 8.dp else 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Cover image
            if (showCover) {
                AsyncImage(
                    model = book.coverUrl,
                    contentDescription = "Cover of ${book.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(
                            width = if (compact) 40.dp else 60.dp,
                            height = if (compact) 60.dp else 90.dp
                        )
                        .clip(MaterialTheme.shapes.small)
                )

                Spacer(modifier = Modifier.width(if (compact) 8.dp else 12.dp))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)
            ) {
                Text(
                    text = book.title,
                    style = if (compact) MaterialTheme.typography.bodyMedium
                    else MaterialTheme.typography.titleMedium,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!compact && book.series != null) {
                    val seriesText = buildString {
                        append(book.series)
                        if (book.seriesNumber != null) {
                            append(" #${book.seriesNumber}")
                        }
                    }
                    Text(
                        text = seriesText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (!compact) {
                    Spacer(modifier = Modifier.height(4.dp))
                }

                val statusColor = when (book.readingStatus) {
                    ReadingStatus.READING -> StatusReading
                    ReadingStatus.READ -> StatusRead
                    ReadingStatus.WANT_TO_READ -> StatusWantToRead
                    ReadingStatus.WANT_TO_BUY -> StatusWantToBuy
                    ReadingStatus.UNREAD -> StatusUnread
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = book.readingStatus.displayName(),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )

                    if (book.rating != null && book.rating > 0f) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "Rating",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "${"%.0f".format(book.rating)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
