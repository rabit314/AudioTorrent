# AudioTorrent

**An audio-first BitTorrent client for Android.**

Listen while your audio downloads. Keep your place across chapters, save bookmarks, and download books for offline listening.

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Status: beta](https://img.shields.io/badge/status-beta-blue)

[Build from source](docs/BUILDING.md) Â· [Architecture](docs/ARCHITECTURE.md) Â· [Contributing](CONTRIBUTING.md) Â· [Report a bug](https://github.com/rabit314/AudioTorrent/issues/new?template=bug_report.yml)

## Features

- **Stream as you download** â€” open magnet links or `.torrent` files and choose an audio track.
- **Pick up where you left off** â€” saved listening positions, history, and a Continue Listening shortcut.
- **Navigate long books** â€” naturally sorted chapter queues, skip controls, and bookmarks with notes.
- **Listen your way** â€” playback speeds from 0.5Ã— to 3Ã—, silence skipping, voice boost, and a sleep timer with fade-out.
- **Keep audio available** â€” download individual files or entire torrents for offline playback.
- **Play in the background** â€” media notifications, lock screen controls, and headset commands through Android Media3.
- **Follow along** â€” attach LRC, SRT, EPUB, or plain-text companion files. Plain-text timing is estimated.
- **Find your next listen** â€” AudiobookBay search, trending titles, recent searches, and a Want to Listen shelf.

## Getting started

Build and install a debug APK using the [build guide](docs/BUILDING.md). Android 8.0 (API 26) or newer is required; bundled native libraries support `arm64-v8a` and `x86_64` devices.

1. Tap **+** in the library to paste a magnet link or choose a `.torrent` file. You can also open supported links from another Android app.
2. Select an audio file to begin playback. Startup and seeking depend on available peers and your connection.
3. Use the player for chapter navigation, bookmarks, playback speed, and the sleep timer.

Audio format support depends on the device's decoders. Discovery depends on a third-party website and may be unavailable if that site changes or requires authentication.

## Development

The app uses Kotlin, Jetpack Compose, Media3, libtorrent4j, and SQLite in a single Android module. The Android package and internal project name remain `com.example.rivulet` and `Rivulet`.

```bash
git clone https://github.com/rabit314/AudioTorrent.git
cd AudioTorrent
./gradlew :app:assembleDebug
```

On Windows, use `.\gradlew.bat :app:assembleDebug`. See [Building](docs/BUILDING.md) for prerequisites, tests, and installation, and [Architecture](docs/ARCHITECTURE.md) for the source map.

## License

A project license has not yet been specified. Dependencies retain their respective licenses.

Use AudioTorrent with audio you are authorized to download and share.
