# Changelog

## v0.2.0-alpha.1

This alpha ships smaller architecture APKs, downloads media tools after opening, and checks GitHub for app updates.

- Separate APKs for ARM64, ARMv7, x86_64, and x86. Download the APK that matches your device. Most recent phones use ARM64.
- Optimized APKs are about 6.4 MB. First setup downloads only the device's Python, FFmpeg, and yt-dlp packages. The later download and installed storage still apply.
- Media setup shows progress, supports pause and resume, verifies package checksums, and retains completed tools for offline use. Saved media remains usable during setup.
- App update checks run on opening. Updates show their version and download size. The app verifies the checksum, package, version, architecture, and signing key before Android asks to install.
- Bootstrap yt-dlp is updated to 2026.10.26. The app also refreshes the extractor automatically and retries a YouTube 403 once with a freshly inspected source link.
- Setup controls adapt to shorter screens. Settings includes full media setup and app update controls.
- CI validates assets, runs JVM tests and lint, and builds all four APKs. The release workflow signs the APKs and publishes the runtime packages, update metadata, checksums, and this changelog.

The APKs keep the existing alpha's development signing key so they can install over the current alpha. Development signing does not affect YouTube's media-server responses. A production signing key will need a separate migration.

Public media support still depends on YouTube and the network. The reported phone-specific 403 has not been reproduced. Private media, sign-in challenges, DRM, playlists, multi-video posts, and active livestreams remain outside this alpha.

## v0.1.0-alpha.1

First public Android alpha with local extraction and conversion, a persistent download queue, file imports, Library and History, offline playback, themes, and verified extractor updates. This release used development signing.
