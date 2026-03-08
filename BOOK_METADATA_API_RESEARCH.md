# Book Metadata API & Service Research

## Executive Summary

This document is an exhaustive analysis of every available API and service that provides book metadata by ISBN lookup. It covers the 7 sources currently used by LibraryIQ, evaluates 15+ potential new sources, and provides field-level comparisons and practical recommendations.

**Current sources**: Google Books, Open Library, Hardcover.app, Amazon (scraping), Barnes & Noble (scraping), Target (scraping), iTunes/Apple Books
**Top recommended additions**: ISBNdb (paid, most complete single source), Penguin Random House (free, no key), Google Books `seriesInfo` field (free, already partially integrated)

---

## PART 1: CURRENTLY USED SOURCES

---

### 1.1 Google Books API

**Status**: Primary source, already integrated
**Authentication**: Optional API key (higher quota with key)
**Rate Limits**: 1,000 requests/day without key; 10,000/day with key (can request increase)
**Coverage**: ~40 million volumes
**Cost**: Free

#### Current Integration

The app queries `GET https://www.googleapis.com/books/v1/volumes?q=isbn:{isbn}` and parses these fields from `VolumeInfo`:

```
Currently parsed:
  title, authors[], publisher, publishedDate, description,
  pageCount, categories[], language, imageLinks{}, industryIdentifiers[]
```

#### ALL Available Fields (from Java SDK / actual API responses)

The `volumeInfo` object actually contains **28 fields**, not the 10 currently parsed:

| Field | Type | Currently Used | Notes |
|-------|------|:-:|-------|
| `title` | string | YES | |
| `subtitle` | string | NO | **Missing** -- should be captured |
| `authors[]` | list | YES | |
| `publisher` | string | YES | |
| `publishedDate` | string | YES | |
| `description` | string | YES | HTML-formatted with basic tags |
| `industryIdentifiers[]` | list | YES | `{type, identifier}` -- ISBN_10, ISBN_13, ISSN, OTHER |
| `pageCount` | integer | YES | |
| `printedPageCount` | integer | NO | **Undocumented** -- actual printed page count vs metadata pageCount |
| `dimensions` | object | NO | `{height, width, thickness}` in cm |
| `printType` | string | NO | BOOK or MAGAZINE |
| `mainCategory` | string | NO | Primary category (deprecated but still returned) |
| `categories[]` | list | YES | |
| `averageRating` | double | NO | 1.0-5.0 scale |
| `ratingsCount` | integer | NO | |
| `maturityRating` | string | NO | **Undocumented in REST docs** -- NOT_MATURE, MATURE |
| `contentVersion` | string | NO | |
| `panelizationSummary` | object | NO | **Undocumented** -- `{containsEpubBubbles, containsImageBubbles, imageBubbleVersion, epubBubbleVersion}` |
| `imageLinks` | object | YES | `{smallThumbnail, thumbnail, small, medium, large, extraLarge}` |
| `language` | string | YES | ISO 639-1 two-letter code |
| `previewLink` | string | NO | |
| `infoLink` | string | NO | |
| `canonicalVolumeLink` | string | NO | |
| `readingModes` | object | NO | **Undocumented** -- `{text: bool, image: bool}` |
| `allowAnonLogging` | boolean | NO | **Undocumented** |
| `comicsContent` | boolean | NO | **Undocumented** -- true for graphic novels/comics |
| `seriesInfo` | object | NO | **CRITICAL MISSING FIELD** -- see below |
| `samplePageCount` | integer | NO | **Undocumented** |

##### The seriesInfo Object (Currently NOT Parsed)

This is a major gap. Google Books returns series information for many books, but the current integration completely ignores it. The `seriesInfo` object structure:

```json
"seriesInfo": {
  "kind": "books#volume_series_info",
  "shortSeriesBookTitle": "The Hunger Games",
  "bookDisplayNumber": "1",
  "volumeSeries": [
    {
      "seriesId": "abc123",
      "seriesBookType": "ISSUE",
      "orderNumber": 1,
      "issue": [
        {
          "issueDisplayNumber": "1",
          "issueOrderNumber": 1
        }
      ]
    }
  ]
}
```

Fields:
- `bookDisplayNumber` -- Display string for position (e.g., "1", "2.5")
- `shortSeriesBookTitle` -- Book title within series context
- `volumeSeries[]` -- Array of series associations
  - `seriesId` -- Google's internal series ID
  - `seriesBookType` -- "ISSUE", "COLLECTION_EDITION", "OMNIBUS"
  - `orderNumber` -- Numeric position in series
  - `issue[]` -- For collections/omnibuses, individual issues contained

**Recommendation**: Add `seriesInfo` parsing immediately. This is free data already being returned but discarded.

##### Additional Volume-Level Fields

Beyond `volumeInfo`, each volume also has:
- `saleInfo` -- `{country, saleability, isEbook, listPrice{amount, currencyCode}, retailPrice{}, buyLink, onSaleDate}`
- `accessInfo` -- `{country, viewability, embeddable, publicDomain, textToSpeechPermission, epub{isAvailable, downloadLink}, pdf{isAvailable, downloadLink}, webReaderLink, accessViewStatus}`
- `searchInfo` -- `{textSnippet}`

#### Data Quality Assessment
- **Title/Author**: Excellent (99%+ accuracy for published books)
- **Description**: Good (HTML-formatted, usually publisher-provided)
- **Page Count**: Good (~85% populated)
- **Categories**: Moderate (broad categories like "Fiction / General", not granular genres)
- **Series**: Available via `seriesInfo` but incomplete (~40% of series books have it)
- **Language**: Good (present for most entries)
- **Publisher/Date**: Good (~90% populated)
- **Covers**: Good quality but limited to ~128px thumbnails by default

#### Gotchas & Known Issues
- The `q=isbn:` prefix search sometimes misses books; a general search with the ISBN string as the query can find books that the prefix misses
- ISBN-10 and ISBN-13 searches can return different results
- The `imageLinks` URLs use HTTP; must upgrade to HTTPS
- The `zoom=1` parameter in thumbnail URLs gives cropped/curled images; `zoom=0` gives full images
- Rate limits are per-project, not per-key
- `categories[]` uses Google's own taxonomy, not BISAC or standard library classifications
- Description may contain HTML tags

---

### 1.2 Open Library API

**Status**: Secondary source, already integrated
**Authentication**: None required
**Rate Limits**: Undocumented but ~100 requests/minute recommended; can be rate-limited
**Coverage**: ~30 million editions
**Cost**: Free (open data)

#### Current Integration

Uses two endpoints:
1. `GET https://openlibrary.org/isbn/{isbn}.json` -- Direct edition lookup
2. `GET https://openlibrary.org/search.json?isbn={isbn}` -- Search fallback
3. `GET https://openlibrary.org/{workKey}.json` -- Work-level data (for description)

Currently parsed from edition:
```
title, authors[].key, publishers[], publish_date, number_of_pages,
isbn_13[], isbn_10[], covers[], description, subjects[],
series[], languages[].key, physical_format, works[].key
```

#### Complete Edition Schema (47 fields total)

The full `/type/edition` schema has 47 properties. Fields NOT currently parsed:

| Field | Type | Notes |
|-------|------|-------|
| `title_prefix` | string | "The", "A", "An" etc. |
| `subtitle` | string | **Missing** |
| `other_titles[]` | string[] | Alternate/translated titles |
| `by_statement` | string | "by Author Name" or "edited by..." |
| `copyright_date` | string | |
| `edition_name` | string | **Useful** -- "First Edition", "Revised Edition" etc. |
| `notes` | text | |
| `genres[]` | string[] | **Missing** -- distinct from subjects |
| `table_of_contents[]` | toc_item[] | Chapter listing |
| `work_titles[]` | string[] | |
| `physical_dimensions` | string | "20 x 13.3 x 1.8 centimeters" |
| `pagination` | string | "xii, 345 p." -- library-style page notation |
| `lccn[]` | string[] | Library of Congress Control Number |
| `ocaid` | string | Internet Archive identifier |
| `oclc_numbers[]` | string[] | WorldCat/OCLC identifiers |
| `dewey_decimal_class[]` | string[] | Dewey classification |
| `lc_classifications[]` | string[] | LC classification |
| `contributions[]` | string[] | "Illustrated by X", "Translated by Y" |
| `publish_places[]` | string[] | |
| `publish_country` | string | Two-letter code |
| `distributors[]` | string[] | |
| `first_sentence` | text | |
| `weight` | string | Physical weight |
| `location[]` | string[] | |
| `scan_on_demand` | boolean | |
| `collections[]` | collection[] | |
| `uris[]` | string[] | Related URLs |
| `uri_descriptions[]` | string[] | |
| `translation_of` | string | Original title if translated |
| `source_records[]` | string[] | Where the data came from |
| `translated_from[]` | language[] | Original language |
| `accompanying_material` | string | "1 CD-ROM" etc. |

#### Works-Level Fields

The Works endpoint (`/works/OL...W.json`) provides:

| Field | Notes |
|-------|-------|
| `title` | Canonical title across all editions |
| `description` | Usually the best description available |
| `subjects[]` | Comprehensive subject tags |
| `subject_places[]` | Geographic locations in the book |
| `subject_people[]` | Characters/people in the book |
| `subject_times[]` | Time periods |
| `covers[]` | Cover IDs |
| `authors[]` | Author references with roles |
| `excerpts[]` | Notable passages |
| `links[]` | External reference URLs |
| `series[]` | **Series info at works level** |

#### Data Quality Assessment
- **Title/Author**: Good (community-curated, occasional errors)
- **Description**: Variable -- works-level often good, edition-level often missing. The `description` field can be either a string or an object `{type, value}` (app already handles this)
- **Page Count**: Moderate (~65% populated)
- **Subjects**: Excellent -- comprehensive subject tagging, best free source for this
- **Series**: Poor -- very inconsistently populated
- **Language**: Good when present, uses `/l/eng` format references
- **Edition/Format**: Moderate -- `physical_format` gives "Paperback", "Hardcover" etc.
- **Classifications**: Good -- Dewey and LC classifications when available
- **Covers**: High resolution available via `covers.openlibrary.org/b/id/{id}-L.jpg`

#### Gotchas & Known Issues
- The `description` field has inconsistent types: sometimes a plain string, sometimes `{type: "/type/text", value: "..."}`. The current code handles this with `Any?` type
- Author data requires a separate API call to resolve `/authors/OL...A.json` into names
- Works vs Editions data model: edition has publisher-specific data, work has canonical/shared data. Must fetch both for complete coverage
- Edition records vary wildly in completeness -- some have 5 fields, others have 40+
- No formal rate limiting documentation, but aggressive requests get 429 responses
- Cover URL with `?default=false` returns 404 instead of 1x1 pixel placeholder (app correctly uses this)

---

### 1.3 Hardcover.app GraphQL API

**Status**: Tertiary source, already integrated
**Authentication**: Bearer token (free account)
**Rate Limits**: ~1 request/second recommended; simultaneous mutations may fail
**Coverage**: Growing -- curated database focused on English-language fiction, non-fiction gaining coverage
**Cost**: Free

#### Current Integration

GraphQL endpoint: `POST https://api.hardcover.app/v1/graphql`

Current query fetches from `editions` table:
```graphql
editions(where: {_or: [{isbn_13: {_eq: $isbn}}, {isbn_10: {_eq: $isbn}}]}) {
    isbn_13, isbn_10, pages, title,
    image { url },
    release_date,
    book {
        title, description,
        contributions(limit: 5) { author { name } },
        book_series { series { name }, position }
    },
    publisher { name }
}
```

#### Additional Available Fields NOT Currently Queried

**Edition-level fields**:
| Field | Notes |
|-------|-------|
| `edition_format` | **Missing** -- "hardcover", "paperback", "ebook", "audiobook" |
| `audio_seconds` | For audiobook editions |
| `asin` | Amazon Standard ID |
| `language_id` | Language reference |
| `cached_image` | Alternative image field |
| `reading_format_id` | Reading format reference |
| `edition_information` | Free-text edition info |
| `country_id` | Country of publication |
| `id` | Hardcover edition ID |

**Book-level fields** (via `book {}` relation):
| Field | Notes |
|-------|-------|
| `slug` | URL identifier |
| `cached_tags` | **Missing** -- user-generated genre/tags |
| `cached_contributors` | Author/translator info |
| `users_read_count` | Popularity metric |
| `activities_count` | Activity count |
| `alternative_titles` | Alternate/translated titles |
| `compilation` | Whether it's a compilation |
| `ratings_count` | Number of ratings |
| `rating` | Average rating (presumably 0-5) |
| `image` | Book-level image |

**Series fields** (via `book_series -> series` relation):
| Field | Notes |
|-------|-------|
| `series.name` | YES, currently fetched |
| `position` | YES, currently fetched (float) |
| `series.primary_books_count` | Total books in series |
| `series.readers_count` | How many people read the series |
| `series.author_name` | Series author |

#### Data Quality Assessment
- **Title/Author**: Excellent for covered books (curated data)
- **Description**: Good (often sourced from publishers)
- **Series**: **BEST source for series data** -- position is a float (handles "1.5" for novellas), well-curated
- **Edition/Format**: Good via `edition_format`
- **Genre/Tags**: Good via `cached_tags` (user-generated, like Goodreads)
- **Page Count**: Good when present
- **Coverage**: Weaker for older/obscure/non-English books
- **Covers**: Good quality

#### Gotchas & Known Issues
- The `_eq` operator on title requires exact match (case-sensitive); `_ilike` was removed for performance
- The API is the same one used by the website/apps, so schema can change without notice
- Simultaneous mutations may fail -- need 1-second delays
- Imported Goodreads data (reviews) may not have spoiler tags populated
- No formal API stability guarantee, but the team is developer-friendly

---

### 1.4 Amazon Product Page Scraping

**Status**: Supplementary source, already integrated
**Authentication**: None (web scraping)
**Rate Limits**: Must self-throttle; aggressive scraping gets blocked
**Coverage**: Essentially every commercially available book
**Cost**: Free (but legally gray)

#### Current Implementation

URL: `https://www.amazon.com/dp/{isbn}` or search by ISBN
Parses two HTML layouts:
- Layout A: Detail bullets list (`detailBullets_feature_div`)
- Layout B: Product details table (`productDetails_detailBullets_sections1`)
- Product overview table (`productOverview_feature_div`)

#### Fields Extracted
- Title, Author, Cover URL, Description
- Pages, Publisher, Published Date
- Format (Hardcover/Paperback/Kindle/etc.)
- Series name and number
- Language, ASIN, ISBN-10, Edition
- Genre/subjects (from breadcrumb navigation)

#### Data Quality Assessment
- **Title/Author**: Excellent
- **Description**: Excellent (publisher-provided, often longest available)
- **Series**: Good (from title parentheticals and product details)
- **Format**: Excellent (Amazon clearly labels editions)
- **Genre**: Good (Amazon's browse node hierarchy)
- **Coverage**: Best coverage of any single source
- **ASIN**: Unique to Amazon, useful for cross-referencing

#### Gotchas & Known Issues
- **Legal risk**: Against Amazon TOS; can result in IP blocking
- **Fragile**: HTML structure changes without notice, breaking parsers
- **Bot detection**: Amazon actively detects and blocks scrapers; requires realistic User-Agent and request patterns
- **Regional variation**: amazon.com vs amazon.co.uk have different catalogs
- **A/B testing**: Amazon serves different page layouts to different users, making parsing unreliable
- The current implementation handles both Layout A and Layout B robustly

#### Amazon Product Advertising API (Official Alternative)

Amazon has an official API (PA-API 5.0) that would be more reliable but requires an Amazon Associates account with qualifying sales. Key details:

**Endpoint**: `https://webservices.amazon.com/paapi5/searchitems` (POST)
**Auth**: AWS-style HMAC-SHA256 signing with Access Key + Secret Key + Associate Tag
**Requirements**: Must be an active Amazon Associate with qualifying sales in the last 30 days
**Rate Limits**: 1 request/second initially, scales with revenue

**Available ItemInfo sub-resources**:
- `ByLineInfo` -- Contributors (Name, Role, RoleType)
- `Classifications` -- Binding, ProductGroup
- `ContentInfo` -- Edition, Languages[], PagesCount, PublicationDate
- `ContentRating` -- AudienceRating
- `ExternalIds` -- EANs[], ISBNs[], UPCs[]
- `Features` -- Feature descriptions
- `ProductInfo` -- ItemDimensions, ReleaseDate, IsAdultProduct
- `TechnicalInfo` -- Formats[]
- `Title` -- DisplayValue

**Verdict**: Only practical if the app generates Amazon affiliate revenue. Not recommended for a library cataloging app.

---

### 1.5 Barnes & Noble Scraping

**Status**: Supplementary source, already integrated
**Authentication**: None (web scraping)
**Rate Limits**: Self-throttle
**Coverage**: US market books, strong on new releases
**Cost**: Free (scraping)

#### Current Implementation

Direct ISBN lookup URL: `https://www.barnesandnoble.com/w/?ean={isbn13}`

Extraction priority:
1. JSON-LD structured data (schema.org/Book) -- most reliable
2. Open Graph meta tags
3. HTML pattern matching (product detail sections)

#### Fields Extracted
- Title, Author, Description, Cover URL
- Publisher, Publication date, Pages, ISBN-10
- Format ("Paperback", "Hardcover", etc.)
- Series name and number (from `isPartOf` in JSON-LD)
- Language

#### Data Quality Assessment
- **Title/Author**: Excellent
- **Description**: Excellent (publisher-provided)
- **Series**: Good when present in JSON-LD `isPartOf`
- **Format**: Very good
- **JSON-LD reliability**: B&N has good structured data markup
- **Coverage**: US market focus, weaker on international/indie titles

#### Gotchas & Known Issues
- JSON-LD is the most reliable extraction method but not always present
- Product pages sometimes show "temporarily unavailable" for valid ISBNs
- Mobile vs desktop HTML differs
- B&N sometimes redirects ISBNs to search results instead of product pages
- Scraping is against TOS but less aggressively policed than Amazon

---

### 1.6 Target Scraping

**Status**: Supplementary source, already integrated
**Authentication**: None (web scraping)
**Rate Limits**: Self-throttle
**Coverage**: Limited to books Target sells (popular titles, bestsellers)
**Cost**: Free (scraping)

#### Current Implementation

Search URL: `https://www.target.com/s?searchTerm={isbn}`
Target uses Next.js, so data is available in multiple places:
1. `__NEXT_DATA__` JSON blob
2. JSON-LD structured data
3. Specifications section (collapsed CSS, but in HTML)
4. Open Graph meta tags

#### Fields Extracted
- Title, Author, Description, Cover URL
- Publisher, Publication date, Pages
- Format, ISBN-10, Language
- Series name and number

#### Data Quality Assessment
- **Title/Author**: Good
- **Specifications section**: Very structured with key-value pairs (Publisher, Page Count, Format, Language, Series Title, etc.)
- **Coverage**: Limited -- only books Target carries (~50K titles vs millions elsewhere)
- **Freshness**: Good for current titles, bad for backlist

#### Gotchas & Known Issues
- Target heavily uses JavaScript rendering; the `__NEXT_DATA__` blob is the most reliable source
- Limited catalog means many ISBNs return no results
- Target's search sometimes returns non-book products for ISBN queries
- Good as a supplementary source for format/series info on popular titles

---

### 1.7 iTunes / Apple Books API

**Status**: Cover image source + supplementary metadata, already integrated
**Authentication**: None required
**Rate Limits**: ~20 requests/minute
**Coverage**: Apple Books catalog (strong on ebooks, audiobooks)
**Cost**: Free

#### Current Integration

- `GET https://itunes.apple.com/lookup?isbn={isbn}` -- Direct ISBN lookup
- `GET https://itunes.apple.com/search?term={term}&media=ebook&entity=ebook` -- Search

Currently parsed fields: `trackName, artistName, artworkUrl60, artworkUrl100, primaryGenreName, releaseDate, description, trackViewUrl`

#### ALL Available Fields (from actual ebook responses)

| Field | Currently Used | Notes |
|-------|:-:|-------|
| `artistIds` | NO | |
| `artistId` | NO | Apple's author ID |
| `artistName` | YES | Author name |
| `genres[]` | NO | **Missing** -- array of genre strings |
| `genreIds[]` | NO | Genre ID numbers |
| `price` | NO | Ebook price |
| `releaseDate` | YES | ISO 8601 format |
| `trackId` | NO | Apple's book ID |
| `trackName` | YES | Book title |
| `kind` | NO | "ebook" |
| `currency` | NO | "USD" etc. |
| `description` | YES | Full HTML description |
| `trackCensoredName` | NO | |
| `artistViewUrl` | NO | |
| `trackViewUrl` | YES | |
| `artworkUrl60` | YES | 60x60 cover |
| `artworkUrl100` | YES | 100x100 cover (upscaled to 600x600 in code) |
| `formattedPrice` | NO | "$9.99" etc. |
| `averageUserRating` | NO | Rating value |
| `userRatingCount` | NO | Number of ratings |

#### Data Quality Assessment
- **Covers**: **BEST source for high-resolution covers** -- 100x100 can be upscaled to 600x600 by replacing URL pattern, and Apple has the highest-quality artwork
- **Description**: Good (publisher-provided)
- **Genre**: `genres[]` array is more specific than Google's `categories[]`
- **Title/Author**: Good
- **Coverage**: Only ebooks/audiobooks in Apple's catalog; no print-only books
- **ISBN lookup**: Hit rate is low (~30-40%) because many ISBNs are print-edition ISBNs that Apple doesn't have

#### Gotchas & Known Issues
- ISBN lookup often returns `resultCount: 0` because the ISBN is for a print edition, not an ebook
- The artwork URL pattern trick (`100x100bb` -> `600x600bb`) is undocumented but stable for years
- Rate limiting is lenient (~20/min) but undocumented
- No API key means no support or SLA
- `genres[]` field is not currently being parsed -- should be added

---

## PART 2: NEW SOURCES TO EVALUATE

---

### 2.1 ISBNdb

**Status**: Not integrated
**Authentication**: API key required (Bearer token)
**Rate Limits**: 1 req/sec (all plans); Premium: 3 req/sec; Pro: 5 req/sec
**Coverage**: 108+ million book titles -- **largest commercial book database**
**Cost**: PAID -- $14.95/mo (Basic), $29.95/mo (Premium), $74.95/mo (Pro)

#### API Endpoints

Base URL: `https://api2.isbndb.com`

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/book/{isbn}` | GET | Lookup by ISBN |
| `/books/{query}` | GET | Search by title/author/keyword |
| `/author/{name}` | GET | Author lookup |
| `/publisher/{name}` | GET | Publisher lookup |
| `/search/books` | POST | Advanced search with filters |
| `/stats` | GET | Database statistics |

#### Response Fields

| Field | Guaranteed | Notes |
|-------|:-:|-------|
| `title` | YES | |
| `title_long` | NO | Full title with subtitle |
| `isbn` | YES | ISBN-10 |
| `isbn13` | YES | ISBN-13 |
| `authors[]` | YES | Author names |
| `publisher` | NO | |
| `publish_date` | NO | |
| `binding` | NO | "Hardcover", "Paperback", etc. |
| `pages` | NO | Page count |
| `dimensions` | NO | Physical dimensions |
| `weight` | NO | Physical weight |
| `language` | NO | |
| `edition` | NO | "First Edition", "Revised" etc. |
| `image` | NO | Cover image URL |
| `synopsis` | NO | Book description |
| `overview` | NO | Extended description |
| `subjects[]` | NO | Subject classifications |
| `dewey_decimal` | NO | Dewey classification |
| `format` | NO | Additional format info |
| `msrp` | NO | List price |
| `related` | NO | Related ISBNs (Pro+) |

**Note**: Only `title`, `authors`, `isbn`, and `isbn13` are guaranteed for every book. Other fields coverage varies -- ISBNdb claims "up to 19 data points per book."

#### Bulk Data (Premium+)

POST to `/books` with up to 1,000 ISBNs in one request. Only available on Premium ($29.95) and higher plans.

#### Data Quality Assessment
- **Title/Author**: Excellent (guaranteed fields)
- **Description**: `synopsis` + `overview` give two levels of description; coverage moderate
- **Series**: No dedicated series field -- **significant gap**
- **Edition/Format**: `binding` + `edition` provide good format/edition info
- **Classifications**: `dewey_decimal` and `subjects[]` when available
- **Coverage**: Largest database (108M+), strong for obscure/old ISBNs
- **International**: Good multi-language coverage
- **Self-published/Indie**: Good coverage (better than Google Books for some indie titles)

#### Verdict
**Worth integrating if budget allows**. ISBNdb fills gaps for obscure ISBNs that Google Books and Open Library miss. The lack of series data is a drawback. Best used as a fallback for ISBNs not found elsewhere, or as a source for `binding`/`edition`/`dimensions` data.

---

### 2.2 Google Books Volume Details Endpoint

**Status**: Partially integrated (search endpoint used, but not direct volume GET)
**Authentication**: Optional API key
**Rate Limits**: Same as search (1K-10K/day)
**Cost**: Free

#### The Endpoint

`GET https://www.googleapis.com/books/v1/volumes/{volumeId}`

This returns MORE data than the search endpoint for a specific volume. The search returns items with `projection=lite` by default, while the direct GET returns the full resource.

#### Additional Fields Available via Direct GET

When you get a volume by ID (rather than finding it via search), you get:
- Full `description` (search may truncate)
- `dimensions` object
- Full `imageLinks` (including `small`, `medium`, `large`, `extraLarge` -- search only returns `thumbnail` and `smallThumbnail`)
- `seriesInfo` (may not appear in search results)
- `saleInfo` with pricing
- `accessInfo` with download/preview availability

#### Implementation Strategy

1. Search by ISBN: `GET /volumes?q=isbn:{isbn}` -- get the volume `id`
2. Fetch full details: `GET /volumes/{id}` -- get complete metadata including `seriesInfo`

This two-step approach maximizes data extraction. The current code only does step 1.

---

### 2.3 Penguin Random House API

**Status**: Not integrated
**Authentication**: FREE -- no API key required for basic REST; Basic HTTP Auth for enhanced API
**Rate Limits**: Not documented
**Coverage**: Only Penguin Random House titles (~15,000 new titles/year, huge backlist)
**Cost**: Free

#### Endpoints

Basic REST (no auth): `https://reststop.randomhouse.com/resources/`

| Endpoint | Description |
|----------|-------------|
| `GET /resources/titles/{isbn}` | Look up by ISBN |
| `GET /resources/titles?isbn={isbn}` | Search by ISBN |
| `GET /resources/works/{workId}` | Get work details |
| `GET /resources/authors/{authorId}` | Get author details |

Enhanced API (requires registration): `https://api.penguinrandomhouse.com/`

#### Response Fields (Title endpoint)

| Field | Notes |
|-------|-------|
| `isbn` | |
| `isbn10` | |
| `isbn13` | |
| `ean` | |
| `workid` | PRH internal work ID |
| `titleweb` | Display title |
| `titleshort` | Short title |
| `author` | Author name |
| `authorweb` | Display author |
| `division` | Publishing division |
| `imprint` | Publishing imprint (e.g., "Knopf", "Bantam") |
| `formatname` | "Hardcover", "Trade Paperback", etc. |
| `formatcode` | Format code |
| `flapcopy` | **Excellent descriptions** -- jacket flap copy |
| `excerpt` | Book excerpt |
| `authorbio` | Author biography |
| `acmartflap` | Back cover copy |
| `jacketquotes` | Review quotes |
| `themes` | Thematic keywords |
| `onsaledate` | Publication date |
| `pages` | Page count |
| `salestatus` | Current sale status |
| `subjectcategory` | BISAC category codes |
| `agerange` | Target age range |
| `agerangecode` | Age range code |
| `priceusa` | US retail price |
| `pricecanada` | Canadian retail price |
| `rgabout` | Reading group: About the book |
| `rgauthbio` | Reading group: Author bio |
| `rgcopy` | Reading group: Copy |
| `rgdiscussion` | Reading group: Discussion questions |

Parameters: `expandLevel=1` for full details, `start=0&max=25` for pagination.

#### Data Quality Assessment
- **Description**: **EXCELLENT** -- `flapcopy` is the actual jacket copy, highest quality descriptions
- **Format**: Excellent -- `formatname` is definitive
- **Imprint/Publisher**: Best source for this (provides specific imprint, not just "Penguin Random House")
- **Genre**: `subjectcategory` uses BISAC codes (industry standard)
- **Series**: No dedicated series field
- **Coverage**: Only PRH titles, but PRH is the world's largest publisher (~25% of English-language titles)
- **Age Range**: Useful for children's/YA classification

#### Verdict
**Highly recommended for integration**. Free, no API key for basic access, excellent description quality, and covers ~25% of English-language books. The `flapcopy` field alone makes this worth adding. Only downside is publisher-specific coverage.

---

### 2.4 Library of Congress API

**Status**: Not integrated
**Authentication**: None required
**Rate Limits**: Rate-limited but not documented
**Coverage**: US books with copyright registration/CIP data
**Cost**: Free

#### Endpoints

Base URL: `https://www.loc.gov/`

| Endpoint | Description |
|----------|-------------|
| `GET /search/?q={isbn}&fo=json` | Search collections |
| `GET /item/{lccn}/?fo=json` | Item by LCCN |
| `GET /books/?q={isbn}&fo=json` | Search books collection |

Append `&fo=json` to any loc.gov URL for JSON output, or `&fo=yaml` for YAML.

#### Available Fields

Response includes: `title`, `contributors[]`, `date`, `subjects[]`, `call_number`, `description[]`, `format[]`, `language[]`, `location[]`, `medium`, `rights_advisory`, `created_published`, `type`, `online_format`, `original_format`, `partof`, `source_collection`, `display_offsite`, `digital_id`, `library_of_congress_control_number`, `repository`, `id`, `url`, `related_items[]`

#### Data Quality Assessment
- **Subjects**: **LCSH (Library of Congress Subject Headings)** -- gold standard for subject classification
- **Call Numbers**: LC classification numbers
- **Coverage**: Strong for US-published books, especially post-1970s
- **ISBN search**: Not a primary ISBN lookup service; search can be unreliable
- **Bibliographic data**: Authoritative but sparse (title, author, date, subjects)
- **Description**: Minimal -- not a source for book descriptions

#### Verdict
**Low priority**. The LoC API is excellent for authoritative subject headings and classification numbers, but it's not designed as an ISBN lookup service. Integration would be complex for marginal benefit. Better to use Open Library (which incorporates LoC data anyway).

---

### 2.5 WorldCat / OCLC APIs

**Status**: Not integrated
**Authentication**: Varies by endpoint
**Coverage**: 586M+ bibliographic records -- largest library catalog network
**Cost**: Free tier available for some endpoints; full access requires OCLC membership

#### Available Endpoints

**1. WorldCat Entities API (Free/Public)**
- `GET https://id.oclc.org/worldcat/entity/{entityId}` (unauthenticated -- limited fields)
- With free WSKEY: more properties available
- Full access: requires OCLC Meridian subscription

**2. WorldCat Classify API (Free)**
- `GET http://classify.oclc.org/classify2/Classify?isbn={isbn}&summary=true`
- Returns: title, author, most popular DDC/LCC call numbers, holding counts
- Rate limit: 500 requests/day (free), unlimited for OCLC members

**3. WorldCat Search API (Requires WSKEY)**
- `GET https://www.worldcat.org/webservices/catalog/content/isbn/{isbn}?wskey={key}`
- Returns: MARCXML bibliographic record
- Note: v1 was decommissioned April 2024; v2 requires subscription

**4. WorldCat Metadata API 2.0 (Subscription)**
- Full CRUD access to WorldCat records
- Requires institutional OCLC membership

#### Classify API Response Fields
- `title`, `author`
- `owi` (OCLC Work Identifier)
- `holdings` (total library holdings count)
- `eholdings` (electronic holdings count)
- `ddc.mostPopular` (Dewey Decimal most popular classification)
- `lcc.mostPopular` (LC most popular classification)

#### Data Quality Assessment
- **Classification numbers**: **Best source** for DDC/LCC -- aggregated from thousands of libraries
- **Holdings counts**: Useful for popularity/importance metrics
- **Coverage**: Unmatched -- includes virtually every book ever cataloged by a library worldwide
- **Bibliographic quality**: Library-quality MARC records
- **ISBN lookup reliability**: Good, but better with OCLC numbers

#### Verdict
**Moderate priority**. The free Classify API is a good source for DDC/LCC classification numbers and holding counts. Not useful for descriptions, covers, or series data. Worth integrating if classification data matters for the app.

---

### 2.6 CrossRef API

**Status**: Not integrated
**Authentication**: None required (polite pool); API key available
**Rate Limits**: 50 req/sec (polite pool with email in User-Agent), 1-2 req/sec without
**Coverage**: 150M+ DOI records, primarily academic/scholarly
**Cost**: Free

#### Endpoints

Base URL: `https://api.crossref.org/`

- `GET /works?filter=isbn:{isbn}` -- Search works by ISBN
- `GET /works/{doi}` -- Get work by DOI

#### Response Fields (for books)

`DOI`, `title[]`, `author[]` (given, family, affiliation), `publisher`, `type` (book, book-chapter, monograph), `ISBN[]`, `published-print.date-parts`, `published-online.date-parts`, `page`, `subject[]`, `container-title[]`, `edition-number`, `language`, `abstract`, `reference-count`, `references-count`, `is-referenced-by-count` (citation count)

#### Data Quality Assessment
- **Coverage**: Primarily academic books, textbooks, and scholarly monographs. Very poor for fiction, popular non-fiction
- **ISBN handling**: ISBN filter works but publishers format ISBNs inconsistently; may need to try multiple formats
- **Metadata quality**: Excellent for academic works
- **Citation counts**: Unique value -- how many times a book is cited

#### Verdict
**Low priority**. Only useful for academic/scholarly books. Not relevant for fiction, popular non-fiction, or general consumer books.

---

### 2.7 BookBrainz

**Status**: Not integrated
**Authentication**: None required
**Rate Limits**: Not documented
**Coverage**: Very small -- community-curated, early stage
**Cost**: Free (open data)

#### Endpoints

API: `https://api.test.bookbrainz.org/1/` (Note: still test API)

| Endpoint | Description |
|----------|-------------|
| `GET /edition/{bbid}` | Edition by BookBrainz ID |
| `GET /work/{bbid}` | Work by BBID |
| `GET /author/{bbid}` | Author by BBID |
| `GET /search?q={term}&type=edition` | Search |

There is **no direct ISBN lookup endpoint**. You must search and filter results.

#### Data Model
Similar to MusicBrainz: Edition (specific printing) -> Edition Group -> Work -> Author
- Editions have: title, languages, format, pages, publishers, release events, identifiers (ISBN, etc.)
- Works have: title, type, languages, relationships

#### Data Quality Assessment
- **Coverage**: **Very poor** -- small community, limited catalog
- **Quality**: What's there is high quality (MusicBrainz-style curated data)
- **ISBN lookup**: No direct endpoint; requires search + filter
- **Series**: Not a primary feature

#### Verdict
**Not recommended**. Too small a database to provide meaningful coverage improvements. May be worth revisiting in 2-3 years if the project grows.

---

### 2.8 LibraryThing APIs

**Status**: Not integrated
**Authentication**: Developer key required (free)
**Rate Limits**: 10 requests/second for ISBN check API
**Coverage**: 4M+ works cataloged by members
**Cost**: Free

#### Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /api/thingISBN/{isbn}` | Returns other ISBNs of the same work |
| `GET /api/thingTitle/{title}` | Returns ISBNs for a title |
| `GET /talpa/search.json?query={term}` | Talpa Search API (recent) |

#### Critical Limitation
**LibraryThing does NOT provide bibliographic metadata via API**. ThingISBN only returns related ISBNs (other editions). There is no way to get title, author, description, etc. through their APIs.

The Talpa Search API returns search results with ISBNs and UPCs but limited metadata.

#### Verdict
**Not useful** for metadata enrichment. The thingISBN API could theoretically be used to find alternative ISBNs when a lookup fails, but this is a niche use case.

---

### 2.9 Goodreads

**Status**: API shut down December 2020
**Authentication**: N/A
**Cost**: N/A

#### Current State
- No new API keys issued since December 2020
- Existing keys have been deactivated
- No official way to access Goodreads data programmatically

#### Workarounds
1. **Apify scrapers**: Third-party scraping services (paid) that scrape Goodreads pages
2. **BiblioReads**: Open-source alternative Goodreads frontend that scrapes data
3. **RSS feeds**: `https://www.goodreads.com/user/updates_rss/{userId}` -- very limited data
4. **Widgets**: Some widget endpoints may still return limited data
5. **Web scraping**: Technically possible but fragile and against TOS

#### Verdict
**Dead end**. No legitimate way to access Goodreads data. The Hardcover API is the recommended replacement for social book metadata.

---

### 2.10 The StoryGraph

**Status**: No official API
**Authentication**: N/A
**Coverage**: Growing user base, strong on genre/mood classification
**Cost**: N/A

#### Current State
An official API is on StoryGraph's public roadmap but has not been released as of March 2026.

#### Unofficial Options
- Python scraping package (`storygraph-api` on PyPI)
- Netlify Functions-based scraper (GitHub: `xdesro/storygraph-api`)
- CSV export from user accounts

#### Verdict
**Not actionable**. Wait for official API. StoryGraph's genre/mood/pace classifications would be valuable if an API becomes available.

---

### 2.11 Kobo API

**Status**: No public book metadata API
**Authentication**: N/A

Kobo has internal APIs for device sync (`/v1/library/sync`, `/v1/library/{id}/metadata`) but these are for authenticated Kobo device users only. There is no public developer API for book metadata.

#### Verdict
**Not available**. No public endpoint exists.

---

### 2.12 HarperCollins OpenBook API

**Status**: Likely defunct
**Authentication**: Unknown

HarperCollins launched an "OpenBook API" beta in 2012 that provided author/title/preview data. The developer portal and documentation appear to be offline or unmaintained as of 2026. ProgrammableWeb still has a listing but the actual API endpoints are unverified.

#### Verdict
**Not available**. Appears to be abandoned.

---

### 2.13 Gutendex (Project Gutenberg)

**Status**: Not integrated
**Authentication**: None required
**Rate Limits**: Not documented
**Coverage**: ~73,000 public domain ebooks
**Cost**: Free

#### Endpoint

`GET https://gutendex.com/books/?search={isbn_or_title}`

#### Response Fields
`id`, `title`, `authors[]` (name, birth_year, death_year), `subjects[]`, `bookshelves[]`, `languages[]`, `copyright`, `media_type`, `formats{}` (download URLs by MIME type), `download_count`, `translators[]`, `summaries[]`

#### Verdict
**Not useful**. Only covers public domain works (pre-1928 mostly). No ISBNs in the database. Could theoretically match by title for very old books, but the coverage overlap is minimal.

---

### 2.14 Bowker / Books In Print

**Status**: Not integrated
**Authentication**: Required (enterprise subscription)
**Coverage**: 60M+ titles, 50M+ cover images
**Cost**: Enterprise pricing (thousands per year)

Bowker is the **gold standard** for book metadata (they issue US ISBNs), but pricing is prohibitively expensive for a consumer app. Their "Data On Demand" RESTful API provides comprehensive bibliographic metadata including:
- Complete bibliographic data
- BISAC subject codes
- Cover images
- Tables of contents, summaries, author bios, first chapters, reviews
- Price and availability

#### Verdict
**Not practical** for a consumer app. Enterprise pricing. The data flows downstream to services like Google Books and ISBNdb anyway.

---

### 2.15 Librario

**Status**: Pre-alpha
**Authentication**: Bearer token
**Coverage**: Aggregates Google Books + ISBNdb + Hardcover
**Cost**: Unknown

A new Go-based API that aggregates metadata from multiple sources with intelligent merging. Endpoint: `https://api.librario.dev/v1/book/{isbn}`

#### Verdict
**Too early**. Pre-alpha, moved to SourceHut, no stable documentation. The concept is exactly what LibraryIQ already does internally. Interesting to watch but not ready for production use.

---

### 2.16 OverDrive Metadata API

**Status**: Not integrated
**Authentication**: API key required (library/partner program)
**Coverage**: Digital library content (ebooks, audiobooks)
**Cost**: Requires partner agreement

Endpoint: `GET https://api.overdrive.com/v1/collections/{collectionId}/products/{productId}/metadata`

Returns: author, title, genre, synopses, reviews, `otherFormatIdentifiers` (print ISBNs), format details.

#### Verdict
**Not practical**. Requires institutional partnership with OverDrive. Not available to individual developers.

---

## PART 3: SPECIAL FOCUS AREAS

---

### 3.1 Series Information

Series data is the hardest metadata to obtain reliably. Here's a ranked assessment:

| Source | Series Name | Position | Quality | Notes |
|--------|:-:|:-:|---------|-------|
| **Hardcover.app** | YES | YES (float) | **BEST** | Curated data, handles "1.5" positions for novellas |
| **Google Books seriesInfo** | YES (indirect) | YES | Good | `bookDisplayNumber` + `volumeSeries[].orderNumber`, but not populated for all series books (~40%) |
| **Barnes & Noble** | YES | YES | Good | From JSON-LD `isPartOf` structured data |
| **Amazon** | YES | YES | Good | From title parentheticals and product details |
| **Target** | YES | YES | Moderate | From Specifications section |
| **Open Library** | YES | NO | Poor | `series[]` field exists but rarely populated, no position |
| **ISBNdb** | NO | NO | N/A | No dedicated series field at all |
| **Google Books categories** | NO | NO | N/A | No series info in categories |
| **iTunes/Apple Books** | NO | NO | N/A | No series field |

**Recommendation**: Hardcover is the primary series source. Add Google Books `seriesInfo` parsing as a free secondary source. Amazon/B&N scrapers already extract this well as tertiary sources.

---

### 3.2 Edition / Format Detection

| Source | Format Field | Quality | Values |
|--------|-------------|---------|--------|
| **Amazon** | Product Details | **Best** | "Hardcover", "Paperback", "Mass Market Paperback", "Kindle Edition", "Audible Audiobook", "Board Book", "Library Binding", "Spiral-bound" |
| **Barnes & Noble** | `bookFormat` / Details | Excellent | "Paperback", "Hardcover", "NOOK Book", "Audio CD" |
| **ISBNdb** | `binding` + `edition` | Good | "Hardcover", "Paperback" + "First Edition", "Revised" |
| **Hardcover** | `edition_format` | Good | Not currently queried -- should add |
| **Open Library** | `physical_format` | Moderate | "Paperback", "Hardcover", etc. |
| **Target** | Specifications | Moderate | Format field in specs section |
| **PRH API** | `formatname` | Excellent | Definitive for PRH titles |
| **Google Books** | `printType` | Poor | Only "BOOK" or "MAGAZINE" |

**Recommendation**: Amazon and B&N scrapers are already the best format sources. Add `edition_format` to the Hardcover query. Open Library's `edition_name` field provides edition info ("First Edition", "Movie Tie-in Edition") that could supplement.

---

### 3.3 Genre / Subject Classification

| Source | Field | System | Quality | Notes |
|--------|-------|--------|---------|-------|
| **Open Library** | `subjects[]`, `subject_places[]`, `subject_people[]`, `subject_times[]` | LCSH-derived | **Best free** | Most comprehensive, includes place/person/time facets |
| **Amazon** | Browse nodes | Amazon taxonomy | Excellent | Granular categories but requires scraping |
| **ISBNdb** | `subjects[]`, `dewey_decimal` | Mixed | Good | Dewey + subject keywords |
| **Hardcover** | `cached_tags` | User-generated | Good | Social tags, similar to Goodreads |
| **Google Books** | `categories[]` | Google taxonomy | Moderate | Broad categories, not granular |
| **iTunes** | `genres[]`, `genreIds[]` | Apple taxonomy | Moderate | Ebook-focused |
| **PRH API** | `subjectcategory` | **BISAC** | Excellent | Industry standard classification |
| **B&N** | Product details | BN taxonomy | Moderate | |
| **WorldCat Classify** | DDC/LCC | Library standard | Excellent | Authoritative classification numbers |

**Recommendation**: Open Library `subjects[]` is the best free source. Add `genres[]` from iTunes responses (currently ignored). If integrating PRH, their BISAC codes are the publishing industry standard. Hardcover's `cached_tags` adds social/user-generated classifications.

---

### 3.4 Description Quality

| Source | Quality | Length | Notes |
|--------|---------|--------|-------|
| **PRH API** | **Best** | Long | `flapcopy` is actual jacket copy; `excerpt` provides book excerpts |
| **Amazon** | Excellent | Long | Publisher-provided, often includes reviews/quotes |
| **Barnes & Noble** | Excellent | Long | Publisher-provided |
| **Google Books** | Good | Medium | HTML-formatted, may include basic tags |
| **Open Library (Work)** | Variable | Variable | Community-contributed, can range from nothing to excellent |
| **ISBNdb** | Moderate | Short-Medium | `synopsis` + `overview` when available |
| **iTunes** | Good | Medium | HTML-formatted |
| **Hardcover** | Good | Medium | Often sourced from publishers |
| **Target** | Moderate | Medium | Often truncated |

**Recommendation**: If integrating PRH, use `flapcopy` as the top-priority description source. Otherwise, Amazon scraping provides the best descriptions, followed by Google Books and B&N.

---

### 3.5 Multi-Language Support

| Source | Non-English Coverage | Language Detection | Notes |
|--------|---------------------|-------------------|-------|
| **ISBNdb** | **Best** | `language` field | 108M titles across all languages |
| **WorldCat** | Excellent | MARC language codes | International library catalog |
| **Google Books** | Good | `language` (ISO 639-1) | Good international coverage |
| **Open Library** | Good | `languages[]` references | Community-contributed international data |
| **Amazon** | Moderate | Per-marketplace | amazon.co.jp, amazon.de etc. have local catalogs |
| **Hardcover** | Poor | `language_id` | English-focused |
| **PRH API** | Poor | N/A | English-language publisher |
| **iTunes** | Moderate | N/A | Varies by regional store |

**Recommendation**: Google Books and ISBNdb are the best sources for non-English books. Open Library has good international coverage from library data imports.

---

### 3.6 Self-Published / Indie Book Coverage

| Source | Indie Coverage | Notes |
|--------|---------------|-------|
| **Amazon** | **Best** | KDP self-published books are all on Amazon |
| **ISBNdb** | Very Good | Aggregates from many sources including self-pub |
| **Google Books** | Good | Google Play Books accepts self-pub |
| **Open Library** | Moderate | Community can add any book |
| **iTunes** | Moderate | Apple Books accepts self-pub |
| **Hardcover** | Poor-Moderate | Growing but curated focus |
| **B&N** | Moderate | B&N Press self-pub titles |
| **PRH API** | Poor | Only PRH imprints |

**Recommendation**: Amazon scraping is the best source for indie/self-pub books, followed by ISBNdb. Google Books is a good free option.

---

## PART 4: PRACTICAL RECOMMENDATIONS

---

### 4.1 Optimal Source Priority by Field

Based on the analysis, here is the recommended source priority for each metadata field:

| Field | Priority 1 | Priority 2 | Priority 3 | Priority 4 |
|-------|-----------|-----------|-----------|-----------|
| **Title** | Google Books | Hardcover | Open Library | Amazon |
| **Author** | Google Books | Hardcover | Open Library | Amazon |
| **Description** | Amazon | Google Books | B&N | Open Library (Work) |
| **Page Count** | Google Books | Hardcover | Open Library | ISBNdb |
| **Publisher** | Google Books | Open Library | Hardcover | PRH API |
| **Published Date** | Google Books | Open Library | Hardcover | Amazon |
| **Series Name** | Hardcover | Google Books seriesInfo | Amazon | B&N |
| **Series Position** | Hardcover | Google Books seriesInfo | Amazon | B&N |
| **Format** | Amazon | B&N | Hardcover edition_format | Open Library |
| **Genre/Subjects** | Open Library subjects | Amazon | Hardcover cached_tags | Google Books categories |
| **Language** | Google Books | Open Library | ISBNdb | Amazon |
| **Edition Info** | Amazon | Open Library edition_name | ISBNdb | B&N |
| **Cover Image** | iTunes (high-res) | Google Books | Open Library | Hardcover |
| **ISBN-10** | Google Books | Open Library | Amazon | B&N |
| **ASIN** | Amazon | - | - | - |
| **DDC/LCC** | Open Library | WorldCat Classify | - | - |

### 4.2 Biggest Improvements for Least Effort

Ranked by impact/effort ratio:

#### 1. Parse Google Books `seriesInfo` (HIGHEST PRIORITY)
- **Effort**: Minimal -- add 1 data class, update VolumeInfo model
- **Impact**: Free series data for ~40% of series books
- **Cost**: Free, no new API calls needed

#### 2. Add `subtitle` from Google Books
- **Effort**: Trivial -- add one field to VolumeInfo model
- **Impact**: Cleaner title display, separate subtitle handling
- **Cost**: Free

#### 3. Parse `genres[]` from iTunes responses
- **Effort**: Trivial -- add one field to ITunesResult model
- **Impact**: Better genre data for ebooks
- **Cost**: Free

#### 4. Add `edition_format` and `cached_tags` to Hardcover query
- **Effort**: Small -- modify GraphQL query string
- **Impact**: Format detection and genre tags
- **Cost**: Free

#### 5. Integrate Penguin Random House API
- **Effort**: Moderate -- new service class, ~100 lines
- **Impact**: Excellent descriptions (`flapcopy`) and BISAC genre codes for ~25% of English books
- **Cost**: Free, no API key needed

#### 6. Fetch full volume via Google Books `/volumes/{id}` endpoint
- **Effort**: Moderate -- add second API call after initial search
- **Impact**: Higher-resolution images, more complete metadata, series info
- **Cost**: Free, uses same quota

#### 7. Add Open Library `edition_name`, `genres[]`, `dewey_decimal_class[]`, `lc_classifications[]`
- **Effort**: Small -- add fields to existing model
- **Impact**: Edition info, genre data, library classifications
- **Cost**: Free

#### 8. Integrate ISBNdb (if budget allows)
- **Effort**: Moderate -- new service class, API key management
- **Impact**: Coverage for obscure ISBNs, binding/edition data, dimensions
- **Cost**: $14.95-$74.95/month

### 4.3 Not Worth Integrating

| Source | Reason |
|--------|--------|
| BookBrainz | Too small a database |
| LibraryThing | No metadata API (only ISBN cross-reference) |
| Goodreads | API permanently shut down |
| StoryGraph | No API yet |
| Kobo | No public API |
| HarperCollins | API appears defunct |
| Gutendex | Only public domain books |
| Bowker | Enterprise pricing |
| OverDrive | Requires institutional partnership |
| CrossRef | Academic books only |
| Librario | Pre-alpha, unstable |

---

## APPENDIX A: Quick Reference -- API Endpoints

```
Google Books (search):     GET https://www.googleapis.com/books/v1/volumes?q=isbn:{isbn}
Google Books (details):    GET https://www.googleapis.com/books/v1/volumes/{volumeId}
Open Library (edition):    GET https://openlibrary.org/isbn/{isbn}.json
Open Library (work):       GET https://openlibrary.org/works/{OL_ID}.json
Open Library (search):     GET https://openlibrary.org/search.json?isbn={isbn}
Open Library (covers):     GET https://covers.openlibrary.org/b/isbn/{isbn}-L.jpg
Hardcover:                 POST https://api.hardcover.app/v1/graphql
iTunes (lookup):           GET https://itunes.apple.com/lookup?isbn={isbn}
iTunes (search):           GET https://itunes.apple.com/search?term={term}&media=ebook
ISBNdb:                    GET https://api2.isbndb.com/book/{isbn}
PRH (basic):               GET https://reststop.randomhouse.com/resources/titles/{isbn}
WorldCat Classify:         GET http://classify.oclc.org/classify2/Classify?isbn={isbn}&summary=true
CrossRef:                  GET https://api.crossref.org/works?filter=isbn:{isbn}
Library of Congress:       GET https://www.loc.gov/books/?q={isbn}&fo=json
Amazon (scrape):           GET https://www.amazon.com/dp/{isbn}
Barnes & Noble (scrape):   GET https://www.barnesandnoble.com/w/?ean={isbn}
Target (scrape):           GET https://www.target.com/s?searchTerm={isbn}
```

## APPENDIX B: Authentication Summary

| Source | Auth Type | Key Required | Notes |
|--------|-----------|:------------:|-------|
| Google Books | API Key (optional) | Optional | Higher quota with key |
| Open Library | None | NO | |
| Hardcover | Bearer token | YES | Free account |
| iTunes | None | NO | |
| ISBNdb | Bearer token | YES | Paid subscription |
| PRH Basic | None | NO | |
| PRH Enhanced | HTTP Basic Auth | YES | Registration required |
| WorldCat Classify | None | NO | |
| CrossRef | None (polite pool) | Optional | Email in User-Agent recommended |
| Library of Congress | None | NO | |
| Amazon PA-API | AWS-style HMAC | YES | Requires Associates account with sales |

## APPENDIX C: Rate Limits Summary

| Source | Limit | Notes |
|--------|-------|-------|
| Google Books | 1K/day (no key), 10K/day (with key) | Per project |
| Open Library | ~100/min (unofficial) | Can get 429 if aggressive |
| Hardcover | ~1/sec recommended | Unofficial |
| iTunes | ~20/min | Unofficial |
| ISBNdb | 1/sec (Basic), 3/sec (Premium), 5/sec (Pro) | Per plan |
| PRH | Undocumented | |
| WorldCat Classify | 500/day (free) | |
| CrossRef | 50/sec (polite pool) | Requires email in User-Agent |
| Library of Congress | Undocumented, rate-limited | |
| Amazon scraping | Self-throttle | Gets blocked if aggressive |
| B&N scraping | Self-throttle | |
| Target scraping | Self-throttle | |
