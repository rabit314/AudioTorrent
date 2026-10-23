# PLAN-005 v1.0 — Synchronized Text & Spotify-Style Audiobook Lyrics (Read & Listen)

- **Date:** 2026-10-23T17:22:00+06:00
- **Author:** Antigravity (Gemini 3.8 Flash)
- **Role:** Planner / Researcher
- **Status:** DRAFT (Awaiting User Review)

## 1. Executive Summary
Provide a synchronized "Read & Listen" (Spotify-style lyrics) experience in Rivulet:
- Auto-scrolling lyrics/transcript view tracking the narrator's voice.
- Dynamic highlighting of the currently spoken line/sentence.
- Tap-to-Seek: tapping any line seeks audio playback directly to that timestamp.
- Lightweight multi-tier text acquisition (LRC/SRT exact parser, companion EPUB/TXT chapter extractor, word-paced timing interpolation, and local file attachment).

## 2. Technical Feasibility Analysis
- **UI & Synchronization:** Trivial and highly performant on Android with Jetpack Compose (`LazyColumn`, `animateScrollToItem()`, `seekTo(ms)`).
- **Text Availability Challenge:** Audiobooks lack centralized lyric APIs (like Spotify's Musixmatch). We address this via:
  1. Auto-detecting `.lrc` and `.srt` subtitles in torrents.
  2. Auto-detecting companion `.epub` / `.txt` files in torrents.
  3. Word-paced proportional interpolation algorithm for un-timed text.
  4. Local file picker for attaching custom EPUB/LRC files.

## 3. Work Breakdown
- `TASK-022`: Create `LyricsModels.kt`, `LrcParser.kt`, `SrtParser.kt`, and `EpubExtractor.kt`.
- `TASK-023`: Implement `PacedTimingEngine.kt` for proportional sentence timing interpolation.
- `TASK-024`: Integrate transcript state, active line lookup, and line-seeking in `PlaybackController.kt`.
- `TASK-025`: Build `LyricsView.kt` Compose UI (Spotify-style auto-scroll, active line glow, tap-to-seek, sync fine-tuning).
- `TASK-026`: Integrate Lyrics sheet/card into `PlayerScreen.kt`.
- `TASK-027`: Add companion text detection in `TorrentEngine.kt`.
- `TASK-028`: Unit tests in `LyricsParserTest.kt` and live build deployment.
