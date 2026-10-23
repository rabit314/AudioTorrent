# AudioTorrent / Rivulet — Project brief

**Current repository:** Android app named `Rivulet`, inside the combined `AudioTorrent/` folder.
**State:** Existing source code supplied by user; **not independently built or runtime-tested by the ZIP integrator**.
**Platform:** Android (Kotlin; Jetpack Compose; min SDK 26, target/compile SDK 36 from `app/build.gradle.kts`).
**Intent:** Generic audio-focused torrent streaming client; audiobooks, music, podcasts, long-form audio. `PLAN.md` is the original user-supplied roadmap.

## Existing source features/components (presence verified by static inspection only)

- Native libtorrent4j integration and torrent state representation (`torrent/TorrentEngine.kt`).
- A stream session with piece-window/priority logic (`stream/StreamSession.kt`).
- Loopback HTTP streaming service with range handling (`stream/StreamServer.kt`).
- Media3 ExoPlayer-based playback/controller (`playback/PlaybackController.kt`).
- Compose library, torrent detail, and player screens (`ui/library/`, `ui/torrent/`, `ui/player/`).
- Magnet and torrent-view/share intents in `AndroidManifest.xml`.
- Project contains existing unit and instrumented test source files, but no new tests were executed for this integration.

## Planned features requiring fresh verification or implementation

The original `PLAN.md` proposes background `MediaSessionService`, durable Room/DataStore persistence, queues, book progress, sleep timer, speed controls, chapter/metadata support, offline management, handling MP4 tail metadata, and release work. **Do not assume these are implemented.** The `PlaybackController` comments explicitly describe MediaSessionService as a future milestone.

## Product and scope reference

- Use `PLAN.md` for original goals, out-of-scope features, architecture intentions and milestones.
- Use `AI/ARCHITECTURE.md` for a cautious map of currently present code.
- Implementation decisions still need to be checked against the real source and user priorities.
- Do not add copyrighted catalogs or obtain content without appropriate rights.

## Suggested near-term goal

Assign an **audit/review agent** to map existing functionality against `PLAN.md`, run a reproducible debug build and controlled legal-media playback tests, then update the task backlog based on evidence.
