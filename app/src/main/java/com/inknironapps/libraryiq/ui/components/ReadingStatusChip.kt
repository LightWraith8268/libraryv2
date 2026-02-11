package com.inknironapps.libraryiq.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import com.inknironapps.libraryiq.ui.theme.StatusRead
import com.inknironapps.libraryiq.ui.theme.StatusReading
import com.inknironapps.libraryiq.ui.theme.StatusUnread
import com.inknironapps.libraryiq.ui.theme.StatusWantToBuy
import com.inknironapps.libraryiq.ui.theme.StatusWantToRead

@Composable
fun ReadingStatusChip(
    status: ReadingStatus,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (label, icon, color) = when (status) {
        ReadingStatus.UNREAD -> Triple("Unread", Icons.Default.Book, StatusUnread)
        ReadingStatus.READING -> Triple("Reading", Icons.Default.AutoStories, StatusReading)
        ReadingStatus.READ -> Triple("Read", Icons.Default.CheckCircle, StatusRead)
        ReadingStatus.WANT_TO_READ -> Triple("Want to Read", Icons.Default.BookmarkAdd, StatusWantToRead)
        ReadingStatus.WANT_TO_BUY -> Triple("Want to Buy", Icons.Default.ShoppingCart, StatusWantToBuy)
    }

    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(18.dp)
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color.copy(alpha = 0.2f),
            selectedLabelColor = color,
            selectedLeadingIconColor = color
        ),
        modifier = modifier
    )
}

fun ReadingStatus.displayName(): String = when (this) {
    ReadingStatus.UNREAD -> "Unread"
    ReadingStatus.READING -> "Reading"
    ReadingStatus.READ -> "Read"
    ReadingStatus.WANT_TO_READ -> "Want to Read"
    ReadingStatus.WANT_TO_BUY -> "Want to Buy"
}
