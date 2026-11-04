# Verification

The application ID is com.maynkudu.yoinkd and the display name is yoink'd. The Android adaptation uses the pinned iOS commit 0c6ba72b046b3a250d8f27754bc6090d2217f264.

## v0.2.0-alpha.1 checks

The local checks use Arch Linux, OpenJDK 21, Gradle 8.11.1, SDK 35, and Build Tools 35.0.0. The signed build used the existing alpha keystore outside the repository and version code 1001.

~~~sh
./scripts/check.sh -PyoinkdVersionCode=1001
python3 scripts/package-release.py --tag v0.2.0-alpha.1 \
  --output build/releases/v0.2.0-alpha.1-signed --allow-development-key-for-alpha
~~~

Asset verification, all 31 JVM tests, lint, four debug APKs, four optimized signed APKs, and the instrumentation APK passed. Lint reports zero errors and 74 warnings. Most warnings concern dependency versions and existing Android API usage. The new warnings include the screen-height query and another suggested Kotlin extension. [Machine-readable results](verification/checks.json) record the suites, warnings, signing certificate, APK hashes, and runtime sizes.

All 18 Android integration tests passed on the Pixel 8 emulator with Android 15 and x86_64. [Runtime and update results](verification/runtime-update-tests.log) cover HTTPS setup, pause/resume, checksum rejection, offline retry/reopen, same-key APK validation, foreign keys/packages/architectures, older versions, and a rate-limited update check. [Conversion and persistence results](verification/native-integration-tests.log) cover the offered audio outputs, video conversion, native cancellation, verified saves, stale attempts, service ownership, and interrupted-save recovery.

These network tests use local HTTPS fixtures and cached, verified native packages. They exercise the production transfer, verification, extraction, and installation-handoff code. They do not claim a fresh public GitHub runtime download.

## Signed local artifacts

| APK architecture | Compressed APK bytes | First media setup bytes |
| --- | ---: | ---: |
| arm64-v8a | 6,411,206 | 53,003,907 |
| armeabi-v7a | 6,360,776 | 46,372,515 |
| x86 | 6,452,376 | 51,597,200 |
| x86_64 | 6,455,951 | 55,971,341 |

Each APK contains one ABI and the four installed media launchers. Large Python/FFmpeg archives and yt-dlp are excluded. Packaging verified every runtime asset and release checksum. First setup includes the pinned yt-dlp download; installed storage remains larger than the APK.

The signing certificate matches the published v0.1.0-alpha.1 APK. The older certificate check used a bounded 128 KB download rather than downloading the entire old APK. Keeping this key allows Android to update the current alpha.

## Device behavior

The manual media checks use the production application ID with the downloaded runtime generation and yt-dlp 2026.10.26. The debug app uses version code 2. The final optimized APK is separately built and signed at code 1001.

- A setup failure leaves the Library usable. Retry in Settings completed setup from verified cached packages. [Failure](verification/screenshots/media-setup-failed.png), [compact status](verification/screenshots/media-setup-banner.png), and [landscape Settings](verification/screenshots/media-settings-landscape.png) record these states.
- The update prompt fits at the larger accessibility text setting in portrait and landscape. [Portrait](verification/screenshots/app-update-large-text.png) and [landscape](verification/screenshots/app-update-landscape.png) show the actual controls. The original text size was restored afterward.
- A cached, signed local alpha APK reached Android's installation permission screen and update confirmation. Installation was cancelled so the public GitHub update can be checked after publication. [Android confirmation](verification/screenshots/android-update-confirmation.png) records the handoff.
- The short public YouTube sample jNQXAC9IVRw downloaded at the lowest offered 240p resolution with AV1 source 395. The transfer was 475,961 bytes, including audio. The saved 1,005,782-byte MP4 contains H.264 video at 320 by 240 and AAC audio. Playback worked after an offline relaunch with airplane mode enabled and Wi-Fi disabled. [Completed download](verification/screenshots/youtube-lowest-quality-completed.png) and [offline playback](verification/screenshots/youtube-offline-playback.png) record the result.
- The same sample's YouTube audio downloaded at the lowest offered 106 kbps OPUS source, 251. The transfer was 252,182 bytes. Automatic output produced a 19-second FLAC file with artwork. FFprobe verified the saved file.
- The YouTube Music URL for the same short sample downloaded the lowest offered 106 kbps OPUS source, 251. It transferred 252,182 bytes and saved a 19-second FLAC file. FFprobe verified the saved file. [Download and probe results](verification/youtube-music.json) and [completed download](verification/screenshots/youtube-music-completed.png) record the result. Playback for this Music download remains unverified.

The device CLI cannot write this emulator's clipboard. Link-field input and an incoming audio deep link were exercised; the copied-link button path remains unverified. The original app's broader Library, import, sharing, folder, and theme workflows were not rerun for this change.

## Review and publication

The lightweight implementation direction was approved before production changes. The final diff was checked against the approved scope, and the JVM tests, lint, and signed release packaging were revalidated. The Plannotator implementation review was not rerun before the user directed publication. GitHub publication is pending. The release workflow builds and validates signed architecture APKs, uploads the assets to a draft, then publishes changelog-derived notes. The Android branch workflow checks code changes and avoids repeating the build for release tags or documentation-only updates.

The public release APK download, its real update offer, installation, and GitHub asset digests will be recorded after publication. The user requested leaving the occupied Pixel 8 emulator alone, so the public update installation and Music playback checks remain unverified. The local APK sizes and hashes above must not be assumed to equal the CI artifacts.

## Limits and earlier evidence

The reported physical-phone YouTube 403 has not been reproduced. Successful emulator samples on this network do not establish behavior on the user's phone. ARM64, ARMv7, and x86 native execution remains unverified on devices.

A full fresh public GitHub runtime download has not been repeated. Cached packages and local HTTPS tests save bandwidth while testing setup and recovery. Provider-specific failures, private links, sign-in challenges, DRM, playlists, multi-video posts, and active livestreams remain outside this alpha.

The earlier Android-port session downloaded a 632,171-byte YouTube MP4 and exercised offline landscape fullscreen. [Its screenshot](verification/screenshots/offline-fullscreen.png) and [review decision](verification/review.json) remain historical evidence for that version. They do not verify this release. Full iOS pixel fidelity is not the acceptance criterion for the approved Android adaptation.
