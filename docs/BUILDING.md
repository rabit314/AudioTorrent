# Building AudioTorrent

## Requirements

- JDK 17, matching the project's configured Java toolchain.
- Android SDK Platform 36 and Android SDK Build Tools installed through Android Studio or the SDK manager.
- An Android device or emulator running Android 8.0 (API 26) or newer, with an `arm64-v8a` or `x86_64` ABI.
- Network access for Gradle and Maven dependencies on the first build.

Use the committed Gradle wrapper. Android Studio must support the Android Gradle Plugin version in `gradle/libs.versions.toml`.

## Configure the SDK

Open the repository in Android Studio and let it configure the SDK location, or set `ANDROID_HOME` to your SDK installation. A local `local.properties` file containing `sdk.dir` is another option; keep this machine-specific file out of Git.

The required libtorrent4j native libraries are committed in `app/src/main/jniLibs/`. Keep them in place when building.

## Build and check

From the repository root on macOS or Linux:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

From PowerShell on Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Unit test reports are under `app/build/reports/tests/`; lint reports are under `app/build/reports/`.

## Install and run

Enable USB debugging and connect a supported device or start an emulator. With `adb` on your PATH:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.rivulet/.MainActivity
```

If more than one device is connected, pass `-s SERIAL` to each `adb` command.

Instrumented tests require a connected device or emulator:

```bash
./gradlew :app:connectedDebugAndroidTest
```

On Windows, substitute `.\gradlew.bat` for `./gradlew`.

## Release builds

Debug builds use Android's development signing setup. The app's release configuration reads a private root-level `keystore.properties` file, when present, with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` properties. Keep this file and the keystore outside version control.

Distribute APKs through GitHub Releases rather than committing generated packages to the source tree.
