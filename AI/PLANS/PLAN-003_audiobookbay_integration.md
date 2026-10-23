# PLAN-003 — AudiobookBay Integration & Book Cover Preview (v1.0)

Status: DRAFT
Author: Antigravity / Gemini 3.8 Flash / Google / Antigravity IDE
Role: PLANNER
Created: 2026-10-23T16:30:00+06:00
Related task(s): TASK-008 through TASK-012
Approved by/at: Not approved

## Problem / user value
The user wants to find, preview, and stream audiobooks directly inside Rivulet without manually visiting an external browser, copying magnet links, or guessing which book is playing:
1. A dedicated side section for discovering and acquiring magnet links from `https://audiobookbay.lu/`.
2. Hardcoded user credentials (`celoy96375:celoy96375@proguidex.com`, UID/Password: `pass`) for automated access.
3. Preview cover image for the book in the search list, library, player screen, and lock screen media notification.

## Existing system evidence
- App runs on Android 13/compileSdk 36 with Jetpack Compose Material 3 and Navigation 3 (`Navigation.kt`).
- `TorrentEngine` accepts magnet links via `addMagnet(magnetUri: String)`.
- `TorrentDatabase` stores torrents in SQLite table `torrents`, but does not yet store `cover_url`.
- `PlaybackController` updates `PlaybackUi` with `title` and `subtitle`, but does not have `coverUrl` or `setArtworkUri`.
- `PlayerScreen.kt` currently renders a generic 200dp square box with a vector `PlayArrow` icon (`Icons.Default.PlayArrow`).
- Build configuration lacks image loading (no Coil) and HTML parsing (no Jsoup).

## Objectives and scope
### In scope:
- Add `ModalNavigationDrawer` on the side to switch between Library and AudiobookBay.
- Add `AudioBookBayConfig` with hardcoded credentials and default trackers.
- Add `AudioBookBayClient` with authenticated session handling, search, latest feed, and resilient InfoHash-to-magnet assembly.
- Add Coil Compose dependency (`io.coil-kt:coil-compose:2.7.0`) for fast, cached image loading.
- Add Jsoup (`org.jsoup:jsoup:1.18.3`) and OkHttp (`com.squareup.okhttp3:okhttp:4.12.0`).
- Update `TorrentDatabase` (schema v2) to persist `cover_url`.
- Update `PlayerScreen` and `TorrentCard` to render the book preview cover image.
- Set `MediaMetadata.setArtworkUri` for Android lock screen & notification artwork.
- Unit tests and wireless deployment to connected Redmi 10 device.

### Out of scope:
- Multi-user account switching for AudiobookBay (single hardcoded account specified by user).
- Downloading complete torrents to external SD cards without app storage permissions.

## Assumptions and open questions
- **Assumption:** `audiobookbay.lu` occasionally rate-limits or redirects login forms; therefore, scraping the 40-character hexadecimal InfoHash and constructing `magnet:?xt=urn:btih:...` with fallback public trackers is required as a fallback alongside direct authenticated magnet extraction.
- **Image URLs:** Book covers on AudiobookBay are hosted either on BBC/publisher CDNs or AudiobookBay uploads, both accessible via standard HTTPS.

## Alternatives and rationale
- **Embedded WebView vs. Native Compose UI:**
  - *Option A (WebView):* Embed an Android WebView showing the website directly. Downside: sluggish touch feel, clunky mobile rendering, ads/popups, no seamless 1-tap integration with `TorrentEngine`.
  - *Option B (Native Compose UI + Scraper):* Clean, modern Material 3 interface matching Rivulet's dark theme, with instantaneous search, 1-tap "Stream Now", and direct cover art extraction. Selected Option B.

## Proposed design
1. **Side Section:** `ModalNavigationDrawer` wrapping the app's top-level destinations. Hamburger icon on `TopAppBar` and left-edge swipe gesture.
2. **AudiobookBay Client:**
   - `AudioBookBayConfig.kt`: Hardcoded credentials and tracker endpoints.
   - `AudioBookBayClient.kt`: OkHttp + Jsoup parser for search results and detail pages.
3. **Cover Art Delivery:**
   - `TorrentDatabase`: v2 migration adds `cover_url TEXT`.
   - `PlaybackController`: Supplies `coverUrl` to `PlaybackUi` and ExoPlayer `MediaMetadata`.
   - `PlayerScreen.kt`: Uses Coil `AsyncImage` for book artwork.
   - `TorrentCard`: Displays cover thumbnail.

## Detailed implementation steps
1. **Dependencies:** Add `coil-compose`, `jsoup`, and `okhttp` to `gradle/libs.versions.toml` and `app/build.gradle.kts`.
2. **Database v2:** Add `cover_url TEXT` column to `torrents` table in `TorrentDatabase.kt`.
3. **Data Layer:** Create `data/audiobookbay/` package with `AudioBookBayConfig.kt`, `AudioBookBayModels.kt`, and `AudioBookBayClient.kt`.
4. **Playback & Models:** Update `TorrentUi`, `SavedTorrent`, `PlaybackUi`, and `PlaybackController` to propagate `coverUrl`.
5. **Navigation:** Introduce `ModalNavigationDrawer` in `Navigation.kt` with destinations `Library` and `AudiobookBay`.
6. **UI:** Create `ui/audiobookbay/AudioBookBayScreen.kt` and update `ui/player/PlayerScreen.kt` and `ui/library/LibraryScreen.kt`.
7. **Verification:** Add unit tests, build APK, and deploy wirelessly via `scripts/deploy.ps1`.

## Dependencies and sequencing
- Step 1 (Dependencies) must precede Step 2 and Step 3.
- Step 3 (Client) must precede Step 6 (UI).
- Step 7 (Verification) occurs on connected Redmi 10 device.

## Risks, security, legal issues, and rollback
- **Risk:** Hardcoded credentials committed to code. User explicitly requested this format ("Format, I want that hardcoded in the code. (i dont care)").
- **Rollback:** Database migration uses `ALTER TABLE` with `runCatching` to remain backwards compatible.

## Test plan and acceptance criteria
- Unit test `AudioBookBayClientTest` passes with mock/live HTML parsing.
- Search for books returns titles, authors, and cover image URLs.
- Tapping "Stream" on a book immediately begins buffering and streaming in Rivulet.
- Cover art is visible on Player screen and Library screen.
- `./gradlew.bat test` and `./gradlew.bat assembleDebug` exit with code 0.

## Revision history
- v1.0 — 2026-10-23T16:30:00+06:00: Initial draft for user review.

---
Signed by: Antigravity / Gemini 3.8 Flash / Google
Role: PLANNER
Recorded at: 2026-10-23T16:30:00+06:00
