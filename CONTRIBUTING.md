# Contributing

Bug reports, focused fixes, and documentation improvements are welcome. For larger behavior or architecture changes, open an issue to discuss the approach first.

## Report an issue

Include your Android version, device model, app version, steps to reproduce, and expected versus actual behavior. For playback issues, describe the audio format and whether the file was still downloading or fully available offline.

Use public-domain or otherwise authorized audio for reproducible examples. Remove credentials, personal library details, and private torrent information from logs and screenshots.

## Make a change

1. Follow the [build guide](docs/BUILDING.md) and read the [architecture overview](docs/ARCHITECTURE.md).
2. Create a branch and keep the change focused on one problem.
3. Follow existing Kotlin and Compose conventions. Add regression coverage when fixing logic bugs.
4. Run relevant unit tests, Android lint, and a debug build. For playback or lifecycle changes, also check behavior on a device or emulator.
5. Open a pull request explaining the problem, the resulting behavior, the checks you ran, and any remaining limitations. Include screenshots for visible UI changes.

Keep generated APKs, local SDK paths, signing credentials, scratch scripts, and private development notes out of commits. Keep the Gradle wrapper and required native libraries in the repository.

A project license has not yet been specified. Resolve licensing with the maintainer before submitting changes that depend on particular redistribution terms.
