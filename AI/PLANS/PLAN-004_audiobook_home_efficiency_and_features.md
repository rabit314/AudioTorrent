# PLAN-004 — Rich Audiobook Front Page, Deep Efficiency Optimizations & Features (v3.0)

Status: DRAFT
Author: Antigravity / Gemini 3.8 Flash / Google / Antigravity IDE
Role: PLANNER
Created: 2026-10-23T17:01:00+06:00
Related task(s): TASK-014 through TASK-022
Approved by/at: Not approved

## Problem / user value
The user requested:
1. Make the front page like AudiobookBay / Audible with listening history.
2. Natural chapter sorting for multi-track audiobooks.
3. Enhanced continuous playback.
4. Deep efficiency optimizations (battery, CPU, network data usage, flash storage).

## Existing system evidence
- `TorrentEngine.kt` uses high connection limit (250) and polls every 1000ms indefinitely, even when backgrounded or paused.
- SQLite queries are executed inside `refresh()` on every tick without in-memory caching.
- `LibraryScreen.kt` is a simple list rather than an audiobook discovery & listening hub.
- `TorrentUi.audioFiles` is unsorted, causing chapter scrambling.
- `PlaybackController.kt` lacks relative skip controls and smart resume.

## Objectives and scope
### In scope:
1. **Efficiency Overhaul**:
   - Adaptive background polling (1s active → 5s background/paused).
   - In-memory metadata caching in `TorrentEngine` to eliminate SQLite read overhead.
   - Mobile swarm tuning (64 connections, 3 active downloads).
   - 16 MB RAM disk cache to minimize flash wear and read energy.
   - SQLite indexing on `playback_state(updated_at DESC)` and `torrents(added_at DESC)`.
   - Selective piece window streaming to save gigabytes of mobile data.
2. **Audiobook Front Page Redesign**:
   - Hero "Continue Listening" card with large cover, progress bar, chapter info, and 1-tap resume.
   - "Listening History" shelf of recently played books with progress rings.
   - "Trending on AudiobookBay" live shelf right on home with 1-tap streaming.
   - Category / genre quick-chips.
   - "My Books & Downloads" offline filter.
3. **Natural Chapter Sorting**:
   - Alphanumeric comparator for files (`Chapter 1`, `Chapter 2` ... `Chapter 10`).
   - Queue sequence alignment with sorted chapter list.
4. **Audiobook Playback Enhancements**:
   - Skip relative (`-10s` / `+30s`) in Player and system notifications.
   - Smart context rewind on resume (5–10s rewind after pause).
   - Voice Dialogue Boost (`LoudnessEnhancer`).
   - Persistent mini-player primed with last book on app start.

## Detailed implementation steps
1. **Database & Indexing**: Add SQLite indices and implement `getListeningHistory()` / `getLatestPlayedBook()`.
2. **Efficiency Tuning**: Update `SettingsPack` (64 connections, 16MB cache), adaptive polling, in-memory metadata cache.
3. **Comparator**: Implement `NATURAL_NAME_COMPARATOR` in `data/Models.kt`.
4. **Playback**: Add `seekBy()`, `smartResume`, and `LoudnessEnhancer` to `PlaybackController.kt`.
5. **UI - Home Screen**: Redesign `ui/library/LibraryScreen.kt` into a rich multi-shelf audiobook hub.
6. **UI - Player Screen**: Add `-10s` / `+30s` buttons and voice boost toggle.
7. **UI - Torrent Detail**: Add sort mode selector in `TorrentScreen.kt`.
8. **Verification**: Automated unit tests in `AudioSortAndEnhancementTest.kt`, Gradle build, and wireless deployment to Redmi 10.

---
Signed by: Antigravity / Gemini 3.8 Flash / Google
Role: PLANNER
Recorded at: 2026-10-23T17:01:00+06:00
