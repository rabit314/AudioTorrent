# PLAN-002 — Complete Implementation of PLAN.md (Rivulet Audio Torrent Streamer)

**Version:** 1.0  
**Author:** Antigravity (Gemini 3.8 Flash)  
**Status:** APPROVED (Authorized by user command)  
**Assigned Roles:** PLANNER, IMPLEMENTER, REVIEWER, TESTER  
**Related Tasks:** TASK-001, TASK-002, TASK-003, TASK-004, TASK-005, TASK-006, TASK-007

## 1. Objective

Complete the remaining roadmap in `PLAN.md` to produce a fully functioning, persistent, background-capable, feature-rich torrent audio streamer for Android, and export an installable APK for the user.

## 2. Current State vs PLAN.md Gap Analysis

| Feature Area | Current State | Required by PLAN.md | Action Needed |
|---|---|---|---|
| Torrent Add & Stream | Working in-memory (M0/M1) | Working | Preserve and enhance |
| Background Playback | App-scoped ExoPlayer only; stops in background | MediaSessionService with `mediaPlayback` FGS, notification, lock screen controls | Implement `RivuletMediaService` + Manifest permissions & service |
| Persistence | In-memory only (lost on restart) | Restore torrents & files on startup; remember playback position | Implement SQLite database (`TorrentDatabase`) |
| Playback Resume | Resets to 0 every time | Exact resume per audio file | Persist resume position every 5s & on pause; restore on track load |
| Queue / Playlist | Plays single file and stops | Playlist/queue, auto-play next track in album/audiobook | Implement track queue & auto-advance on track completion |
| Playback Speed | None | Speed controls (0.75x - 2.0x) | Add speed control to `PlaybackController` & `PlayerScreen` |
| Sleep Timer | None | Sleep timer (15m, 30m, 45m, 60m, End of Track) | Add timer logic to `PlaybackController` & UI to `PlayerScreen` |
| Download for Offline | None (all files ignored by default) | Per-file & whole-torrent offline download | Add download actions to `TorrentEngine` & `TorrentScreen` |
| Storage & Library Mgmt | In-memory; no delete UI | Delete torrent + optional delete files; storage accounting | Add delete dialog & storage usage in UI |
| MP4 `moov` Tail Handling | Only reads head | Tail-first priority for MP4/M4B | Prioritize last ~2 MB pieces in `StreamSession` |
| Release / Export | None | Export installable APK | Build APK and copy to `Rivulet.apk` export |

## 3. Architecture & Implementation Steps

### Step 1: Persistence Layer (`data/TorrentDatabase.kt`)
- SQLiteOpenHelper managing:
  - `torrents` table: `info_hash` (TEXT PK), `name` (TEXT), `added_at` (INTEGER), `torrent_bytes` (BLOB), `magnet_uri` (TEXT)
  - `playback_state` table: `info_hash` (TEXT), `file_index` (INTEGER), `position_ms` (INTEGER), `duration_ms` (INTEGER), `updated_at` (INTEGER), PRIMARY KEY(`info_hash`, `file_index`)
  - `file_settings` table: `info_hash` (TEXT), `file_index` (INTEGER), `download_wanted` (INTEGER), PRIMARY KEY(`info_hash`, `file_index`)
- Integrate with `TorrentEngine`:
  - On engine start, restore saved torrents from SQLite.
  - On adding torrent (magnet or file), save to SQLite.
  - On remove torrent, remove from SQLite.
- Integrate with `PlaybackController`:
  - Periodically save `(infoHash, fileIndex, positionMs, durationMs)` to SQLite.
  - On track load, query saved position and seek if > 0.

### Step 2: Background Playback & `MediaSessionService` (`playback/RivuletMediaService.kt`)
- Create `RivuletMediaService : MediaSessionService`.
- Provide `MediaSession` tied to ExoPlayer.
- In `AndroidManifest.xml`:
  - Add permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`.
  - Register `<service android:name=".playback.RivuletMediaService" ...>`.
- Hook notification with media style, actions for Play/Pause, Seek -15s, Seek +30s, Previous, Next.

### Step 3: Queue & Auto-Advancing Playlist (`playback/PlaybackController.kt`)
- Maintain queue of tracks (audio files in the current torrent).
- Support `playNext()`, `playPrevious()`, `hasNext`, `hasPrevious`.
- On `Player.STATE_ENDED`: auto-advance to next track in queue seamlessly.
- Expose current track index, queue info, and playback speed in `PlaybackUi`.

### Step 4: Playback Speed & Sleep Timer
- Add `setSpeed(speed: Float)` to `PlaybackController` using `player.setPlaybackParameters(PlaybackParameters(speed))`.
- Add `setSleepTimer(minutes: Int)` / `setSleepTimerEndOfTrack()` to `PlaybackController`.
- Update `PlayerScreen` with Speed selector and Sleep Timer dialog.

### Step 5: Offline Download & Torrent Management
- In `TorrentEngine`:
  - Add `setFileDownloadWanted(hash, fileIndex, wanted)`.
  - Add `setTorrentDownloadWanted(hash, wanted)`.
- In `TorrentScreen`:
  - Add Download button per file (toggle download / stream-only).
  - Add "Download all" option in top bar.
  - Add Delete torrent button with confirmation dialog ("Delete torrent only" vs "Delete torrent and data").
- In `LibraryScreen`:
  - Long press or menu to delete torrent.
  - Storage summary in header.

### Step 6: Robustness & MP4 Tail Handling (`stream/StreamSession.kt`)
- In `StreamSession.start()`:
  - If filename ends in `.mp4`, `.m4a`, `.m4b`:
    - Arm piece deadlines for the last 2 MB of the file in addition to the first 256 KB.
    - Ensures non-faststart files with `moov` at the tail can initialize without delay.

### Step 7: Tests, Verification, and Export
- Add unit tests for database, queue logic, range calculation, speed, and timer.
- Run `./gradlew.bat test` and verify passing.
- Run `./gradlew.bat assembleDebug` and assemble release APK.
- Copy APK to root as `Rivulet.apk` with version info.

## 4. Acceptance Criteria
- App compiles without errors.
- Unit tests pass.
- Added torrents persist across app restart.
- Playback resume position is saved and restored.
- Next track plays automatically when current finishes.
- MediaSessionService runs in foreground for background audio playback.
- Playback speed and sleep timer work.
- APK file is generated and placed in the project root for installation.
