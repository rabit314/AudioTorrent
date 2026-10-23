# PLAN-006 v4.0 — Comprehensive UX, Audio Engineering, Playback Bug Fixes & Keyboard-Enter Search Discovery

- **Date:** 2026-10-23T19:09:00+06:00 (Asia/Dhaka)
- **Author:** Antigravity (Gemini 3.8 Flash)
- **Role:** Planner / Researcher
- **Status:** DRAFT (Awaiting User Confirmation)
- **Scope:** Keyboard-Enter Search (`ImeAction.Search`), Interactive Recent Searches, Chapter Queue, Audio Engineering (Fade-Out & Silence Skipping), Playback Bug Fixes, Bookmarks, Swarm Transparency, Removal of Categories

---

## 1. Executive Summary & Objective

Based on comprehensive static audit and direct user requirements:
1. **1-Press Keyboard Enter Search:** Type a title/author on keyboard and press Enter/Search (`ImeAction.Search` on soft keyboard). This immediately hides the keyboard, saves the query to recent searches, and triggers search—no need to tap any "Go" button!
2. **Interactive Recent Searches:** Save search history in SQLite `recent_searches` table; render interactive chips with clock icons (`[🕒 Dune (×)]`) below the search bar, with 1-tap re-query, single-query removal, and "Clear all".
3. **Remove Categories Everywhere:** Eliminate the "Explore Genres" section on Home and all category filter chips in `AudioBookBayScreen.kt`.
4. **Fix Critical Playback Bugs:**
   - **Completed Track Replay Bug:** Reset `savedPos = 0L` when a track is near/at EOF so completed chapters can be re-listened to without auto-skipping.
   - **Stream Teardown Priority Bug:** Stop setting `Priority.IGNORE` and clearing deadlines when sockets disconnect; respect user's offline download priorities and prevent download halt when paused.
   - **Missing Queue on Resume:** Ensure resuming from Home Hero card or Listening History populates `PlaybackController.queue` so chapter auto-advance works.
   - **Deadline Storm / Sequential Ordering:** Replace uniform deadline `0` across 32 pieces with playhead-prioritized sequential deadlines.
   - **Play/Pause Icon Glitches:** Fix `Icons.Default.PlayArrow` bug on mini-player and hero card.
5. **Audiobook Experience Enhancements:**
   - **In-Player Chapters & Track Queue:** Bottom sheet in `PlayerScreen` showing all chapters, durations, active indicator, and 1-tap jump.
   - **Audio Engineering Polish:** 30s gentle sleep timer volume fade-out + native Smart Silence Skipping (`skipSilenceEnabled`).
   - **Fine-Grained Speed & Jump Controls:** 0.5×–3.0× slider with 0.1× steppers and customizable jump intervals.
   - **Bookmarks & Notes System:** DB v5 `bookmarks` table, 1-tap timestamp bookmarking with notes, and in-player bookmark browser.
   - **Swarm & Buffer Transparency:** Dual-layer scrubber displaying downloaded piece buffers + live swarm telemetry badge.
   - **Instant 1-Tap "Continue Listening":** Home page Hero card play button immediately prepares stream and resumes playback without screen hops.
   - **Wishlist Shelf:** "Want to Listen" shelf on Home for saved books.

---

## 2. Work Breakdown & Task Allocation

### `TASK-030`: In-Player Chapter Navigation & Track Queue Bottom Sheet
- Expose `currentQueue: StateFlow<List<QueueTrack>>` and `currentQueueIndex: StateFlow<Int>` in `PlaybackController`.
- Add a "Chapters & Queue" button in `PlayerScreen` opening a bottom sheet with full chapter list, progress, active indicator, and 1-tap jump.

### `TASK-031`: Audio Engineering: Smooth Sleep Timer Fade-Out & Smart Silence Skipping
- 30-second volume fade-out before sleep timer stop (ducking volume from `1.0f` to `0.0f`).
- Add `toggleSkipSilence(): Boolean` leveraging Media3 ExoPlayer's native silence trimming.
- Render `Trim Silence` filter chip in `PlayerScreen`.

### `TASK-032`: Fine-Grained Playback Speed & Customizable Jump Intervals
- Replace speed dialog with slider (0.5×–3.0×, 0.05× steps, `-0.1×`/`+0.1×` stepper buttons, and presets).
- Customizable jump intervals (default -10s/+30s or -15s/+30s).

### `TASK-033`: Bookmarks & Notes System
- Upgrade DB to `DATABASE_VERSION = 5` with `bookmarks` and `wishlist_books` tables.
- In-player bookmark button with timestamp and optional note dialog; Bookmarks tab in Chapter bottom sheet.

### `TASK-034`: Swarm & Buffer Stream Visualizer
- Expose verified piece buffer ratio and swarm peer metrics (`peerCount`, `downloadRate`) in `PlaybackUi`.
- Dual-layer scrubber bar showing downloaded ranges + live telemetry pill.

### `TASK-035`: Instant 1-Tap "Continue Listening" Resume & Queue Autoplay
- Home Hero card Play button immediately prepares stream, sets the book queue, and plays without navigating away.
- History shelf items also populate queue on resume.

### `TASK-036`: Fix Playback Engine Bugs & UI Glitches
- **Fix Completed Track Replay:** If `savedPos >= durationMs - 3000L`, start at `0L`.
- **Fix Stream Teardown:** Stop setting `Priority.IGNORE` on connection close; maintain idle lease grace period.
- **Fix Deadline Storm:** Set deadline 0 on immediate playhead piece and sequential ascending deadlines on remainder.
- **Fix Missing Queue on Resume:** Auto-build queue from torrent's audio files in `prepareAndPlayTrack` and `resume`.
- **Fix Play/Pause Icons:** Replace `PlayArrow` with Pause glyph when `isPlaying == true` in mini-players and hero card.
- **Fix AudiobookBay Top Bar:** Replace `Menu` hamburger icon with `ArrowBack`.

### `TASK-037`: Keyboard Enter Search, Recent Searches, Wishlist & Category Removal
- Add `KeyboardOptions(imeAction = ImeAction.Search)` and `KeyboardActions(onSearch = { ... })` on search field.
- Create DB table `recent_searches (query TEXT PRIMARY KEY, searched_at INTEGER NOT NULL)`.
- Implement `Recent Searches` chip shelf under search bar with 1-tap search, single-item deletion, and "Clear all".
- Remove "Explore Genres" and chips from `LibraryScreen.kt`.
- Remove all category filter chips from `AudioBookBayScreen.kt`.
- Simplify `NavigationKeys.kt` `AudioBookBay` route.
- Add "Save to Wishlist" bookmark button on book cards and Wishlist shelf on Home.

### `TASK-038`: Network Safety Safeguard
- Wi-Fi only streaming/downloading toggle to prevent unintended cellular data usage.

---

## 3. Verification Plan

### Automated Unit Tests
```powershell
./gradlew.bat test
```
- `RecentSearchesTest.kt`: Verify query storage, deduplication, timestamp ordering, delete and clear operations.
- `PlaybackResumeBugTest.kt`: Verify completed track (`pos == duration`) resets to 0L on replay.
- `QueueAndChapterTest.kt`: Verify queue index bounds, auto-advance, and playlist jumping.
- `AudioEnhancementsTest.kt`: Verify volume fade math, silence skipping toggle, and speed clamp.
- `BookmarksAndWishlistTest.kt`: Verify DB v5 migrations, bookmark and search history CRUD.

### Manual Verification
1. **Keyboard Enter Search**: Type "Dune" on keyboard, press Enter/Search on keyboard, verify search triggers immediately and keyboard closes without touching "Go".
2. **Recent Searches**: Verify "Dune" appears as a chip. Tap it, verify it searches. Tap (×), verify it is removed.
3. **No Categories**: Verify Home and AudiobookBay screens have no category/genre chips; clean search UI.
4. **Replay Completed Chapter**: Play a chapter to the end, then tap it again in the chapter list; verify it plays from 0:00 instead of skipping.
5. **Queue Auto-Advance**: Resume from History, wait for track end, verify it auto-advances to the next chapter.
6. **Chapters Sheet**: In Player, open Chapters sheet, verify all tracks appear with active highlight, tap Chapter 3 to jump.
7. **Smooth Sleep Fade**: Set 1m timer, hear smooth 30s volume fade without abrupt clicks.
8. **Hero 1-Tap**: Tap Play on Hero card, verify audio streams immediately.
9. **Clean Build**: Run `./gradlew.bat assembleDebug` and verify APK compiles.
