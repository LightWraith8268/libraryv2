package com.inknironapps.libraryiq.ui.screens.settings

import android.app.Activity
import com.inknironapps.libraryiq.BuildConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncDisabled
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import androidx.compose.material.icons.filled.SystemUpdate
import com.inknironapps.libraryiq.util.DebugLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val connectivityManager = remember {
        context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    }
    val isOnline = remember {
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
        capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            val account = task.getResult(ApiException::class.java)
            account.idToken?.let { viewModel.handleGoogleSignInResult(it) }
                ?: viewModel.showSignInError("No ID token received from Google")
        } catch (e: ApiException) {
            DebugLog.e("GoogleSignIn", "Sign-in failed: status=${e.statusCode}, message=${e.message}")
            viewModel.showSignInError("Google Sign-In failed (code ${e.statusCode}): ${e.message}")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Subscription Section ---
            // Show to signed-in users without Pro, but NOT to joined members (they sync free via creator's Pro)
            if (uiState.isSignedIn && !uiState.hasProAccess && (!uiState.isSyncEnabled || uiState.isLibraryCreator)) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Unlock Multi-Device Sync",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Subscribe to sync your library across multiple devices and invite others to join.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                (context as? Activity)?.let { viewModel.launchSubscription(it) }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (uiState.subscriptionPrice != null)
                                    "Subscribe for ${uiState.subscriptionPrice}/month"
                                else "Subscribe - \$0.99/month"
                            )
                        }
                        TextButton(onClick = viewModel::restorePurchases) {
                            Text("Restore Purchases")
                        }
                    }
                }
            }

            // --- Sync Section ---
            Text(
                text = "Sync & Account",
                style = MaterialTheme.typography.titleLarge
            )

            // Sync status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.isSyncEnabled && !isOnline)
                        MaterialTheme.colorScheme.errorContainer
                    else if (uiState.isSyncEnabled)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        if (uiState.isSyncEnabled && !isOnline) Icons.Default.CloudOff
                        else if (uiState.isSyncEnabled) Icons.Default.Sync
                        else Icons.Default.SyncDisabled,
                        contentDescription = null,
                        tint = if (uiState.isSyncEnabled && !isOnline) MaterialTheme.colorScheme.error
                        else if (uiState.isSyncEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (uiState.isSyncEnabled && !isOnline) "Offline"
                            else if (uiState.isSyncEnabled) "Sync Active"
                            else "Sync Disabled",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (uiState.isSyncEnabled && !isOnline)
                                "Changes will sync when reconnected"
                            else if (uiState.isSyncEnabled)
                                "Library syncs across devices in real-time"
                            else if (!uiState.isSignedIn)
                                "Sign in to create or join a library"
                            else "Create or join a library to get started",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Pro status badge
            if (uiState.isSignedIn && uiState.hasProAccess) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (uiState.isAdmin) "Admin Access"
                            else "Pro Subscriber",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            if (!uiState.isSignedIn) {
                // --- Google Sign In ---
                Text(
                    text = "Sign in with your Google account to create or join a library.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = {
                        (context as? Activity)?.let { activity ->
                            googleSignInLauncher.launch(viewModel.getGoogleSignInIntent(activity))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Sign in with Google")
                    }
                }
            } else {
                // --- Signed In ---
                Text(
                    text = "Signed in as ${uiState.userEmail}",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (uiState.libraryCode != null) {
                    // Show library code
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Library Code",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = uiState.libraryCode!!,
                                    style = MaterialTheme.typography.headlineLarge.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 4.sp
                                    ),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(onClick = {
                                    clipboardManager.setText(
                                        AnnotatedString(uiState.libraryCode!!)
                                    )
                                }) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy code"
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (uiState.hasProAccess) "Share this code to sync with other devices"
                                else "Subscribe to Pro to sync across devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = viewModel::forceSync,
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isLoading
                        ) {
                            if (uiState.isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Force Sync")
                        }
                        OutlinedButton(
                            onClick = viewModel::leaveLibrary,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CloudOff, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Leave Library")
                        }
                    }
                } else {
                    // No library yet - create or join
                    Text(
                        text = "Create a new library or join an existing one with a code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = viewModel::createLibrary,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isLoading
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("Create Library")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f))
                        Text(
                            "  OR  ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f))
                    }

                    OutlinedTextField(
                        value = uiState.joinCode,
                        onValueChange = viewModel::onJoinCodeChange,
                        label = { Text("Library Code") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("e.g. ABC123") }
                    )

                    OutlinedButton(
                        onClick = viewModel::joinLibrary,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isLoading && uiState.joinCode.isNotBlank()
                    ) {
                        Text("Join Existing Library")
                    }
                }

                HorizontalDivider()

                OutlinedButton(
                    onClick = {
                        (context as? Activity)?.let { viewModel.signOut(it) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign Out")
                }
            }

            // Error/message
            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            uiState.message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()

            // --- Scanner ---
            Text(
                text = "Scanner",
                style = MaterialTheme.typography.titleLarge
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Continuous Scan", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Scan multiple books without returning to library",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = viewModel.libraryPreferences.continuousScan,
                    onCheckedChange = { viewModel.libraryPreferences.updateContinuousScan(it) }
                )
            }

            HorizontalDivider()

            // --- Data Management ---
            Text(
                text = "Data Management",
                style = MaterialTheme.typography.titleLarge
            )

            OutlinedButton(
                onClick = { viewModel.exportLibraryCsv(context as android.app.Activity) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export Library as CSV")
            }

            Button(
                onClick = viewModel::showClearDataDialog,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear All Local Data")
            }

            Text(
                text = "This removes all books and collections from this device. If sync is enabled, you can re-download by re-joining the library.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // --- Debug Log (admin only) ---
            if (uiState.isAdmin) {
                HorizontalDivider()

                val logEntries by DebugLog.entries.collectAsStateWithLifecycle()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Debug Log",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    Row {
                        IconButton(onClick = {
                            clipboardManager.setText(
                                AnnotatedString(
                                    logEntries.joinToString("\n") { it.formatted() }
                                )
                            )
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy logs"
                            )
                        }
                        IconButton(onClick = { DebugLog.clear() }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Clear logs"
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    val logScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(logScrollState)
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        if (logEntries.isEmpty()) {
                            Text(
                                text = "No log entries yet. Scan a book to see diagnostics.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            logEntries.forEach { entry ->
                                Text(
                                    text = entry.formatted(),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp
                                    ),
                                    color = when (entry.level) {
                                        "E" -> MaterialTheme.colorScheme.error
                                        "W" -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "${logEntries.size} entries",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // --- About ---
            Text(
                text = "About",
                style = MaterialTheme.typography.titleLarge
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LibraryIQ v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Personal book library manager with barcode scanning and multi-device sync.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Metadata sources: Google Books, Open Library, Hardcover, Amazon",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedButton(
                onClick = viewModel::checkForUpdate,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isCheckingUpdate
            ) {
                if (uiState.isCheckingUpdate) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (uiState.isCheckingUpdate) "Checking..." else "Check for Updates")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Clear data dialog
    if (uiState.showClearDataDialog) {
        AlertDialog(
            onDismissRequest = viewModel::hideClearDataDialog,
            title = { Text("Clear All Data?") },
            text = {
                Text("This will permanently delete all books and collections from this device. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = viewModel::clearAllData) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::hideClearDataDialog) {
                    Text("Cancel")
                }
            }
        )
    }

    // Update available dialog
    uiState.updateInfo?.let { update ->
        if (update.isNewer) {
            AlertDialog(
                onDismissRequest = viewModel::dismissUpdate,
                title = { Text("Update Available - v${update.versionName}") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "You have v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (update.releaseNotes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            for (line in update.releaseNotes.lines()) {
                                val trimmed = line.trim()
                                when {
                                    trimmed.startsWith("### ") -> {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = trimmed.removePrefix("### "),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                    }
                                    trimmed.startsWith("- ") -> {
                                        Text(
                                            text = trimmed,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    trimmed.isNotBlank() -> {
                                        Text(
                                            text = trimmed,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = viewModel::downloadUpdate) {
                        Text("Download & Install")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissUpdate) {
                        Text("Later")
                    }
                }
            )
        }
    }
}
