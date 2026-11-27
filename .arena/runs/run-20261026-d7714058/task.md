# AudioTorrent Android Audiobook App — Bug Diagnosis & Remediation Task

## Overview & Context
AudioTorrent is an Android native audiobook streaming, offline listening, and read-along app built with Kotlin, Jetpack Compose, Media3 ExoPlayer, and libtorrent4j. The app supports streaming torrent audiobooks from AudiobookBay, caching chapters, playing with ExoPlayer, and aligning companion ebooks (.epub, .lrc, .srt) with audio using on-device whisper.cpp.

## Task
Audit the codebase, identify existing and latent bugs, and formulate a comprehensive, robust diagnosis and patch plan covering the following known problem areas:

1. **Audio Decoding & Resampling (`AudioWindowDecoder.kt`):**
   - Address `BUG-063`: Examine the resampling loop in `AudioWindowDecoder.kt`. When decoding audio files with sample rates below 16 kHz (e.g. 8 kHz, 11.025 kHz), verify why the decoder produces insufficient PCM samples for 16 kHz whisper.cpp inference. Provide a correct upsampling/resampling solution that works reliably across arbitrary audio sample rates.

2. **Audio UX & Player Controls (`PlayerLayout.kt`, `PlayerScreen.kt`):**
   - Restore missing user-facing controls (Sleep Timer dialog/countdown, Trim Silence toggle, and Skip Interval configuration) that exist in `PlaybackController` but are missing or omitted from the player controls row in `PlayerLayout.kt`.

3. **Torrent Stream & Swarm Stability (`StreamSession.kt`, `TorrentEngine.kt`, `AudioBookBayConfig.kt`):**
   - Address torrent piece acquisition and metadata timeouts:
     - Review `TorrentFlags.SEQUENTIAL_DOWNLOAD` in `StreamSession.kt` vs. individual piece deadlines for multi-track audiobooks.
     - Review dead trackers in `AudioBookBayConfig.DEFAULT_TRACKERS` and replace with verified active public trackers.
     - Review synchronous 45s `session.fetchMagnet` blocking under `addLock` in `TorrentEngine.kt`.

4. **Navigation & Edge Cases (`Navigation.kt`):**
   - Review root back stack behavior in `NavDisplay` to prevent popping the main screen and leaving an empty backStack.

## Rules & Constraints
- Subagents MUST NOT edit files outside their assigned `.arena` run folder.
- Do not modify production application code.
- Provide clean, production-grade Kotlin diffs / patches, root-cause explanations, and verification test designs.
- Strictly adhere to Android API 26+ compatibility and Media3 / Compose conventions.
