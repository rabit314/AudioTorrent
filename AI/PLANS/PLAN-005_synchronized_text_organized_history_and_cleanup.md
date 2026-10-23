# PLAN-005 v3.0 — Sliding-Page Synchronized Text, Organized Listening History & Layout Cleanup

- **Date:** 2026-10-23T17:28:00+06:00
- **Author:** Antigravity (Gemini 3.8 Flash)
- **Role:** Planner / Researcher
- **Status:** DRAFT (Awaiting User Review)

## 1. Executive Summary
Incorporates the user's sliding-window / page-cached text concept:
1. **Sliding-Page Synchronized Text (Read & Listen):** Rather than loading or processing an entire 500-page book at once, Rivulet treats text like the audio streaming engine: caching "a page or so" (~1–2 minutes of speech / ~250 words per page) around the playhead into SQLite, rendering Spotify-style lyrics with line glow, auto-scroll, and tap-to-seek.
2. **Organized Listening & File History:** Chapter-level granularity grouped chronologically (**Today**, **Yesterday**, **Earlier**), while strictly preserving the current front-page layout.
3. **Remove Redundant Side Drawer:** Clean removal of `ModalNavigationDrawer` and hamburger menu since AudiobookBay is directly integrated on the front page.

## 2. Work Breakdown
- `TASK-022`: Remove `ModalNavigationDrawer` & hamburger menu for clean front-page layout.
- `TASK-023`: Implement DB v4 `playback_history` and `page_text_cache` in `TorrentDatabase.kt`.
- `TASK-024`: Implement dedicated `HistoryScreen.kt` (Today / Yesterday / Earlier) with chapter details and resume.
- `TASK-025`: Implement `PageTextManager.kt` (rolling 3-page buffer, word-paced slicing, SQLite page cache).
- `TASK-026`: Integrate page text state, active line lookup & seekTo in `PlaybackController.kt`.
- `TASK-027`: Build `LyricsView.kt` Spotify-style auto-scroll Compose UI & Player drawer card in `PlayerScreen.kt`.
- `TASK-028`: Unit tests in `PageTextCacheTest` & `ListeningHistoryTest`, and live wireless deployment to Redmi 10.
