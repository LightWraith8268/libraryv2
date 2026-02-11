# LibraryIQ - Personal Book Library Manager

An Android app for managing your personal book library with barcode scanning, multi-source metadata lookup, and multi-device sync.

## Features

- **Barcode Scanning** - Scan ISBN barcodes (EAN-13, 978/979 prefixes) with your camera to instantly look up and add books. Supports continuous scan mode for bulk additions.
- **Multi-Source Metadata** - 8-step cascading lookup across 5 sources for the most complete data:
  1. Google Books API (ISBN prefix, general, and ISBN-10 variant searches)
  2. Open Library API (direct ISBN endpoint + search fallback)
  3. Hardcover GraphQL API (ISBN + title search with series data)
  4. Amazon product page scraping (physical books only, excludes eBooks)
  5. Apple Books / iTunes Search API (high-resolution 600x600 cover art)
- **Smart Metadata Merge** - Per-field quality selection: longest description wins, highest page count, most specific date, best series name+number pair, shortest title (less cruft), longest author (more complete)
- **Title-Based Enrichment** - Always searches by title+author across Google Books, Open Library, and Hardcover for maximum metadata coverage
- **Series Tracking** - Extracts series name and position from Hardcover and Amazon (JSON-LD, seriesTitle, title tag parsing). Series names are standardized to prevent duplicates from different sources.
- **Refresh Metadata** - Re-fetch metadata for already-saved books from all sources. Prefers fresh data over existing values while preserving user-set fields (notes, rating, reading status).
- **Reading Status** - Mark books as Unread, Reading, Read, Want to Read, or Want to Buy
- **Custom Collections** - Create lists and organize books into them. "Want to Buy" collection managed automatically.
- **Search & Filter** - Search by title/author and filter by reading status
- **Stats Dashboard** - Reading insights including books by status, top authors/genres, and yearly activity
- **Multi-Device Sync** - Sync your library between devices using Firebase Firestore with shared library codes
- **Google Sign-In** - Sign in with your Google account
- **In-App Updates** - Download and install updates directly from GitHub Releases (sideloaded installs only; hidden for Play Store installs)
- **CSV Export** - Export your library data from Settings > Data Management
- **Subscription Model** - Google Play Billing integration; Pro only required for multi-device sync
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
  - Google Books API (unauthenticated, 1000 req/day)
  - Open Library API (no key needed)
  - Hardcover GraphQL API (JWT token)
  - Amazon HTML scraping (no key needed)
  - iTunes Search API (no key needed, cover art)
- **Image Loading:** Coil
- **Networking:** Retrofit + OkHttp + Gson
- **Billing:** Google Play Billing Library

## Versioning

This project uses semantic versioning. Version numbers are defined in `gradle.properties`:

```properties
app.versionMajor=1   # Breaking changes / major redesigns
app.versionMinor=8   # New features (backward compatible)
app.versionPatch=2   # Bug fixes and small improvements
app.versionBuild=18  # Monotonic counter for Play Store (bump every release)
```

Each number can grow without limit. `versionBuild` is a separate counter that must be incremented with every release for Play Store compatibility. The release workflow automatically bumps versions using conventional commit prefixes.

## CI/CD Workflows

### Release (`release.yml`)
Triggers on push to `main` or manual dispatch. Performs:
1. Bumps version based on conventional commit prefix (`feat:` = minor, `fix:` = patch, `BREAKING CHANGE` / `!:` = major)
2. Updates CHANGELOG.md version header with new version and date
3. Commits and pushes version bump to main (before build, so failures don't block version progression)
4. Builds debug APK, signed release APK, and signed release AAB
5. Creates GitHub Release with versioned artifacts and changelog notes

### Auto PR & Merge (`auto-merge.yml`)
Triggers on push to `claude/**` branches. Automatically:
1. Creates a PR from the branch to main
2. Merges with a merge commit (preserves history)
3. Triggers the release workflow via `workflow_dispatch`

### Build Debug (`build-debug.yml`)
Manual dispatch only. Builds debug APK, signed release AAB, and signed release APK for testing.

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

When you scan a barcode, LibraryIQ runs an 8-step cascading search:

1. **Local Database** - Checks if the book already exists locally (skipped on refresh)

2. **Google Books ISBN** - Three strategies: `isbn:` prefix query, general ISBN search, ISBN-10 variant

3. **Open Library** - Direct ISBN endpoint with search fallback

4. **Hardcover** - GraphQL API with ISBN lookup and series data extraction (requires token)

5. **Amazon Scraping** - Parses the `<title>` HTML tag, extracts author, cover image, page count, publisher, description, series info, and format. Filters out eBook/Kindle editions.

6. **Smart Merge** - Per-field quality selection across all sources (not first-wins)

7. **Title Enrichment** - Always searches by title+author across Google Books, Open Library, and Hardcover for additional metadata

8. **Apple Books Cover** - Queries iTunes Search API for high-resolution 600x600 artwork as the preferred cover source

**Title Cleaning** strips edition suffixes like "Deluxe Edition Hardcover" from the final title. **Series Standardization** normalizes series names to prevent duplicates.

## How Sync Works

- Each user signs in with their Google account
- One user creates a shared library (generates a code like `ABC123`)
- Other users join with that code
- All book and collection data syncs under the shared library in Firestore
- Library code is automatically restored on sign-in
- Works offline; changes sync when connectivity is restored
- Book deletions sync correctly via Firestore document change detection

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
│   │   ├── BookApiService.kt        # Google Books API (unauthenticated)
│   │   ├── BookApiModels.kt         # Google Books response models
│   │   ├── OpenLibraryApiService.kt # Open Library API
│   │   ├── HardcoverApiService.kt   # Hardcover GraphQL API (ISBN + title queries)
│   │   ├── ITunesApiService.kt      # Apple Books / iTunes Search API (cover art)
│   │   ├── AmazonMetadataScraper.kt # Amazon product page scraping
│   │   └── FirestoreSync.kt         # Firebase sync with shared library codes
│   ├── repository/
│   │   └── BookRepository.kt        # 8-step cascading lookup, smart merge, enrichment
│   └── update/
│       └── AppUpdateManager.kt      # GitHub release updates, sideload detection
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
    │   ├── scanner/                 # Barcode scanner (single + continuous modes)
    │   ├── settings/                # Settings, account, sync, updates, debug log viewer
    │   └── stats/                   # Reading stats dashboard
    └── theme/                       # Material 3 theming

.github/workflows/
├── release.yml                      # Auto version bump, build, GitHub Release
├── auto-merge.yml                   # Auto PR & merge from claude/* branches
└── build-debug.yml                  # Manual debug/release build
```

## ProGuard / R8 Notes

The app uses R8 in **compat mode** (`android.enableR8.fullMode=false` in gradle.properties). R8 full mode (AGP 8.2+ default) aggressively strips Hilt-generated module inner classes, breaking `hiltViewModel()` lookups at runtime. Compat mode avoids this while still providing minification.

Critical ProGuard rules in `proguard-rules.pro`:

- **Retrofit:** Keep generic signatures for R8, keep annotation-based method parameters
- **Gson:** Keep TypeToken subclasses, TypeAdapter factories, serialized field names
- **Kotlin Coroutines:** Keep Continuation class for suspend function return types

Without these rules, all API calls silently fail in release builds with `Class cannot be cast to ParameterizedType`.

## Build Variants

- **Debug:** Uses debug keystore, all logging enabled
- **Release:** Minified with R8, signed with release keystore (configured via GitHub Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`)
