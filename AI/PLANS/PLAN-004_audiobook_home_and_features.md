# PLAN-004 — Rich Audiobook Front Page, Listening History & Continuous Playback (v2.0)

Status: DRAFT
Author: Antigravity / Gemini 3.8 Flash / Google / Antigravity IDE
Role: PLANNER
Created: 2026-10-23T16:58:00+06:00
Related task(s): TASK-014 through TASK-020
Approved by/at: Not approved

## Problem / user value
The user reported that the app looks "too simple and bland" and requested:
1. Make the front page like AudiobookBay / modern audiobook streaming apps.
2. Include Listening History with progress metrics and last played positions.
3. Natural chapter sorting for multi-file audiobooks.
4. Continuous playback enhancements and similar premier features.

## Existing system evidence
- `LibraryScreen` renders a simple list of torrent cards or an empty box.
- `TorrentDatabase` records `playback_state` per file and torrent, but does not provide joined listening history queries.
- `TorrentUi.audioFiles` is unsorted, causing chapter scrambling.
- `PlaybackController` lacks relative jump (`-10s` / `+30s`) and smart resume auto-rewind.

## Objectives and scope
### In scope:
1. **Front Page Redesign**:
   - Hero "Continue Listening" card with large artwork, progress bar, time remaining, and instant resume.
   - "Listening History" shelf of recently played books with progress rings.
   - "Trending on AudiobookBay" live shelf right on the front page with 1-tap streaming.
   - Category chips (Sci-Fi, Fantasy, Mystery, Fiction, History).
   - "My Books & Downloads" offline filter.
2. **Natural Chapter Sorting**:
   - Alphanumeric comparator for files (`Chapter 1`, `Chapter 2` ... `Chapter 10`).
   - Queue sequence alignment with sorted chapter list.
3. **Audiobook Playback Enhancements**:
   - Skip relative (`-10s` / `+30s`) in Player and system notifications.
   - Smart context rewind on resume (3–10s rewind after pause).
   - Voice Dialogue Boost (`LoudnessEnhancer`).
   - Persistent mini-player primed with last book on app start.

## Detailed implementation steps
1. **Database**: Implement `getListeningHistory()` and `getLatestPlayedBook()` in `TorrentDatabase.kt`.
2. **Comparator**: Implement `NATURAL_NAME_COMPARATOR` in `data/Models.kt`.
3. **Playback**: Add `seekBy()`, `smartResume`, and `LoudnessEnhancer` to `PlaybackController.kt`.
4. **UI - Home Screen**: Redesign `ui/library/LibraryScreen.kt` into a rich, multi-shelf audiobook home with Hero card, history shelf, trending feed, and category chips.
5. **UI - Player Screen**: Add `-10s` / `+30s` buttons and voice boost toggle.
6. **UI - Torrent Detail**: Add sort mode selector in `TorrentScreen.kt`.
7. **Verification**: Automated unit tests in `AudioSortAndEnhancementTest.kt`, Gradle build, and wireless deployment to Redmi 10.

---
Signed by: Antigravity / Gemini 3.8 Flash / Google
Role: PLANNER
Recorded at: 2026-10-23T16:58:00+06:00
