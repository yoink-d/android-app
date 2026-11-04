# yoink'd for Android

The native Kotlin and Jetpack Compose port of [yoink'd for iOS](https://github.com/maynkudu/yoinkd). The Android application ID is com.maynkudu.yoinkd.

Check a public media link, choose Video or Audio, select the source quality and saved format, and download it to your phone. Extraction and conversion run locally with yt-dlp, CPython, QuickJS, and FFmpeg. The app needs no companion server.

Downloads use a persistent queue and a foreground service. Pause, resume, retry, and cancellation retain checkpoints where the source supports them. Media enters the library after validation, a verified file copy, and a database commit.

The library supports imports, search, filters, sorting, grid/list display, rename, sharing, deletion, and offline audio/video playback. Settings includes folder selection, appearance, six themes, media setup, app updates, and verified extractor updates with rollback. The original brand artwork, Stack Sans fonts, and Rune icons are included.

Android 10 or newer is required. Separate APKs support arm64-v8a, armeabi-v7a, x86_64, and x86. Most recent phones use arm64-v8a. Public media support depends on yt-dlp and the source website. Private links, sign-in challenges, DRM, playlists, multi-video posts, and active livestreams are outside this version.

[Download the latest alpha](https://github.com/yoink-d/android-app/releases) and read its [changelog](CHANGELOG.md).

## First opening and updates

Optimized architecture APKs are about 6.4 MB. On first opening, the app downloads Python, FFmpeg, and a pinned yt-dlp for the installed architecture. The initial media setup downloads about 46 to 56 MB, depending on architecture. Moving these packages reduces the APK download; the later download and installed storage still apply.

Setup runs in the background, verifies SHA-256 checksums, and activates a complete runtime. Pause, resume, and retry retain valid transfer checkpoints. Installed tools and saved playback remain available offline. Link checking and conversion require completed setup. Settings has the full setup controls; a compact status card appears on taller screens.

The app checks GitHub releases on foreground opening, including alpha releases. A compatible update shows its version and download size. Downloading verifies its checksum, package, version code, architecture, signing certificate, and Android compatibility. Android asks for installation permission and confirmation. Offline and rate-limited checks leave the current app usable.

The extractor also refreshes automatically. A YouTube 403 triggers one recovery with an extractor refresh and a newly inspected source page. A persistent network or sign-in block remains an error. APK signing is separate from media-server 403 responses.

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

- app/build/outputs/apk/debug/app-<abi>-debug.apk is signed with the local development key and includes development tooling.
- app/build/outputs/apk/release/app-<abi>-release-unsigned.apk uses R8 and resource shrinking. A configured signing key produces app-<abi>-release.apk instead.
- app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk contains the device integration tests.
- app/build/media-runtime/releases contains the four architectures' Python and FFmpeg packages and runtime-manifest.json.

No signing keys are stored in the repository. On Windows, use gradlew.bat for the equivalent Gradle tasks. The Bash check script requires Bash.

## Signed releases and CI

Set YOINKD_KEYSTORE, YOINKD_STORE_PASSWORD, YOINKD_KEY_ALIAS, and YOINKD_KEY_PASSWORD in the build environment to sign optimized APKs. Keep the keystore outside this repository. All architecture APKs must use the same persistent signing key. A different key cannot replace the installed alpha.

The current alpha intentionally retains the published alpha's development signing key for installation compatibility. This is an optimized release build. Development signing is allowed only for numbered alpha releases when explicitly enabled.

The Android workflow runs asset verification, JVM tests, lint, and all architecture builds. The Release workflow runs on a version tag, signs and validates all APKs, and publishes them with the runtime packages, yoinkd-update.json, SHA256SUMS, and release notes extracted from CHANGELOG.md. A missing changelog stops publication.

The Release workflow uses the encrypted Actions secrets YOINKD_KEYSTORE_BASE64, YOINKD_STORE_PASSWORD, YOINKD_KEY_ALIAS, and YOINKD_KEY_PASSWORD. The temporary keystore stays outside the checkout and is removed after the job. The repository variable YOINKD_ALLOW_DEVELOPMENT_ALPHA must be true to retain development signing for an alpha. Stable releases require a production key.

Tags set the version name. Published version codes use 1000 plus the Release workflow's run number so later releases have a larger code. Local builds default to version code 2 and accept -PyoinkdVersionCode and -PyoinkdVersionName. Prepare local release assets with:

~~~sh
python3 scripts/package-release.py --tag v0.2.0-alpha.1 \
  --output build/releases/v0.2.0-alpha.1 --allow-development-key-for-alpha
~~~

Use an empty output directory. --allow-unsigned is for local validation and cannot produce an installable release. Runtime asset URLs are pinned when Gradle prepares the packages; -PruntimeReleaseTag can retain an existing package release when appropriate.

## Checks

~~~sh
python3 scripts/verify-assets.py
./gradlew :app:testDebugUnitTest :app:lintDebug
~~~

For the device tests, install the matching debug APK and prepare the cached fixtures. Supply the pinned yt-dlp file described by scripts/media-runtime.json. For an x86_64 emulator:

~~~sh
adb -s emulator-5554 install -r -g app/build/outputs/apk/debug/app-x86_64-debug.apk
python3 scripts/device-fixtures.py --abi x86_64 --extractor /path/to/yt-dlp \
  --device emulator-5554 --seed-runtime
./gradlew :app:connectedDebugAndroidTest
~~~

The fixture script reuses cached native packages, validates the extractor hash, and creates signed test APKs. Its temporary test signing key is deleted immediately. It does not download media packages. Match the device ID and ABI to your test device.

The 18 integration tests exercise HTTPS setup, interruption/resume, checksum failure, offline retry/reopen, APK signing and identity checks, native conversion, process cancellation, verified saves, stale attempts, service ownership, and interrupted-save recovery. Runtime and database checks use isolated private directories and uniquely named public files. Update tests use the provider's real cache path and restore any earlier update pointer.

[Verification results](docs/verification.md) distinguish executed checks, fixture-based checks, and remaining device scenarios. GitHub Actions runs the checks that do not require a device and uploads APKs and reports.

## Project

- [Architecture and file responsibilities](docs/architecture.md)
- [Feature mapping from iOS](docs/features.md)
- [Approved implementation plan](.plannotator/android-port/plan.md)
- [Pinned iOS source](reference/ios/COMMIT.txt)

Production code lives under app/src/main/java/com/maynkudu/yoinkd. Feature screens, domain models, repositories, native extraction, file storage, and the download service have separate packages. Local prototypes and session files stay outside the committed app.

## License

The Android app is distributed under [GPL-3.0](LICENSE), consistent with its bundled extraction and FFmpeg dependencies. Fonts and icons retain their own licenses. [Third-party notices](app/src/main/assets/third-party-notices.txt) also ship in the app.
