# PLAN-004 — Natural Name File Sorting & Audiobook Player Enhancements (v1.0)

Status: DRAFT
Author: Antigravity / Gemini 3.8 Flash / Google / Antigravity IDE
Role: PLANNER
Created: 2026-10-23T16:55:00+06:00
Related task(s): TASK-014 through TASK-018
Approved by/at: Not approved

## Problem / user value
Audiobooks frequently consist of dozens of numbered chapter files (e.g. `Chapter 01.mp3` through `Chapter 45.mp3`). When torrent files are ordered arbitrarily by size or piece offset, book playback becomes chaotic.
1. The user requested: "Instead of having torrent files in size order, Its best to have in name order."
2. The user also requested: "And suggest more features. And improvements, I need them."

## Existing system evidence
- In `data/Models.kt`, `TorrentUi.audioFiles` currently does `files.filter { it.isAudio }` without natural sorting.
- `TorrentEngine.readFiles` reads files in index order from `TorrentInfo.files()`.
- `TorrentScreen` renders `torrent.audioFiles` and maps them directly to `QueueTrack`.
- `PlaybackController` only supports absolute `seekTo(posMs)`. No relative jump (`-10s` / `+30s`) or contextual auto-rewind exists.

## Objectives and scope
### In scope:
1. Natural Alphanumeric File Ordering (e.g. `Chapter 1`, `Chapter 2`, ... `Chapter 10`, `Chapter 11`).
2. Sort options in `TorrentScreen` (Natural Name A-Z by default, Torrent Index, File Size).
3. Queue synchronization matching the active sort sequence.
4. Dedicated `-10s` and `+30s` skip buttons in Player and system media controls.
5. Smart Auto-Rewind on Resume (rewinds 3–10s when resumed after an extended pause).
6. Voice Boost / LoudnessEnhancer for quiet audiobook narrators.
7. Swarm buffer health indicator.

## Proposed design
- `NATURAL_NAME_COMPARATOR` in `data/Models.kt`.
- `TorrentScreen`: Sort mode selector dropdown / chips.
- `PlaybackController`: `seekBy(offsetMs)` and `LoudnessEnhancer` audio effect.
- `PlayerScreen`: Dedicated skip and voice boost controls.

## Detailed implementation steps
1. Implement `NATURAL_NAME_COMPARATOR` and update `TorrentUi.audioFiles`.
2. Add sort modes and UI selector in `TorrentScreen.kt`.
3. Add `seekBy` and `smartResume` to `PlaybackController.kt`.
4. Add `-10s` / `+30s` jump buttons and boost button in `PlayerScreen.kt`.
5. Unit tests covering comparator edge cases and seek operations.
6. Build and deploy to connected Redmi 10 device.

## Test plan and acceptance criteria
- `AudioSortAndEnhancementTest.kt` passes.
- Numbered chapters sort chronologically (1, 2, ... 9, 10).
- Skipping back 10s and forward 30s works smoothly.
- `./gradlew.bat test` and `./gradlew.bat assembleDebug` exit 0.

---
Signed by: Antigravity / Gemini 3.8 Flash / Google
Role: PLANNER
Recorded at: 2026-10-23T16:55:00+06:00
