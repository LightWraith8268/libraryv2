# BookLib - Personal Book Library Manager

An Android app for managing your personal book library with barcode scanning and multi-device sync.

## Features

- **Book Library Management** - Adding a book to the app means you own it
- **Manual Entry** - Add books by typing in title, author, and other details
- **Barcode Scanning** - Scan UPC/ISBN barcodes with your camera to auto-fill book details
- **Multi-Source Metadata** - Pulls book info from both Google Books API and Open Library API, merging results for the most complete data (title, author, description, cover, series, page count, publisher, language, format, subjects)
- **Reading Status** - Mark books as Unread, Reading, Read, or Want to Read
- **Custom Collections** - Create lists/collections and organize books into them
- **Search & Filter** - Search by title/author and filter by reading status
- **Multi-Device Sync** - Sync your library between devices using Firebase (each person uses their own account, connected via a shared library code)
- **Comprehensive Settings** - Account management, sync controls, data management
- **Material 3 Design** - Modern UI with dynamic colors on Android 12+

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Architecture:** MVVM with Hilt dependency injection
- **Local Database:** Room
- **Remote Sync:** Firebase Firestore + Firebase Auth
- **Barcode Scanning:** CameraX + ML Kit Barcode Scanning
- **Book Lookup:** Google Books API + Open Library API (both free, no API key needed)
- **Image Loading:** Coil
- **Networking:** Retrofit + OkHttp

## Project Setup

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34

### Steps

1. **Clone and open** the project in Android Studio

2. **Set up Firebase** (required for sync, but app works offline without it):
   - Go to [Firebase Console](https://console.firebase.google.com/)
   - Create a new project
   - Add an Android app with package name `com.booklib.app`
   - Download `google-services.json` and place it in `app/` (replacing the placeholder)
   - Enable **Authentication** with Email/Password provider
   - Enable **Cloud Firestore** database

3. **Build and run** on your device or emulator

### First Launch

The app works fully offline with the local Room database. To enable sync:

1. Go to **Settings** tab (bottom nav)
2. Create an account or sign in
3. Tap **Create New Shared Library** to get a 6-character code
4. On the second device, sign in with any account and tap **Join Existing Library** with the code
5. Both devices will now sync in real-time

## How Sync Works

- Each user signs in with their own email/password account
- One user creates a shared library (generates a code like `ABC123`)
- The other user joins with that code
- All book and collection data syncs under the shared library in Firestore
- Changes made on one device appear on the other in real-time
- Works offline; changes sync when connectivity is restored

## Project Structure

```
app/src/main/java/com/booklib/app/
├── BookLibApp.kt                    # Application class (Hilt entry point)
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt          # Room database
│   │   ├── Converters.kt           # Type converters
│   │   ├── dao/                    # Data access objects
│   │   └── entity/                 # Room entities (Book, Collection, etc.)
│   ├── remote/
│   │   ├── BookApiService.kt       # Google Books API
│   │   ├── OpenLibraryApiService.kt # Open Library API
│   │   └── FirestoreSync.kt        # Firebase sync with shared library codes
│   └── repository/                 # Repository layer
├── di/                             # Hilt dependency injection modules
└── ui/
    ├── MainActivity.kt
    ├── components/                 # Reusable UI components
    ├── navigation/                 # Nav graph and screen routes
    ├── screens/
    │   ├── addbook/               # Add book manually
    │   ├── auth/                  # Auth screen (legacy, kept for deep links)
    │   ├── bookdetail/            # Full book detail with metadata
    │   ├── collections/           # Collection management
    │   ├── library/               # Main library view
    │   ├── scanner/               # Barcode scanner
    │   └── settings/              # Comprehensive settings
    └── theme/                     # Material 3 theming
```
