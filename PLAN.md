# Torrent Audio Streamer — Android

Working name: **TBD** (dir: `AudioBook Project`)
Status: planning · v1 targets personal use, shippable later via GitHub/F-Droid

## 1. Product scope (v1)

A generic BitTorrent client optimized for **audio**: audiobooks, music albums, podcasts/live sets.

**In scope**
- Add torrents by magnet link, `.torrent` file, or Android share/open intent
- Inspect file list, choose what to play (no "download everything" required)
- Stream any audio file immediately — play while downloading; seek works
- Queue/playlist, resume position per file, playback speed, sleep timer
- Background playback: notification, lock screen, Bluetooth/headset controls
- Library of added torrents with progress, keep/delete, storage use
- Optional "download for offline" per file/torrent

**Out of scope for v1**
- Built-in search / catalog / index of torrents (legal + store risk)
- Transcoding (FLAC/ALAC/Opus rejected only if the device can't decode them)
- Chromecast, Android Auto, cross-device sync, seeding ratio management
- Video playback, subtitles

**Legal stance**: the app ships with no index, no curated sources, no bundled torrents. Generic client, user-supplied magnets. This keeps it defensible and F-Droid-compatible.

## 2. Stack

| Concern | Choice | Why |
|---|---|---|
| Language / UI | Kotlin, Compose + Material 3 | standard |
| SDK | minSdk 26, target/compile 36 | covers ~97% devices, modern FGS rules |
| Torrent engine | **libtorrent4j** (libtorrent 2.x, BSD) | native performance, sequential download + piece deadlines + readPiece API, proven in Android clients |
| Streaming bridge | **localhost HTTP range server** | seek = HTTP Range; player-agnostic; debuggable with VLC; isolates engine threads |
| Player | **Media3 ExoPlayer + MediaSessionService** | notification, media buttons, audio focus, speed, chapters |
| Persistence | Room + DataStore | torrent/file/playback state; settings |
| DI | Hilt | |
| Images/tags | Coil; tags parsed lazily from available bytes | cover art, artist/album |

Single Gradle module (`:app`) with strict package boundaries (`torrent/`, `stream/`, `playback/`, `data/`, `ui/`). Split into modules only if build times hurt.

## 3. Architecture

```
UI (Compose)
  └─ ViewModels
       ├─ TorrentRepository ── libtorrent4j SessionManager ── swarm/disk
       │                             │
       │                        PieceCache (LRU, in-flight dedupe)
       │                             │
       └─ PlaybackController ── ExoPlayer ── HTTP ── StreamServer (127.0.0.1, token)
                                                      └─ TorrentDataSource
MediaSessionService (mediaPlayback FGS) hosts engine + stream server
```

### 3.1 Torrent session
- One `SessionManager` for the app lifetime; settings pack tuned for mobile: DHT on, LSD/UPnP off by default, upload limit 0 (v1), limited connections, small disk cache.
- Torrents restored from Room on startup (paused).
- Metadata: `magnet` → `add_torrent_alert` → `metadata_received_alert` → file list.
- Per-file priority policy:
  - playing file = 7, next queued file = 4, everything else = 0
  - `setSequentialDownload(true)` on the playing file
- Piece deadlines for the playhead window (see 3.3). Clear deadlines when the window moves.

### 3.2 Stream server
- `ServerSocket` bound to `127.0.0.1:0`, unguessable path token, no external interfaces.
- `GET /s/{infoHash}/{fileIndex}?t={token}` with `Range`, `HEAD`, 206 responses, correct `Content-Length`/`Content-Range`.
- For fully downloaded files, serve via `FileInputStream` (skip the torrent path entirely — battery).
- Failure semantics: if no pieces arrive within N seconds, return 504 and the player surfaces "source stalled" instead of spinning forever.

### 3.3 Streaming read path (the core risk)
- Map byte offset → torrent piece via `torrentInfo.files().fileOffset(fileIndex)`. A piece may span two files; handle the sub-range.
- `readPiece(pieceIndex)` → await `read_piece_alert`; never assume the piece is on disk yet.
- `PieceCache`: LRU of decoded pieces (~16 MB) + in-flight request dedupe, so ExoPlayer's overlapping reads don't re-request.
- Read-ahead window: **8–16 MB ahead of playhead** (at 64–320 kbps that's 3–30 minutes of audio). Set piece deadlines only for that window, which keeps battery/bandwidth low and makes seek cheap.
- On seek: reposition window, re-arm deadlines, keep file priority. Target: first audio within ~1–2 s on a healthy swarm; <5 s on a thin one.
- MP4/M4B caveat: if `moov` is at the end of the file (non-faststart), normal sequential streaming fails. Plan: sniff MP4 boxes as bytes arrive; if `moov` is at the tail, prioritize the last ~2 MB before starting playback (and cache it). Fallback: play only after full download, with a clear UI message.
- M4B chapter metadata is exposed by Media3 for chapter navigation; not a v1 blocker.

### 3.4 Playback & background
- `MediaSessionService` with `mediaPlayback` foreground service type. Torrent traffic is legitimate buffering inside that service — avoids the Android 15 **6-hour `dataSync` FGS cap**.
- Engine runs only while a stream session is open. Pause torrent on playback pause + idle timeout (configurable, default 60 s), stop on queue end. No background seeding by default.
- `POST_NOTIFICATIONS` runtime permission on 33+; audio focus via Media3; becoming-noisy receiver.
- Playback state saved every few seconds → exact resume for 20-hour audiobooks.

### 3.5 Library / data model
`Torrent(infoHash PK, name, addedAt, state, savePath)` · `TorrentFile(torrentId, index, path, size, mime, title, artist, album, trackNo, durationMs, wanted, downloadedBytes)` · `PlaybackState(fileId, positionMs, updatedAt)` · `QueueItem`

Tags/art: parse ID3v2 from the first bytes of MP3s (available early); for M4B read `udta`/`meta` once the head is downloaded. Fallback to folder/file-name heuristics (`Author - Series - 01 - Title.m4b`). Album art from `cover.jpg`/`folder.jpg` in the torrent.

## 4. Milestones

### M0 — Feasibility spike (1–2 days) ← do this first
1. Scaffold app (android CLI), add libtorrent4j AARs (arm64-v8a, armeabi-v7a), verify it loads on device.
2. Add a public-domain test torrent (Internet Archive audio, legal), list files, show swarm/progress.
3. Implement byte→piece reader + HTTP range server; play in VLC first, then ExoPlayer.
4. Seek test on a ~700 MB multi-file torrent: measure time-to-first-audio, seek latency, memory, battery.
**Kill criteria**: if seek is unreliable or >~5 s on a healthy swarm, stop and re-evaluate (alternative: download-file-then-play MVP).

### M1 — Playable core (3–5 days)
Add via magnet / `.torrent` / share intent · file picker · player screen · MediaSession + notification · background playback · seek/skip · resume.

### M2 — Library (3–4 days)
Room persistence · torrent list + progress · keep/delete + storage accounting · queue · tags & cover art · grouping by author/album.

### M3 — Robustness & audio UX (3–5 days)
moov-atom tail handling · dead-torrent/stall errors · network change handling (Wi-Fi ↔ mobile, metered guard) · playback speed · sleep timer · chapter navigation · per-file download-for-offline · upload/connection limits.

### M4 — Release (1–2 days)
Signing, app icon/name, license (GPL-3 or Apache-2 — check libtorrent4j BSD compatibility), privacy statement (no telemetry), GitHub release APK, F-Droid metadata if desired.

Rough total: 2–3 focused weeks.

## 5. Risks

| Risk | Mitigation |
|---|---|
| Multi-file sequential streaming is fiddly | M0 spike before any UI; per-file priorities + piece deadlines; HTTP layer keeps player decoupled |
| Dead/thin swarms | Explicit stall timeouts and messages; show seed/peer counts before play; allow full-download fallback |
| Android FGS restrictions (15/16) | mediaPlayback FGS only, no dataSync worker; pause engine when not playing |
| OEM battery killers (Xiaomi/Huawei) | document exclusion; do not fight the OS |
| Battery / mobile data | small read-ahead, stop engine on pause, metered-network warning, limits |
| MP4 `moov` at EOF | tail-first priority + fallback |
| Play Store policy | ship GitHub/F-Droid first; keep "generic client" framing if ever submitting |
| Copyright | no index/search; user-supplied magnets only |

## 6. Test fixtures (legal)
- Internet Archive item `.torrent` files (public-domain audiobooks, live music)
- A deliberately single-file 300 MB MP3, a 30-track album folder, an M4B audiobook, one non-faststart MP4 to exercise the moov path

## 7. Open questions
- App name / branding
- Allow uploading (seeding) at all? (v1 default: no)
- Android Auto important? (would pull M3 forward)
- Primary test device + Android version
