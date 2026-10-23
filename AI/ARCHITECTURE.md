# Rivulet Android architecture

**Updated by:** Antigravity (Gemini 3.8 Flash)  
**Verification:** Source code implementation, Gradle unit tests (`gradlew test`), and APK build (`gradlew assembleDebug`).

## Build/package

- Android single Gradle module `:app` (`settings.gradle.kts`).
- Kotlin 2.3.20 / Jetpack Compose / Material 3 (Gradle version catalog).
- Application/namespace `com.example.rivulet`; root Gradle project name `Rivulet`.
- `app/src/main/jniLibs/{arm64-v8a,x86_64}/libtorrent4j.so` bundled for 64-bit ARM and x86 devices.

## Code map

| Component | Source | Implemented responsibility |
|---|---|---|
| Entry/activity and intents | `MainActivity.kt` | Entry UI, magnet uri & `.torrent` file intent dispatching |
| Application wiring | `RivuletApp.kt` | App-level DI container (`AppContainer`), binding engine, database, server, player |
| Persistence layer | `data/TorrentDatabase.kt` | SQLite database storing torrents, per-file playback resume positions, offline priorities |
| Data types | `data/Models.kt` | UI models for torrents, audio files, progress, download states |
| Torrent engine | `torrent/TorrentEngine.kt` | libtorrent4j session manager, torrent restoration, piece priority management, download management |
| Stream session | `stream/StreamSession.kt` | File/piece mapping, read-ahead window deadlines, MP4/M4B tail piece prioritization |
| Local HTTP bridge | `stream/StreamServer.kt` | Loopback HTTP/1.1 range server delivering byte-range chunks to player |
| Media Session Service | `playback/RivuletMediaService.kt` | Android MediaSessionService with `mediaPlayback` foreground service type, system notification & lock screen controls |
| Player controller | `playback/PlaybackController.kt` | Media3 ExoPlayer wrapper, track queue, auto-play next track, exact resume, speed control, sleep timer |
| Navigation | `Navigation.kt`, `NavigationKeys.kt` | Library → torrent detail → player navigation with back stack |
| UI | `ui/library/`, `ui/torrent/`, `ui/player/` | Jetpack Compose screens for Library, Torrent Detail, and Player |
| Formatting utilities | `ui/Format.kt` | Byte formatting, download rate formatting, and time/duration formatting |

## Data Flow

```text
Incoming Magnet / .torrent -> MainActivity -> TorrentEngine -> TorrentDatabase (SQLite)
                                                     │
                                            SessionManager (libtorrent4j)
                                                     │
                                                StreamSession (sequential + deadlines + MP4 tail)
                                                     │
                                                StreamServer (127.0.0.1 HTTP range server)
                                                     │
                                                PlaybackController (ExoPlayer)
                                                     │
              ┌──────────────────────────────────────┴──────────────────────────────────────┐
              │                                                                             │
     RivuletMediaService                                                               Compose UI
(Foreground Service + Notification)                                         (Library, Torrent, Player screens)
```

## Implemented Audio UX Highlights
1. **Background Playback**: Foreground service type `mediaPlayback` with full system media notification and lock screen playback controls.
2. **Durable Persistence**: All added torrents and playback positions survive app process restarts.
3. **Queue & Auto-advance**: Automatically plays the next track in an audiobook or album when the current track completes.
4. **Playback Speed**: Configurable playback speeds from 0.75× to 2.0×.
5. **Sleep Timer**: Flexible sleep timer with 15m, 30m, 45m, 60m countdown or pause at track end.
6. **Offline Download**: Per-file and torrent-wide offline download prioritization.
7. **Library Management**: Delete torrent options (delete metadata only or delete metadata + files), total storage accounting, and mini-player bar.
8. **MP4/M4B Optimization**: Non-faststart MP4 files with `moov` at EOF are handled by arming piece deadlines for the last 2 MB during stream startup.
