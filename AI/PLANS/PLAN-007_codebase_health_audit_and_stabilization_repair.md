# PLAN-007 v1.0 — Codebase Health Audit, Playback Stabilization & Architecture Hardening

- **Date:** 2026-10-23T20:17:00+06:00 (Asia/Dhaka)
- **Author:** Antigravity (Gemini 3.8 Flash)
- **Role:** Planner / Researcher
- **Status:** DRAFT (Awaiting User Approval)
- **Source Document:** External Audit & Repair Roadmap (`c:\Users\ASUS\Downloads\PLAN.md`)
- **Scope:** Lint 0 errors, Unified `requestPlayback`, Queue State Synchronization, Media3 `ForwardingPlayer` Lockscreen/Bluetooth Queue Controls, Priority-Preserving Stream Leases, 60s Pause Lifecycle, Cross-Chapter Bookmark Seeking, `PacedTimingEngine` Range Crash Fix, Transactional Database Cascade, Safe Multi-file Deletion, Verified Audio Completion Filter, UI History Polling Optimization, Wishlist & Jump Interval UI

---

## 1. Executive Summary & Audit Verification

An external audit roadmap was supplied in `PLAN.md`. Every finding was audited and verified against the actual repository source code:

1. **Android Lint Fails with 8 Errors**: Verified. Media3 experimental APIs in `PlaybackController.kt` lack `@OptIn(UnstableApi::class)`.
2. **Offline Download Reset on Chapter Change**: Verified. `TorrentEngine.kt:293` calls `activeStream?.close()` with default `Priority.IGNORE`, resetting user offline download preferences when changing chapters.
3. **Queue State Inconsistency**: Verified. `PlaybackController.kt:267` modifies internal `queueIndex` without updating `currentQueueIndex.value`; resuming another chapter retains the old index.
4. **Bookmarks Seek into Wrong Chapter**: Verified. `PlayerScreen.kt:575` seeks directly to `bm.positionMs` on whatever chapter is active, ignoring `bm.fileIndex`.
5. **False 100% Downloaded Filter**: Verified. `LibraryScreen.kt:403` checks `progress >= 1f` from libtorrent task progress rather than checking verified completion of audio files on disk.
6. **Timing Crash in `PacedTimingEngine.kt`**: Verified. Line 56 calls `coerceIn(startMs + 500L, totalDurationMs)`, throwing `IllegalArgumentException` whenever `startMs + 500L > totalDurationMs`.
7. **Unbounded / Stale Text Cache**: Verified. `PageTextManager.kt` keeps global lines without track scoping and never evicts pages or handles attachment replacements.
8. **Colliding Recursive Deletion**: Verified. `TorrentEngine.kt:400` calls `deleteRecursively()` on `info.name()`, risking deletion of colliding torrent folders.
9. **UI Efficiency Drain**: Verified. `LibraryScreen.kt:129` runs `LaunchedEffect(isPlaying, positionMs)` which queries SQLite every 350ms during playback.

---

## 2. Work Breakdown & Task Allocation

### `TASK-039`: Codebase Health Baseline & Lint Opt-In Resolution
- Add `@OptIn(UnstableApi::class)` to `PlaybackController.kt` and `RivuletMediaService.kt`.
- Resolve all 8 lint errors so `./gradlew.bat lintDebug` passes cleanly.

### `TASK-040`: Unified Playback Architecture & Queue State Synchronization
- Introduce `requestPlayback(infoHash, fileIndex, startPositionMs, queueOverride, autoplay)`.
- Atomically sync private `queueIndex` and public `currentQueueIndex.value`.
- Override automatic resume rules when explicit `startPositionMs` is provided.
- Wrap ExoPlayer in `ForwardingPlayer` in `RivuletMediaService` to connect lockscreen, notification, and Bluetooth next/previous buttons to chapter queue.

### `TASK-041`: Priority-Preserving Stream Lease & 60-Second Pause Lifecycle
- Centralize `calculateEffectivePriority(hash, fileIndex)` in `TorrentEngine`: streaming = `SEVEN`, offline wanted = `DEFAULT`, else `IGNORE`.
- Restore previous stream's priority based on `isFileWanted` when switching chapters or closing.
- Extend idle lease timer to 60 seconds on pause; preserve offline downloads after 60s.

### `TASK-042`: Cross-Chapter Bookmark Seeking & Offline Audio Completion Filter
- In `PlayerScreen.kt`, route bookmark clicks through `requestPlayback(bm.infoHash, bm.fileIndex, startPositionMs = bm.positionMs)`.
- Add `TorrentUi.isFullyDownloaded` property verifying on-disk completion of all audio files.
- Update `LibraryScreen.kt` "Downloaded Only" chip to use `isFullyDownloaded`.

### `TASK-043`: Robust Text Attachment Timing (`PacedTimingEngine` Range Fix) & Isolated Cache
- Fix `coerceIn` empty range crash in `PacedTimingEngine.kt` with clamped start/end logic supporting short tracks (< 5s), dense text, zero duration, and large offsets.
- Scope `PageTextManager` lines to track key (`"$infoHash:$fileIndex"`), clear cache on track change, and bound memory cache.

### `TASK-044`: Transactional Cascade Database Cleanup & Safe File Deletion
- Enclose `deleteTorrent` in `TorrentDatabase.kt` within a database transaction across all 7 tables (`torrents`, `playback_state`, `file_priorities`, `bookmarks`, `playback_history`, `page_text_cache`, `wishlist_books`).
- Delete only specific torrent files in `TorrentEngine.kt` rather than `deleteRecursively()` on directory names.

### `TASK-045`: UI Efficiency (Event-driven History) & Open Requirements (Wishlist, Jump Intervals, Wi-Fi Toggle)
- Remove 350ms history polling in `LibraryScreen.kt`; refresh history on screen entry, track change, or pause.
- Build "Want to Listen" Wishlist shelf and book card bookmark buttons.
- Add configurable jump interval toggle (-10s/+30s vs -15s/+30s).
- Add Wi-Fi safeguard toggle in settings.

### `TASK-046`: Comprehensive Regression Test Suite & Versioned APK Release
- Write automated tests:
  - `PacedTimingRobustnessTest.kt`
  - `PlaybackUnifiedQueueTest.kt`
  - `OfflinePriorityPreservationTest.kt`
  - `CascadeDatabaseDeletionTest.kt`
  - `AudioCompletionFilterTest.kt`
- Increment version code to 3, version name to `0.2.0-beta`.
- Run full test suite (`./gradlew.bat test`), lint (`./gradlew.bat lintDebug`), and debug APK build (`./gradlew.bat assembleDebug`).

---

## 3. Acceptance Criteria

1. **Lint**: Zero lint errors (`./gradlew.bat lintDebug` succeeds).
2. **Tests**: All unit tests pass (`./gradlew.bat test` succeeds).
3. **Queue**: Lockscreen/Bluetooth next/previous advances chapters; UI queue highlight updates in real time.
4. **Bookmarks**: Tapping a bookmark in any chapter transitions to that chapter and seeks to the exact position.
5. **Downloads**: Switching chapters never cancels active offline downloads; "Downloaded Only" accurately reflects completed books.
6. **Lyrics/Text**: Timing engine handles 1-second tracks and +60s offsets without crashing.
7. **Database**: Deleting a book purges all associated records transactionally.
8. **UI**: Smooth scrolling and playback without periodic SQLite queries.
