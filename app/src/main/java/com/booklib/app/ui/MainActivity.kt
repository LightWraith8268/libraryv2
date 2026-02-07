package com.booklib.app.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.booklib.app.data.remote.FirestoreSync
import com.booklib.app.ui.navigation.AppNavigation
import com.booklib.app.ui.theme.BookLibTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var firestoreSync: FirestoreSync

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled by the scanner screen */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge to properly handle system bars
        enableEdgeToEdge()

        // Request camera permission upfront for barcode scanning
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)

        // Start Firestore sync if configured
        if (firestoreSync.isSyncEnabled) {
            firestoreSync.startListening()
        }

        setContent {
            BookLibTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        firestoreSync.stopListening()
    }
}
