# LibraryIQ - Personal Book Library Manager

An Android app for managing your personal book library with barcode scanning, multi-source metadata lookup, and multi-device sync.

## Features

- **Barcode Scanning** - Scan ISBN barcodes (EAN-13, 978/979 prefixes) with your camera to instantly look up and add books
- **Multi-Source Metadata** - Cascading lookup across 8 sources for the most complete data:
  1. Google Books API (ISBN, general, and ISBN-10 searches)
  2. Open Library API (direct ISBN endpoint + search)
  3. Hardcover GraphQL API (ISBN + title search with series data)
  4. Amazon product page scraping (physical books only, excludes eBooks)
- **Title-Based Enrichment** - When ISBN results are incomplete, automatically searches by title+author across Google Books, Open Library, and Hardcover to fill in missing metadata
- **Series Tracking** - Extracts series name and position from Hardcover and Amazon (JSON-LD, seriesTitle, title tag parsing)
- **Refresh Metadata** - Re-fetch metadata for already-saved books from all sources without losing user data (notes, reading status, collections)
- **Reading Status** - Mark books as Unread, Reading, Read, or Want to Read
- **Custom Collections** - Create lists and organize books into them
- **Search & Filter** - Search by title/author and filter by reading status
- **Multi-Device Sync** - Sync your library between devices using Firebase Firestore with shared library codes
- **Google Sign-In** - Sign in with your Google account
- **Subscription Model** - Google Play Billing integration for premium features
- **Admin Debug Console** - In-app log viewer (visible to admin users) showing API call results, errors, and metadata extraction details
- **Material 3 Design** - Modern UI with dynamic colors on Android 12+

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose + Material 3
- **Architecture:** MVVM with Hilt dependency injection
- **Local Database:** Room (offline-first)
- **Remote Sync:** Firebase Firestore + Firebase Auth
- **Authentication:** Google Sign-In
- **Barcode Scanning:** CameraX + ML Kit Barcode Scanning
- **Book Metadata:**
  - Google Books API (with API key)
  - Open Library API (no key needed)
  - Hardcover GraphQL API (JWT token)
  - Amazon HTML scraping (no key needed)
- **Image Loading:** Coil
- **Networking:** Retrofit + OkHttp + Gson
- **Billing:** Google Play Billing Library

## Versioning

This project uses semantic versioning. Version numbers are defined in `app/build.gradle.kts`:

```kotlin
val versionMajor = 1   // Breaking changes / major redesigns
val versionMinor = 3   // New features (backward compatible)
val versionPatch = 0   // Bug fixes and small improvements
val versionBuild = 1   // Monotonic counter for Play Store (bump every release)
```

Each number can grow without limit. `versionBuild` is a separate counter that must be incremented with every release for Play Store compatibility.

## Project Setup

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34

### Steps

1. **Clone and open** the project in Android Studio

2. **Set up Firebase** (required for sync and sign-in):
   - Go to [Firebase Console](https://console.firebase.google.com/)
   - Create a new project or use existing project `library-e9079`
   - Add an Android app with package name `com.inknironapps.libraryiq`
   - Download `google-services.json` and place it in `app/`
   - Enable **Authentication** with Google Sign-In provider
   - Enable **Cloud Firestore** database
   - Add both debug and release SHA-1 fingerprints to the Firebase Android app

3. **Configure API keys:**
   - **Google Books API:** Enable "Books API" in Google Cloud Console, and ensure the API key in `BookApiService.kt` has Books API in its allowed APIs list
   - **Hardcover API:** Add your token to `local.properties`:
     ```properties
     HARDCOVER_API_TOKEN=your_jwt_token_here
     ```
     Or set the `HARDCOVER_API_TOKEN` environment variable for CI builds

4. **Build and run** on your device or emulator

### First Launch

The app works fully offline with the local Room database. To enable sync:

1. Tap the **Settings** tab (bottom nav)
2. Sign in with your Google account
3. Tap **Create New Shared Library** to get a 6-character code
4. On a second device, sign in and tap **Join Existing Library** with the code
5. Both devices will now sync in real-time

## How Metadata Lookup Works

When you scan a barcode, LibraryIQ runs a cascading search:

1. **ISBN Search (Phase 1):** Queries Google Books, Open Library, Hardcover, and Amazon by ISBN. Results from all sources are merged, with the first source providing the base and others filling in missing fields.

2. **Title Enrichment (Phase 2):** If the merged result is missing 2+ key fields (cover, description, pages, publisher, author, series, series number), it searches again by title+author across Google Books, Open Library, and Hardcover.

3. **Amazon Scraping:** Parses the `<title>` HTML tag (most reliable), extracts author, cover image, page count, publisher, description, series info, and format. Filters out eBook/Kindle editions automatically.

4. **Title Cleaning:** Strips edition suffixes like "Deluxe Edition Hardcover" from the final title.

## How Sync Works

- Each user signs in with their Google account
- One user creates a shared library (generates a code like `ABC123`)
- Other users join with that code
- All book and collection data syncs under the shared library in Firestore
- Library code is automatically restored on sign-in
- Works offline; changes sync when connectivity is restored

## Project Structure

```
app/src/main/java/com/inknironapps/libraryiq/
├── LibraryIQApp.kt                  # Application class (Hilt entry point)
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt           # Room database
│   │   ├── Converters.kt            # Type converters
│   │   ├── dao/                     # Data access objects
│   │   └── entity/                  # Room entities (Book, Collection, etc.)
│   ├── remote/
│   │   ├── BookApiService.kt        # Google Books API (with API key)
│   │   ├── OpenLibraryApiService.kt # Open Library API
│   │   ├── HardcoverApiService.kt   # Hardcover GraphQL API (ISBN + title queries)
│   │   ├── AmazonMetadataScraper.kt # Amazon product page scraping
│   │   └── FirestoreSync.kt         # Firebase sync with shared library codes
│   └── repository/
│       └── BookRepository.kt        # Cascading lookup, merge, enrichment logic
├── di/                              # Hilt dependency injection modules
├── util/
│   └── DebugLog.kt                  # In-memory log buffer for admin debug viewer
└── ui/
    ├── MainActivity.kt
    ├── components/
    │   └── BookCard.kt              # Book list item with series display
    ├── navigation/                  # Nav graph and screen routes
    ├── screens/
    │   ├── addbook/                 # Manual book entry
    │   ├── bookdetail/              # Book detail with refresh metadata
    │   ├── collections/             # Collection management
    │   ├── library/                 # Main library view
    │   ├── scanner/                 # Barcode scanner (single scan per session)
    │   └── settings/                # Settings, account, sync, debug log viewer
    └── theme/                       # Material 3 theming

.github/workflows/
└── build-debug.yml                  # CI: debug build, release AAB, release APK
```

## ProGuard / R8 Notes

The app uses R8 (minification) in release builds. Critical ProGuard rules in `proguard-rules.pro`:

- **Retrofit:** Keep generic signatures for R8 full mode, keep annotation-based method parameters
- **Gson:** Keep TypeToken subclasses, TypeAdapter factories, serialized field names
- **Kotlin Coroutines:** Keep Continuation class for suspend function return types

Without these rules, all API calls silently fail in release builds with `Class cannot be cast to ParameterizedType`.

## Build Variants

- **Debug:** Uses debug keystore, all logging enabled
- **Release:** Minified with R8, signed with release keystore (configured via environment variables: `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`)
