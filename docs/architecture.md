# Architecture

The app uses one Android module, Kotlin, Jetpack Compose, Room, coroutines, and Media3. AppContainer constructs the repositories and services with constructor injection. ViewModels expose state to screens. UI code does not execute native processes or write SQL.

## File responsibilities

| Package | Responsibility |
| --- | --- |
| app | Dependency construction, navigation, incoming share/deep-link requests, sharing, and artwork theme selection. |
| core/model | Links, source formats, output support, queue states, preferences, and file naming. |
| core/design | Shared Compose controls, original fonts, palette calculation, contrast, and artwork clustering. |
| data/database | Room entities, DAOs, schema, and transactional metadata. |
| data/extraction | yt-dlp, native process ownership, FFmpeg, media probing, and verified extractor updates. |
| data/storage | HTTPS transfers, resume validators, MediaStore/document-provider access, artwork, and recoverable file operations. |
| download | Scheduling, acquisition, processing, saving, connectivity, and the foreground service. |
| feature | Capture, Library, Downloads, History, Player, and Settings screens and ViewModels. |

## Capture and downloads

Capture validates the HTTPS link and inspects the source before the user confirms a download. Source selection and saved format are independent. Source sizes can be reported, estimated, or unavailable. FFmpeg availability determines the offered output formats.

DownloadEngine persists the job before requesting the foreground service. DownloadRules allows two occupied slots and one source acquisition. Direct HTTPS transfers can run alongside source acquisition. Source webpages are inspected again for every attempt, so jobs do not persist expiring stream URLs.

Each attempt has a unique ID. JobRepository rejects late writes from stopped or replaced attempts. Pause and cancellation wait for owned work to stop before a slot is released. NativeProcessRunner starts a process group for each request so cancellation also stops its child processes.

The foreground data-sync service displays progress and holds a bounded wake lock. Service ownership prevents an old service from pausing a newer service's work. Interrupted active jobs restore as paused. Android's foreground-service timeout pauses work and retains checkpoints.

## Files and transactions

Default media files use MediaStore in Downloads/yoink'd. A selected folder uses a persistent document-tree grant. Metadata, artwork, native runtimes, and temporary files remain private.

MediaStorage probes the prepared media and verifies the public copy with SHA-256. A Room transaction commits the library row and completed job together. Pending file operations record recovery work for saves, deletion, and relocation.

Relocation copies and verifies managed files before switching database paths and folder preferences. It rejects existing destination names. This also prevents deletion through two URIs that refer to the same physical file. Unfinished jobs block folder changes. Imports and file operations share a mutex.

Clearing history deletes terminal jobs and their checkpoints. It preserves library rows and saved media. Removing a library item leaves its download history identifiable.

## Playback and updates

Media3 plays saved files offline. Video uses one player across embedded and fullscreen views. Unsupported ALAC, AIFF, CAF, or video codecs can use a private playback conversion while preserving the saved original. Dismissing the player clears its ViewModel store and releases the player.

ExtractorUpdates downloads official yt-dlp releases over HTTPS, verifies SHA-256, checks the version and extractor loading, and switches an atomic active pointer. The previous verified generation remains available for rollback. CPython, QuickJS, FFmpeg, and bundled certificates update with the app.

## Reference and generated files

The iOS source is pinned at 0c6ba72b046b3a250d8f27754bc6090d2217f264 under reference/ios. Asset verification compares the packaged fonts, artwork, and icon geometry to the pinned files.

Room schema snapshots are generated under app/schemas and committed. Gradle outputs, local SDK paths, signing keys, prototypes, and session files are ignored. The approved plan and its representative capture evidence remain available under .plannotator/android-port.
