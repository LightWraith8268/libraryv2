# Comprehensive Book Metadata Sources Encyclopedia

## Overview

This document catalogs **every known source** of book metadata, covers, and bibliographic data — going far beyond mainstream APIs. It covers 60+ sources organized by category: production-ready APIs, national libraries, academic/scholarly, regional/language-specific, niche/specialty, aggregators, standards, and emerging/future sources.

---

## PART 1: PRODUCTION-READY APIs (Already Integrated or Immediately Usable)

### 1.1 Already Integrated in LibraryIQ (13 sources)

| Source | Type | Cost | Notes |
|--------|------|------|-------|
| Google Books | REST API | Free | 1K/day no key, 10K with key |
| Open Library | REST API | Free | 20M+ titles, community-curated |
| Hardcover.app | GraphQL | Free | Best series data, curated |
| iTunes/Apple Books | REST API | Free | High-res covers (600x600) |
| Penguin Random House | REST API | Free | Best descriptions (flapcopy) |
| Amazon | Web scraping | Free | Largest catalog, fragile |
| Barnes & Noble | Web scraping | Free | Good JSON-LD structured data |
| Target | Web scraping | Free | Specifications section |

### 1.2 Ready to Integrate (High Value, Not Yet Used)

#### ISBNdb
- **URL**: `https://api2.isbndb.com/book/{isbn}`
- **Auth**: Bearer token (paid)
- **Cost**: $14.95/mo (Basic), $29.95/mo (Premium), $74.95/mo (Pro)
- **Coverage**: 108M+ titles — largest single database
- **Unique fields**: `binding`, `edition`, `dimensions`, `weight`, `msrp`, `dewey_decimal`, `synopsys`, `overview`
- **Rate limits**: 1/sec (Basic), 3/sec (Premium), 5/sec (Pro)
- **Verdict**: Best for obscure ISBNs, binding/edition data, and dimensions. Worth the $15/mo for completeness.

#### WorldCat Classify API
- **URL**: `http://classify.oclc.org/classify2/Classify?isbn={isbn}&summary=true`
- **Auth**: None
- **Cost**: Free (500 req/day)
- **Coverage**: 586M+ bibliographic records
- **Unique fields**: `ddc.mostPopular` (Dewey), `lcc.mostPopular` (LC), `holdings` count, `eholdings` count
- **Verdict**: Best source for DDC/LCC classification numbers. Holding counts are a unique popularity metric.

#### New York Times Books API
- **URL**: `https://api.nytimes.com/svc/books/v3/`
- **Auth**: API key (free registration)
- **Cost**: Free
- **Rate limits**: 4,000/day, 10/min
- **Endpoints**:
  - `GET /reviews.json?isbn={isbn}` — Book reviews by ISBN
  - `GET /lists/best-sellers/history.json?isbn={isbn}` — Bestseller history
  - `GET /lists.json?list={list-name}` — Current bestseller lists
- **Unique fields**: NYT review text, bestseller rank history, weeks on list
- **Verdict**: **Highly recommended**. Free, gives unique review/bestseller data no other source has. A book's NYT bestseller status is valuable metadata.

#### Wikidata SPARQL
- **URL**: `https://query.wikidata.org/sparql`
- **Auth**: None
- **Cost**: Free (CC0)
- **Coverage**: Structured data for millions of books
- **Query**: `SELECT ?book ?isbn WHERE { ?book wdt:P212 "{isbn}" }` (ISBN-13 lookup)
- **Unique fields**: Linked author/publisher entities, genre classifications, awards, series relationships, translations, influenced-by relationships
- **Verdict**: Best for linked data relationships (author → other works, awards, series). Free SPARQL endpoint. Query complexity is the main barrier.

---

## PART 2: NATIONAL LIBRARY APIs

### 2.1 Library of Congress (USA)
- **URL**: `https://www.loc.gov/books/?q={isbn}&fo=json`
- **Auth**: None
- **Cost**: Free
- **Coverage**: US books with CIP data
- **Unique fields**: LCSH subject headings (gold standard), LC classification, call numbers
- **Z39.50**: Restored access in Feb 2026 to FOLIO Catalog
- **Verdict**: Low priority for consumer app. LCSH subjects are authoritative but Open Library already incorporates LoC data.

### 2.2 British Library (UK)
- **URL**: `http://bnb.data.bl.uk/` (Linked Open Data)
- **Auth**: None
- **Cost**: Free (CC0 for metadata)
- **Coverage**: 14M+ catalogue records, BNB (British National Bibliography)
- **Formats**: RDF, SPARQL endpoint, MARC21, Dublin Core
- **Unique fields**: BNB number, UK Dewey, British publication data
- **Verdict**: Useful for UK-published books. SPARQL endpoint available but complex.

### 2.3 Deutsche Nationalbibliothek / DNB (Germany)
- **URL**: `https://services.dnb.de/sru/dnb` (SRU)
- **Auth**: None
- **Cost**: Free (CC0 for all bibliographic data)
- **Coverage**: All German-language publications (legal deposit)
- **Formats**: MARC21, MARCXML, RDF, BIBFRAME
- **Unique fields**: German subject headings (GND), DDC numbers, German-language metadata
- **Calibre plugin**: `calibre-dnb` fetches metadata and covers from DNB
- **Verdict**: Essential for German-language books. CC0 license is excellent. SRU protocol is well-documented.

### 2.4 Bibliothèque nationale de France / BnF (France)
- **URL**: `https://catalogue.bnf.fr/api/` and `https://gallica.bnf.fr/SRU`
- **Auth**: None
- **Cost**: Free
- **Coverage**: French legal deposit — all French publications
- **Formats**: SRU (CQL query language), JSON, XML, RDF
- **Data portal**: `data.bnf.fr` — linked open data for authors, works, subjects
- **Unique fields**: French subject headings (RAMEAU), French-specific publication data
- **Verdict**: Essential for French-language books. Rich linked data via data.bnf.fr.

### 2.5 National Diet Library / NDL (Japan)
- **URL**: `https://ndlsearch.ndl.go.jp/api/` (multiple endpoints)
- **Auth**: None
- **Cost**: Free
- **Coverage**: All Japanese publications (legal deposit) + 30M+ metadata records via Japan Search
- **Formats**: SRU, OpenSearch, OAI-PMH, JSON, RDF
- **Unique fields**: NDL classification, Japanese subject headings (NDLSH)
- **Verdict**: Essential for Japanese books. Japan Search aggregates 30M+ records from multiple Japanese institutions.

### 2.6 CiNii Books (Japan — Academic)
- **URL**: `https://ci.nii.ac.jp/ncid/{ncid}.json`
- **Auth**: None
- **Cost**: Free (CC-BY 4.0)
- **Coverage**: Academic and research library holdings across Japan (NACSIS-CAT)
- **Formats**: JSON-LD, RDF, OpenSearch
- **Unique fields**: Japanese library holdings data, NACSIS catalog numbers
- **Verdict**: Good supplementary source for Japanese academic books.

### 2.7 OpenBD (Japan — Publishing)
- **URL**: `https://api.openbd.jp/v1/get?isbn={isbn}`
- **Auth**: None
- **Cost**: Free
- **Coverage**: Japanese commercial publications via JPRO (Japan Publishing Organization for Information Infrastructure)
- **Unique fields**: Japanese publisher-provided metadata, cover images, book descriptions
- **Verdict**: **Best source for Japanese commercial book metadata**. Free, simple REST API, publisher-curated data.

### 2.8 National Library of Korea / KOLIS-NET
- **URL**: `https://www.nl.go.kr/` (KOLIS-NET search)
- **Auth**: None for search
- **Cost**: Free
- **Coverage**: 8.8M+ bibliographic records, 46.8M+ holdings records
- **Unique fields**: Korean subject classifications, Korean publication data
- **Verdict**: Good for Korean books. Also see Aladin API below.

### 2.9 Aladin Open API (Korea — Commercial)
- **URL**: `https://www.aladin.co.kr/ttb/api/` (various endpoints)
- **Auth**: API key (free registration)
- **Cost**: Free
- **Coverage**: Korean commercial books (Aladin is Korea's largest online bookstore)
- **Fields**: Author, publisher, ISBN, publication date, cover image, price, category
- **Verdict**: Best practical source for Korean book metadata. Used by the popular Korean Book Search Obsidian plugin.

### 2.10 Rakuten Books API (Japan — Commercial)
- **URL**: `https://openapi.rakuten.co.jp/services/api/BooksBook/Search/20170404`
- **Auth**: Application ID (free registration)
- **Cost**: Free
- **Coverage**: Japanese commercial books sold on Rakuten
- **Fields**: Title, author, publisher, ISBN, price, cover, sales rank, review count
- **Verdict**: Good commercial source for Japanese books with sales/pricing data.

---

## PART 3: ACADEMIC & SCHOLARLY SOURCES

### 3.1 CrossRef
- **URL**: `https://api.crossref.org/works?filter=isbn:{isbn}`
- **Auth**: None (polite pool with email)
- **Cost**: Free
- **Coverage**: 150M+ DOI records, primarily academic
- **Rate limits**: 50 req/sec with email in User-Agent
- **Unique fields**: Citation count (`is-referenced-by-count`), DOI, edition number
- **Verdict**: Academic books only. Citation counts are unique value.

### 3.2 Semantic Scholar (S2AG)
- **URL**: `https://api.semanticscholar.org/graph/v1/paper/`
- **Auth**: None (rate-limited) or API key
- **Cost**: Free
- **Coverage**: 225M+ papers, includes books/monographs
- **Rate limits**: 1000 req/sec unauthenticated (shared)
- **Unique fields**: TLDR summaries, citation velocity, influential citations, SPECTER embeddings, fields of study
- **Verdict**: Niche — academic/scholarly books only. AI-generated summaries are unique.

### 3.3 OpenAlex
- **URL**: `https://api.openalex.org/works?filter=ids.isbn:{isbn}`
- **Auth**: None
- **Cost**: Free (CC0)
- **Coverage**: 240M+ works (replaced Microsoft Academic Graph)
- **Unique fields**: Concept tags, citation counts, open access status, institutional affiliations
- **Verdict**: Better than CrossRef for open-access academic works. Completely free, CC0 licensed.

### 3.4 Springer Nature API
- **URL**: `https://api.springernature.com/openaccess/json?q=isbn:{isbn}`
- **Auth**: API key (free registration)
- **Cost**: Free tier (100 hits/min)
- **Coverage**: Springer Nature publications (books, chapters, journals)
- **Verdict**: Publisher-specific. Only useful for Springer Nature titles.

### 3.5 Elsevier Scopus API
- **URL**: `https://api.elsevier.com/content/search/scopus?query=ISBN({isbn})`
- **Auth**: API key (free for academic non-commercial use)
- **Coverage**: Elsevier/Scopus indexed publications
- **Verdict**: Publisher-specific. Only for Elsevier academic books.

### 3.6 DBLP (Computer Science)
- **URL**: `https://dblp.org/search/publ/api?q={query}&format=json`
- **Auth**: None
- **Cost**: Free
- **Coverage**: Computer science bibliography
- **Verdict**: Extremely niche — CS textbooks and proceedings only.

---

## PART 4: DIGITAL LIBRARIES & ARCHIVES

### 4.1 Internet Archive / Open Library
- **Metadata API**: `https://archive.org/metadata/{identifier}`
- **Search API**: `https://archive.org/advancedsearch.php?q=isbn:{isbn}&output=json`
- **Auth**: None
- **Cost**: Free
- **Coverage**: 41M+ items including books
- **OPDS Feed**: `https://bookserver.archive.org/catalog/`
- **Unique fields**: Scan information, lending availability, full-text availability
- **Verdict**: Already using Open Library. The Internet Archive metadata API adds scan/lending data.

### 4.2 HathiTrust
- **URL**: `https://catalog.hathitrust.org/api/volumes/full/isbn/{isbn}.json`
- **Auth**: None
- **Cost**: Free
- **Coverage**: 17.8M+ volumes from major research libraries
- **Unique fields**: Rights status, volume/item-level data, MARC source records, vernacular (foreign language) titles
- **Verdict**: Good for verifying bibliographic data (library-quality MARC records). Free REST API by ISBN.

### 4.3 Project Gutenberg / Gutendex
- **URL**: `https://gutendex.com/books/?search={query}`
- **Auth**: None
- **Cost**: Free
- **Coverage**: 73,000+ public domain ebooks
- **Verdict**: Only public domain works. No ISBNs.

### 4.4 LibriVox
- **URL**: `https://librivox.org/api/feed/audiobooks/?title={title}&format=json`
- **Auth**: None
- **Cost**: Free
- **Coverage**: Public domain audiobooks
- **Verdict**: Niche — public domain audiobooks only. Could supplement audiobook metadata.

---

## PART 5: COVER IMAGE SOURCES

### 5.1 Dedicated Cover APIs

| Source | URL Pattern | Auth | Quality | Notes |
|--------|-----------|------|---------|-------|
| Open Library Covers | `covers.openlibrary.org/b/isbn/{isbn}-L.jpg` | None | Variable | `?default=false` for 404 on missing |
| Google Books | Via volumeInfo.imageLinks | None | Medium | `zoom=0` for full size |
| iTunes/Apple Books | Via lookup/search endpoints | None | High (600x600) | Best free high-res source |
| Amazon | Product page scraping | None | High | Fragile |
| Hardcover | Via GraphQL response | Bearer | High | Curated |

### 5.2 Bookcover-api (Aggregator)
- **URL**: `https://github.com/w3slley/bookcover-api`
- **Type**: Self-hosted Go service
- **Sources**: Scrapes multiple sites for covers by title/author/ISBN
- **Verdict**: Interesting concept but requires self-hosting.

### 5.3 Bookcovers (NPM)
- **URL**: `https://www.npmjs.com/package/bookcovers`
- **Type**: Node.js library
- **Sources**: Federated search of Amazon, Google Books, Open Library
- **Verdict**: Useful reference for cover URL patterns.

### 5.4 Syndetics (ProQuest)
- **URL**: `https://proquest.syndetics.com/`
- **Auth**: Library subscription required
- **Coverage**: 8M+ data elements, 4.5M+ ISBNs
- **Content**: Covers, reviews, tables of contents, summaries, author notes, awards
- **Verdict**: Institutional only. Not available for consumer apps.

### 5.5 Edelweiss+
- **URL**: `https://www.edelweiss.plus/`
- **Auth**: Publisher/retailer account
- **Coverage**: Major publisher catalogs
- **Image specs**: 1000px width, 2:3 ratio, JPG/PNG/GIF
- **Verdict**: Not publicly accessible. Publisher/retailer platform.

---

## PART 6: AUDIOBOOK METADATA

### 6.1 Audible (Unofficial)
- **URL**: `https://api.audible.com/1.0/catalog/products/{asin}`
- **Auth**: Audible device registration
- **Fields**: Title, author, narrator(s), runtime, chapters, series, release date, ratings
- **Rate limits**: Unknown (unofficial)
- **Verdict**: Best audiobook metadata source. Unofficial API documented at `audible.readthedocs.io`. Requires device auth.

### 6.2 Spotify Audiobooks API
- **URL**: `https://api.spotify.com/v1/audiobooks/{id}`
- **Auth**: OAuth2
- **Cost**: Free (developer account)
- **Fields**: Name, publisher, narrators, cover art (multiple sizes), languages, chapters
- **Markets**: US, UK, Canada, Ireland, NZ, Australia only
- **Limitation**: No ISBN lookup — must use Spotify ID
- **Verdict**: Supplementary only. No ISBN-based search.

### 6.3 Audiobookshelf
- **URL**: `https://api.audiobookshelf.org/`
- **Type**: Self-hosted server API
- **Metadata providers**: Audible, Google, iTunes, audiobookcovers
- **Verdict**: Reference implementation for audiobook metadata aggregation.

---

## PART 7: COMMUNITY & SOCIAL BOOK DATA

### 7.1 Goodreads
- **Status**: API permanently shut down (December 2020)
- **Workarounds**: Apify scrapers (paid), BiblioReads (OSS frontend), RSS feeds (limited)
- **Verdict**: Dead end for API access.

### 7.2 The StoryGraph
- **Status**: No official API (on public roadmap)
- **Unique data**: Genre/mood/pace classifications, content warnings (author-approved + community)
- **Verdict**: Wait for official API. Content warning data is unique and valuable.

### 7.3 Hardcover.app
- **Status**: Integrated
- **Unique data**: Curated series data, cached_tags (user-generated), reading stats
- **Verdict**: Already using. Best free series data source.

### 7.4 BookBrainz
- **Status**: Pre-alpha, very small database
- **URL**: `https://api.test.bookbrainz.org/1/`
- **Verdict**: Not useful yet. Revisit in 2-3 years.

### 7.5 LibraryThing
- **URL**: `https://www.librarything.com/api/thingISBN/{isbn}`
- **Auth**: Developer key (free)
- **Coverage**: 4M+ works
- **Limitation**: Only returns related ISBNs — NO bibliographic metadata
- **Verdict**: Only useful for finding alternative editions of a book.

### 7.6 DoesTheDogDie
- **URL**: `https://www.doesthedogdie.com/`
- **Coverage**: Crowdsourced content/trigger warnings for books, movies, TV
- **API**: Unofficial scraping only
- **Verdict**: Unique content warning data. No public API.

---

## PART 8: CONTENT WARNING & REVIEW DATA

### 8.1 New York Times Books API
- **URL**: `https://api.nytimes.com/svc/books/v3/`
- **Auth**: API key (free)
- **Rate limits**: 4,000/day, 10/min
- **Unique data**: Professional book reviews, bestseller list history, weeks-on-list
- **Verdict**: **Highly recommended**. Unique review and bestseller data.

### 8.2 Book Trigger Warnings API
- **URL**: Referenced on Devpost (hackathon project)
- **Source**: Book Trigger Warnings Wiki
- **Verdict**: Interesting concept but likely unmaintained.

---

## PART 9: PUBLISHER-SPECIFIC APIs

### 9.1 Penguin Random House (Integrated)
- **URL**: `https://reststop.randomhouse.com/resources/titles/{isbn}`
- **Coverage**: ~25% of English books
- **Best for**: Descriptions (flapcopy), format, imprint, BISAC codes

### 9.2 HarperCollins OpenBook API
- **Status**: Appears defunct (launched 2012, unverified)

### 9.3 Springer Nature (Academic)
- See Part 3 above

### 9.4 Elsevier (Academic)
- See Part 3 above

---

## PART 10: AGGREGATORS & META-SOURCES

### 10.1 Librario
- **URL**: `https://api.librario.dev/v1/book/{isbn}`
- **Sources**: Google Books + ISBNdb + Hardcover (+ Goodreads scraping planned)
- **Status**: Pre-alpha, unstable, rewriting database
- **Cost**: Will be paid eventually (free tier planned)
- **Verdict**: Interesting but too early. Does what LibraryIQ already does internally.

### 10.2 Bowker / Books In Print
- **Cost**: Enterprise pricing (thousands/year)
- **Coverage**: 60M+ titles, 50M+ covers
- **Verdict**: Gold standard but prohibitively expensive.

### 10.3 Ingram Content Group
- **URL**: ipage platform, Web Service API
- **Auth**: Business account required
- **Cost**: Paid
- **Coverage**: Data from 30,000+ publishers
- **Verdict**: Requires business relationship. Not for consumer apps.

### 10.4 Anna's Archive (Shadow Library Metadata)
- **Coverage**: Aggregates Open Library, WorldCat, Google Books, Z-Library, LibGen, Sci-Hub metadata
- **Data**: ElasticSearch + MariaDB dumps available
- **License**: CC0 for metadata
- **Verdict**: Largest aggregated metadata set. Legal gray area for the source material, but metadata itself is factual/non-copyrightable. Download-only (no REST API).

---

## PART 11: STANDARDS & PROTOCOLS

### 11.1 ONIX for Books
- **What**: XML standard for publisher-to-retailer metadata exchange
- **Version**: 3.0.7 (latest)
- **Used by**: Amazon, B&N, Baker & Taylor, Bowker, Ingram, Library of Congress
- **Coverage**: Title, contributors, subjects, publication date, marketing text, cover images, prices, sales rights
- **Verdict**: Industry standard. Not an API — it's a data format publishers send to retailers.

### 11.2 MARC21
- **What**: Library cataloging standard (Machine-Readable Cataloging)
- **Used by**: Every library worldwide
- **Verdict**: Legacy but ubiquitous. Being gradually replaced by BIBFRAME.

### 11.3 BIBFRAME
- **What**: Linked Data replacement for MARC, using RDF
- **Status**: Library of Congress pilot (Aug 2024), National Library of Sweden fully transitioned (2018)
- **Verdict**: Future standard. Not directly useful for consumer apps yet.

### 11.4 Z39.50
- **What**: Pre-internet protocol for searching library catalogs
- **Status**: Still active — Library of Congress restored access Feb 2026
- **Clients**: BookWhere, YAZ
- **Verdict**: Legacy but still the way many libraries share catalog records.

### 11.5 SRU (Search/Retrieve via URL)
- **What**: HTTP-based successor to Z39.50
- **Used by**: DNB, BnF, NDL, many national libraries
- **Verdict**: The modern way to query library catalogs programmatically.

### 11.6 OAI-PMH
- **What**: Protocol for harvesting metadata from repositories
- **Used by**: Internet Archive, HathiTrust, institutional repositories
- **Verdict**: Good for bulk metadata harvesting, not individual lookups.

### 11.7 OPDS
- **What**: Open Publication Distribution System — Atom-based catalog format for ebooks
- **Used by**: Internet Archive, Calibre, many ebook platforms
- **Verdict**: Standard for ebook distribution catalogs.

---

## PART 12: IDENTITY & AUTHORITY SYSTEMS

### 12.1 VIAF (Virtual International Authority File)
- **URL**: `https://viaf.org/viaf/search?query=local.ISBN="{isbn}"&httpAccept=application/json`
- **Auth**: None
- **Cost**: Free
- **Coverage**: Links authority records from 50+ national libraries
- **Verdict**: Best for disambiguating authors across international sources.

### 12.2 ISNI (International Standard Name Identifier)
- **URL**: `https://isni.org/isni/{isni}` (linked data)
- **SRU API**: Available for searching
- **Cost**: Free
- **Formats**: RDF/XML, JSON-LD
- **Verdict**: Author identity bridge between databases. Available in linked data.

### 12.3 ORCID
- **URL**: `https://pub.orcid.org/v3.0/{orcid}/works`
- **Auth**: None for public data
- **Coverage**: 18M+ researcher profiles
- **Verdict**: Academic authors only.

---

## PART 13: REGIONAL / LANGUAGE-SPECIFIC

### 13.1 Chinese Books

| Source | Type | Status | Notes |
|--------|------|--------|-------|
| Douban Books | Scraping only | API shut down | Best Chinese book metadata; scraping via community tools |
| DuXiu (读秀) | Institutional | Subscription | Largest Chinese academic book database |
| CALIS (中国高等教育文献保障系统) | Institutional | Free for Chinese universities | Union catalog for Chinese academic libraries |

### 13.2 Japanese Books

| Source | Type | Status | Notes |
|--------|------|--------|-------|
| OpenBD | REST API | Free | Best for Japanese commercial books |
| NDL Search | SRU/API | Free | National Diet Library — all Japanese publications |
| CiNii Books | REST/JSON-LD | Free (CC-BY) | Academic library holdings |
| Rakuten Books | REST API | Free (app ID) | Commercial with pricing data |
| Amazon.co.jp | Scraping | Free | Japanese Amazon product pages |

### 13.3 Korean Books

| Source | Type | Status | Notes |
|--------|------|--------|-------|
| Aladin Open API | REST API | Free (API key) | Best Korean book metadata |
| KOLIS-NET | Search portal | Free | National Library of Korea catalog |

### 13.4 German Books

| Source | Type | Status | Notes |
|--------|------|--------|-------|
| DNB (SRU) | SRU API | Free (CC0) | All German publications |
| Deutsche Digitale Bibliothek | REST API | Free | Digitized cultural content |

### 13.5 French Books

| Source | Type | Status | Notes |
|--------|------|--------|-------|
| BnF/Gallica | SRU API | Free | French national library |
| data.bnf.fr | SPARQL/LOD | Free | Linked open data for French books |

### 13.6 Polish Books

| Source | Type | Notes |
|--------|------|-------|
| Wolne Lektury | REST API | Polish public domain literature (free, no auth) |

### 13.7 Multi-language

| Source | Best For | Notes |
|--------|----------|-------|
| WorldCat | French, German, Portuguese, Japanese, Chinese, Arabic | 586M+ records |
| ISBNdb | All languages | 108M+ titles |
| Google Books | Most languages | 40M+ titles |
| Europeana | European languages | Aggregates EU national libraries |

---

## PART 14: COMIC BOOK & MANGA METADATA

### 14.1 ComicVine
- **URL**: `https://comicvine.gamespot.com/api/`
- **Auth**: API key (free)
- **Coverage**: Comics, graphic novels, manga
- **Verdict**: Best general comics database.

### 14.2 MangaDex
- **URL**: `https://api.mangadex.org/manga`
- **Auth**: None
- **Coverage**: Manga titles with chapter-level data
- **Verdict**: Best for manga metadata.

### 14.3 AniList
- **URL**: `https://graphql.anilist.co`
- **Auth**: None
- **Coverage**: 100K+ manga entries, 20K+ anime
- **Format**: GraphQL
- **Verdict**: Good for manga with user ratings/reviews.

### 14.4 Metron (MetronInfo)
- **URL**: `https://github.com/Metron-Project/metroninfo`
- **Type**: XML schema + database
- **Coverage**: Digital comic book metadata standard
- **Verdict**: Good metadata standard for comic management apps.

---

## PART 15: PRACTICAL RECOMMENDATIONS FOR LIBRARYIQ

### Tier 1: Integrate Next (High Impact, Low Effort)

| Source | Why | Effort |
|--------|-----|--------|
| **New York Times Books API** | Unique bestseller/review data, free | Small — new service class |
| **HathiTrust Bibliographic API** | Library-quality MARC data by ISBN, free | Small — simple REST |
| **Wikidata SPARQL** | Awards, linked author data, series | Medium — SPARQL queries |

### Tier 2: Integrate If Budget Allows

| Source | Why | Effort |
|--------|-----|--------|
| **ISBNdb** | 108M titles, best for obscure books | Small — $15/mo |
| **WorldCat Classify** | DDC/LCC numbers, holdings counts | Small — XML parsing |

### Tier 3: Language-Specific (If User Demand)

| Source | Why | Effort |
|--------|-----|--------|
| **OpenBD** | Japanese books | Small |
| **Aladin** | Korean books | Small |
| **DNB SRU** | German books | Medium |
| **BnF SRU** | French books | Medium |

### Tier 4: Specialty (Future Features)

| Source | Why | Effort |
|--------|-----|--------|
| **Audible API** | Audiobook metadata (narrators, runtime) | Medium — device auth |
| **ComicVine/AniList** | Comics/manga support | Medium |
| **StoryGraph** | Content warnings, mood/pace | Wait for API |
| **DoesTheDogDie** | Trigger warnings | Wait for API or scrape |

### Not Worth Integrating

| Source | Reason |
|--------|--------|
| BookBrainz | Too small |
| LibraryThing | No metadata API (ISBNs only) |
| Goodreads | API dead |
| Kobo | No public API |
| StoryGraph | No API yet |
| HarperCollins | API defunct |
| Gutendex | Public domain only |
| Bowker | Enterprise pricing |
| OverDrive | Institutional only |
| Librario | Pre-alpha |
| Ingram | Business account required |
| Syndetics | Library subscription only |
| Edelweiss | Publisher/retailer only |
| NetGalley | No public API |

---

## APPENDIX A: Complete API Endpoint Quick Reference

```
# Already Integrated
Google Books (search):     GET https://www.googleapis.com/books/v1/volumes?q=isbn:{isbn}
Google Books (volume):     GET https://www.googleapis.com/books/v1/volumes/{volumeId}
Open Library (edition):    GET https://openlibrary.org/isbn/{isbn}.json
Open Library (covers):     GET https://covers.openlibrary.org/b/isbn/{isbn}-L.jpg
Hardcover:                 POST https://api.hardcover.app/v1/graphql
iTunes (lookup):           GET https://itunes.apple.com/lookup?isbn={isbn}
iTunes (search):           GET https://itunes.apple.com/search?term={term}&media=ebook
PRH:                       GET https://reststop.randomhouse.com/resources/titles/{isbn}

# Recommended Next
NYT Books:                 GET https://api.nytimes.com/svc/books/v3/reviews.json?isbn={isbn}
NYT Bestsellers:           GET https://api.nytimes.com/svc/books/v3/lists/best-sellers/history.json?isbn={isbn}
HathiTrust:                GET https://catalog.hathitrust.org/api/volumes/full/isbn/{isbn}.json
Wikidata:                  GET https://query.wikidata.org/sparql?query={SPARQL}
ISBNdb:                    GET https://api2.isbndb.com/book/{isbn}
WorldCat Classify:         GET http://classify.oclc.org/classify2/Classify?isbn={isbn}&summary=true
CrossRef:                  GET https://api.crossref.org/works?filter=isbn:{isbn}
OpenAlex:                  GET https://api.openalex.org/works?filter=ids.isbn:{isbn}

# National Libraries
Library of Congress:       GET https://www.loc.gov/books/?q={isbn}&fo=json
DNB (Germany):             GET https://services.dnb.de/sru/dnb?query=num={isbn}
BnF (France):              GET https://gallica.bnf.fr/SRU?query=dc.identifier={isbn}
NDL (Japan):               GET https://ndlsearch.ndl.go.jp/api/sru?query=isbn={isbn}
OpenBD (Japan):            GET https://api.openbd.jp/v1/get?isbn={isbn}
VIAF:                      GET https://viaf.org/viaf/search?query=local.ISBN="{isbn}"

# Regional
Rakuten (Japan):           GET https://openapi.rakuten.co.jp/services/api/BooksBook/Search/20170404
Internet Archive:          GET https://archive.org/advancedsearch.php?q=isbn:{isbn}&output=json
Semantic Scholar:          GET https://api.semanticscholar.org/graph/v1/paper/ISBN:{isbn}
Springer Nature:           GET https://api.springernature.com/openaccess/json?q=isbn:{isbn}

# Comics/Manga
ComicVine:                 GET https://comicvine.gamespot.com/api/search/?query={title}&resources=volume
AniList:                   POST https://graphql.anilist.co (GraphQL)
MangaDex:                  GET https://api.mangadex.org/manga?title={title}
```

## APPENDIX B: Authentication Summary (Extended)

| Source | Auth Type | Cost | Rate Limit |
|--------|-----------|------|------------|
| Google Books | Optional API key | Free | 1K-10K/day |
| Open Library | None | Free | ~100/min |
| Hardcover | Bearer token | Free | ~1/sec |
| iTunes | None | Free | ~20/min |
| PRH | None | Free | Unknown |
| ISBNdb | Bearer token | $15-75/mo | 1-5/sec |
| NYT Books | API key | Free | 4K/day, 10/min |
| HathiTrust | None | Free | Unknown |
| WorldCat Classify | None | Free | 500/day |
| CrossRef | Polite pool (email) | Free | 50/sec |
| Wikidata | None | Free | Unknown |
| OpenAlex | None | Free | Unknown |
| OpenBD | None | Free | Unknown |
| Rakuten | Application ID | Free | Unknown |
| Aladin | API key | Free | Unknown |
| DNB | None | Free | Unknown |
| BnF | None | Free | Unknown |
| NDL | None | Free | Unknown |
| ComicVine | API key | Free | Unknown |
| AniList | None | Free | 90/min |
| Spotify | OAuth2 | Free | Varies |

---

*Research conducted March 2026. 60+ sources analyzed across 15 categories.*
