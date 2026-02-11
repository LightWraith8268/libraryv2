package com.inknironapps.libraryiq.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
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
import androidx.compose.ui.text.style.TextAlign
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
fun BookGridCard(
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Cover image or placeholder
            if (!book.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = book.coverUrl,
                    contentDescription = "Cover of ${book.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(MaterialTheme.shapes.small)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Title
            Text(
                text = book.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            // Author
            Text(
                text = book.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Status and rating row
            val statusColor = when (book.readingStatus) {
                ReadingStatus.READING -> StatusReading
                ReadingStatus.READ -> StatusRead
                ReadingStatus.WANT_TO_READ -> StatusWantToRead
                ReadingStatus.WANT_TO_BUY -> StatusWantToBuy
                ReadingStatus.UNREAD -> StatusUnread
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = book.readingStatus.displayName(),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )
                if (book.rating != null && book.rating > 0f) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "Rating",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(12.dp)
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
