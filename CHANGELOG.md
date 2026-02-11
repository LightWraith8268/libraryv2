# Changelog

All notable changes to LibraryIQ will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [1.8.8] - 2026-02-11 - 2026-02-11 - 2026-02-11 - 2026-02-11 - 2026-02-11 - 2026-02-11

### Added
- In-app update checker — download and install updates directly from GitHub releases (sideloaded installs only)
- "What's New" dialog on first launch after updating with sanitized release notes
- Stats dashboard with reading insights (books by status, top authors/genres, yearly activity)
- "Want to Buy" reading status with automatic collection management
- Continuous barcode scan mode (toggle in Settings > Scanner)
- CSV library export (Settings > Data Management)
- Offline sync indicator in Settings
- Force sync button to fully reconcile local library with cloud
- Apple Books as default high-resolution cover source for all book lookups (600x600 artwork)
- iTunes Search API integration for cover art (no API key required)
- Series name standardization to prevent duplicate series entries from different metadata sources
- Smart metadata merge — per-field quality selection (longest description, highest page count, most specific date, best series pair)
- Auto PR & Merge workflow — pushes to `claude/*` branches auto-create PR, merge to main, and trigger release
- GitHub Actions release workflow with automatic semantic version bumping, changelog updates, versioned APKs, and GitHub Release creation
- Sideload detection — hides in-app update checker for Google Play Store installs

### Changed
- Pro subscription now only required for multi-device sync; creating and joining libraries is free for all users
- Default "To Buy" collection renamed to "Want to Buy"
- Series view auto-sorts by series number
- Cover image priority: Apple Books > Amazon > Hardcover > Open Library > Google Books
- Author names sorted by last name in library view
- Refresh metadata now updates existing fields with fresh data instead of only filling blanks
- Title enrichment (Phase 2) always runs regardless of field completeness for maximum metadata coverage
- Google Books API uses unauthenticated requests (1000/day) instead of Firebase API key
- Google Books title queries use quoted phrases for accurate multi-word matching
- Version bump committed before build so build failures don't block version progression
- Release workflow auto-updates CHANGELOG.md version header with bumped version and date

### Fixed
- Book deletions now sync correctly to other library members via Firestore document change detection
- Google Books API returning no results due to Firebase API key not authorized for Books API
- Google Books title search query encoding — `+` separator was URL-encoded as `%2B` instead of space
- Refresh metadata not applying series name standardization (was keeping old unstandardized name)
- Subscription upsell no longer shown to users who joined a library (they sync free)
- Library code text updates based on Pro status
- Release workflow version bump not persisting due to detached HEAD from `actions/checkout`
- Release workflow `git pull --rebase` failing with unstaged changes after sed modifications
- Auto-merge workflow `grep -oP` not available on Ubuntu runner — replaced with `rev | cut`
- Auto-merge workflow not triggering release — GITHUB_TOKEN merges don't fire `on: push`, now uses `gh workflow run`

## [1.3.0] - 2026-02-09

### Added
- Refresh Metadata button on book detail screen (top bar icon + body button) to re-fetch metadata from all sources without overwriting user-set fields
- Series name and number displayed on book cards in the library list
- Amazon series extraction from JSON-LD structured data, seriesTitle section, "Part of" links, and title tag parentheticals
- Amazon physical format detection (Hardcover, Paperback, etc.) stored on books
- eBook/Kindle edition filtering - Amazon scraper skips non-physical books
- Semantic versioning with decoupled versionBuild counter for unlimited version numbers
- Dynamic version display in Settings About section from BuildConfig
- Error response body logging for Google Books and Hardcover API calls

### Fixed
- Amazon title tag author parsing - now searches backwards through all parts instead of only checking index 1
- Hardcover title search HTTP 403 - routed through editions table instead of books table
- Hardcover _ilike operation rejected - switched to _eq for title matching
- Amazon scraper extracting JavaScript variable names as titles - switched to desktop User-Agent and `<title>` tag parsing
- Amazon scraper extracting social media text as publishers - added validation
- Page count merge - treat 0 as missing so Amazon's page count isn't overwritten
- Edition suffixes stripped from final merged titles ("Wildfire: Deluxe Edition Hardcover" -> "Wildfire")
- Amazon always runs alongside other sources instead of only as last resort

## [1.2.0] - 2026-02-09

### Added
- Title-based metadata enrichment (Phase 2) - when ISBN results are incomplete, searches by title+author across Google Books, Open Library, and Hardcover
- Series data from Hardcover GraphQL API (book_series with series name and position)
- Hardcover title search using editions table with book title filter
- Debug log viewer visible to admin users in all builds (not just debug)

### Fixed
- R8/ProGuard stripping Retrofit generic type signatures causing all API calls to fail in release builds
- Scanner now only accepts one barcode per session (blocks all after first detection)
- Scan job cancellation - tracks lookup jobs to prevent conflicts from rapid scans
- Google Books API key added to avoid HTTP 429 rate limiting
- CI workflow now passes HARDCOVER_API_TOKEN to all build steps

## [1.1.0] - 2026-02-09

### Added
- Hardcover GraphQL API as metadata source (ISBN search)
- Amazon product page scraping as metadata fallback (like Calibre)
- Google Sign-In authentication (replacing email/password)
- In-app DebugLog singleton with 500-entry ring buffer for diagnostics
- Library code persistence - auto-restore from Firestore after sign-in
- Google Books API key support

### Fixed
- Debug build Google Sign-In - added debug keystore SHA-1 to Firebase
- Sign-in errors no longer silently swallowed - logged to DebugLog and shown to user

## [1.0.0] - 2026-02-08

### Added
- Book library management with Room database (offline-first)
- Manual book entry (title, author, ISBN, and other details)
- Barcode scanning with CameraX + ML Kit (EAN-13, 978/979 prefixes)
- Multi-source metadata lookup: Google Books API + Open Library API
- Metadata merging from multiple sources for most complete data
- Reading status tracking (Unread, Reading, Read, Want to Read)
- Custom collections for organizing books
- Search by title/author and filter by reading status
- Multi-device sync via Firebase Firestore with shared library codes
- Material 3 design with dynamic colors on Android 12+
- Subscription model with Google Play Billing
