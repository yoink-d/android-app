# yoink'd for Android

The native Kotlin and Jetpack Compose port of [yoink'd for iOS](https://github.com/maynkudu/yoinkd). The Android application ID is com.maynkudu.yoinkd.

Check a public media link, choose Video or Audio, select the source quality and saved format, and download it to your phone. Extraction and conversion run locally with yt-dlp, CPython, QuickJS, and FFmpeg. The app needs no companion server.

Downloads use a persistent queue and a foreground service. Pause, resume, retry, and cancellation retain checkpoints where the source supports them. Media enters the library after validation, a verified file copy, and a database commit.

The library supports imports, search, filters, sorting, grid/list display, rename, sharing, deletion, and offline audio/video playback. Settings includes folder selection, appearance, six themes, and verified extractor updates with rollback. The original brand artwork, Stack Sans fonts, and Rune icons are included.

Android 10 or newer is required. Builds include arm64-v8a for phones and x86_64 for emulators. Public media support depends on yt-dlp and the source website. Private links, sign-in challenges, DRM, playlists, multi-video posts, and active livestreams are outside this version.

## Build

Use JDK 17 or 21, Python 3, and Android SDK Platform 35 with Build Tools 35.0.0. JDK 21 was used for the recorded checks. Android Studio can open this folder directly.

Set ANDROID_HOME to your SDK directory or set sdk.dir in an untracked local.properties file. Set JAVA_HOME to a supported JDK if your system default is newer.

On this Arch Linux setup:

~~~sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=/opt/android-sdk
./scripts/check.sh
~~~

The wrapper downloads Gradle 8.11.1 and verifies its SHA-256. Dependency versions are pinned in [gradle/libs.versions.toml](gradle/libs.versions.toml).

Build outputs:

- app/build/outputs/apk/debug/app-debug.apk is signed with the local development key and can be installed for testing.
- app/build/outputs/apk/release/app-release-unsigned.apk uses R8 and needs your release signature before installation.
- app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk contains the device integration tests.

No signing keys are stored in the repository. On Windows, use gradlew.bat for the equivalent Gradle tasks. The Bash check script requires Bash.

## Checks

~~~sh
python3 scripts/verify-assets.py
./gradlew :app:testDebugUnitTest :app:lintDebug
~~~

With a connected Android emulator or test phone:

~~~sh
./gradlew :app:connectedDebugAndroidTest
~~~

The integration tests use an isolated database, private test directories, and uniquely named public files. They exercise native conversion, process cancellation, verified saves, stale attempts, service ownership, and interrupted-save recovery.

[Verification results](docs/verification.md) distinguish current build checks, earlier emulator evidence, and remaining device scenarios. Full runtime parity has not been verified. GitHub Actions runs the checks that do not require a device and uploads the APKs and reports.

## Project

- [Architecture and file responsibilities](docs/architecture.md)
- [Feature mapping from iOS](docs/features.md)
- [Approved implementation plan](.plannotator/android-port/plan.md)
- [Pinned iOS source](reference/ios/COMMIT.txt)

Production code lives under app/src/main/java/com/maynkudu/yoinkd. Feature screens, domain models, repositories, native extraction, file storage, and the download service have separate packages. Local prototypes and session files stay outside the committed app.

## License

The Android app is distributed under [GPL-3.0](LICENSE), consistent with its bundled extraction and FFmpeg dependencies. Fonts and icons retain their own licenses. [Third-party notices](app/src/main/assets/third-party-notices.txt) also ship in the app.
