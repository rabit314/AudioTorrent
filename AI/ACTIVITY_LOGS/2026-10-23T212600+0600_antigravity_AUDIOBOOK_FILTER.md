# Activity Log: Strict Audiobook Filtering & Non-Audio Media Blocker

**When:** 2026-10-23T21:26:00+06:00 (Asia/Dhaka)  
**Agent:** Antigravity  
**Model:** Gemini 3.8 Flash (Google)  
**Provider:** Google  
**Harness:** Antigravity IDE  
**Role:** Implementer / Tester  
**Task ID:** TASK-047  

## Work Performed

### 1. Root Cause Identification
- AudiobookBay community uploads sometimes include TV series episodes, movies, video rips, or ebooks miscategorized or uploaded by users.
- `AudioBookBayClient.parseListHtml` previously collected all `div.post` elements without verifying if the item represented a legitimate audiobook, causing video web-rips such as "VisionQuest S01E05 Firewall 1080p WEB-DL" and "Tulsa King S04E07 Blind Rooster 1080p" to appear in the "Trending on AudiobookBay" shelf.

### 2. Implementation
- Added `isLikelyAudioBook(title: String, format: String, category: String): Boolean` in `AudioBookBayClient.kt`:
  - **TV/Video Scene Patterns:** Blacklists standard scene release conventions, including `\bS\d{1,2}\s*E\d{1,2}\b`, `Season \d+ (Episode|Complete)`, video resolutions (`2160p`, `1080p`, `1080i`, `720p`, `576p`, `480p`, `360p`), UHD/4K flags (`\b(4k|uhd)\b`), rip tags (`web-dl`, `webrip`, `bluray`, `bdrip`, `brrip`, `dvdrip`, `hdtv`, `pdtv`), codecs (`x264`, `h.264`, `x265`, `hevc`, `xvid`, `divx`, `av1`), color depth (`10-bit`, `8-bit`), and release group artifacts (`remux`, `subbed`, `dubbed`).
  - **Non-Audio File Formats:** Rejects video and non-audio ebook containers: `mkv`, `avi`, `wmv`, `mov`, `flv`, `vob`, `iso`, `mp4 video`, `video`, `pdf`, `epub`, `mobi`, `cbr`, `cbz`, `azw3`.
  - **Non-Audio Categories:** Filters out non-audio categories like `tv show`, `television`, `movie`, `film`, `anime`, `video`, `ebook`, `comic`, `magazine` unless explicitly labeled with `audiobook`.
- Integrated `isLikelyAudioBook` inside `AudioBookBayClient.parseListHtml` so that Trending feeds, Category browsing, and Search results uniformly filter out non-audio releases.

### 3. Unit Tests
- Extended `AudioBookBayClientTest.kt`:
  - `testIsLikelyAudioBook_FiltersVideoReleases`: Verifies that TV episodes ("VisionQuest S01E05...", "Tulsa King S04E07...", "Breaking Bad S05E16..."), 4K movies, and non-audio formats (`MKV`, `AVI`, `EPUB`, `CBR`) are rejected.
  - `testIsLikelyAudioBook_AcceptsLegitimateAudiobooks`: Verifies that legitimate audiobooks and BBC radio dramatizations ("Victoria Station season 1 - Steve Chambers", "Dune - Frank Herbert", "Project Hail Mary", etc.) are accepted.
  - `testParseListHtml_FiltersOutVideoTorrents`: Verifies that parsing mixed HTML content drops video items and retains only valid audiobooks.
- Executed `./gradlew.bat test` — 37 tests passed (100% success).
- Executed `./gradlew.bat lintDebug` — 0 errors.

### 4. Device Verification
- Built `AudioTorrent.apk` and deployed to connected Redmi 10 (selene / Android 13) via ADB.
- Restarted application and captured live screenshot (`audiotorrent_home.png`).
- Confirmed that "Trending on AudiobookBay" displays only valid audiobooks ("Victoria Station season 2", "The Mulai", "No Stress Space Express") with zero TV or video releases.

## Changed Files
- `app/src/main/java/com/example/rivulet/data/audiobookbay/AudioBookBayClient.kt`
- `app/src/test/java/com/example/rivulet/AudioBookBayClientTest.kt`

**Signed by:** Antigravity — Gemini 3.8 Flash (Google)
