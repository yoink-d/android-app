# Verification

The Android application ID is com.maynkudu.yoinkd and the display name is yoink'd. The source comparison uses the pinned iOS commit 0c6ba72b046b3a250d8f27754bc6090d2217f264.

## Checks in this continuation

The checks use Arch Linux, OpenJDK 21, Gradle 8.11.1, compile/target SDK 35, and Build Tools 35.0.0. Run them with:

~~~sh
./scripts/check.sh
~~~

The script verifies pinned assets, runs JVM tests and Android lint, and builds the debug APK, device test APK, and R8 release APK. [Machine-readable results](verification/checks.json) record the final check results and lint warnings.

The JVM suite covers link validation, source variant selection, sizes, output availability, queue limits, interruption rules, safe file names, theme seeds, contrast, artwork fallback, and HTTPS resume behavior.

The final fixes address Media3 opt-in annotations, Activity access for fullscreen, application context ownership in playback, destination collisions during relocation, and file-name lookup failures during multi-file imports.

The completed implementation diff returned an approved decision from Plannotator with no change requests. The [review decision](verification/review.json) records that result. This approval does not replace the deferred device checks below.

## Earlier emulator evidence

The reference thread used a Pixel 8 on Android 15. Its recorded results include:

- The public YouTube link https://www.youtube.com/watch?v=jNQXAC9IVRw returned Me at the zoo, uploader jawed, duration, thumbnail, and actual AV1, VP9, and H.264 source variants.
- A production download saved a 632,171-byte MP4 in Downloads/yoink'd.
- The saved video played offline with airplane mode enabled and Wi-Fi disabled.
- Landscape fullscreen and playback controls were exercised.

[Offline fullscreen screenshot](verification/screenshots/offline-fullscreen.png) preserves the earlier production evidence. The [approved plan](../.plannotator/android-port/plan.md) contains the separate capture prototype screenshots and recording.

These results come from the earlier session. They do not verify changes made in this continuation.

## Deferred device checks

The Pixel 8 was owned by the earlier Device session. The user requested that remaining emulator checks be left for now. No emulator test result is claimed for this continuation.

The device test APK builds, but its nine integration tests have not been executed. Those tests cover all offered audio outputs, video conversion, native child-process cancellation, verified completion, history preservation, stale saves/progress, service ownership, and interrupted-save recovery.

Before a release, run:

~~~sh
./gradlew :app:connectedDebugAndroidTest
~~~

Also exercise the final APK's direct HTTPS download, pause/resume/cancel/retry, process restart, Library and History controls, multi-file import, incoming sharing/deep links, outbound sharing, folder relocation and collision handling, all themes, extractor update/rollback, large text, keyboard dismissal, and landscape layout. Inspect the saved files and app logs along with the visible result.

Physical-phone behavior, provider-specific failures, full functional parity, final UI behavior after these fixes, and iOS pixel fidelity remain unverified. Pixel fidelity is not the acceptance criterion for the approved Android adaptation.

The release APK is unsigned. Install the development APK for testing or sign the release APK with a separately managed key.
