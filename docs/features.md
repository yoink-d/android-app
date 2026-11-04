# Feature mapping

The source is [maynkudu/yoinkd at 0c6ba72](https://github.com/maynkudu/yoinkd/tree/0c6ba72b046b3a250d8f27754bc6090d2217f264). The approved direction is a native Android adaptation. The table describes implemented code. [Verification results](verification.md) record what has actually been checked.

| iOS workflow | Android implementation | Platform adaptation |
| --- | --- | --- |
| AddLinkView and SourceMedia | feature/capture, MediaInspector, SourceParser, SourceMedia | Compose sheet, HTTPS validation, cancellation, metadata, all usable variants, Video/Audio, and separate saved format. |
| NativeExtractor | ExtractorRuntime, SourceExtractor, MediaProcessor, MediaProbe | Bundled Android CPython, QuickJS, yt-dlp, and FFmpeg. Local extraction, stream acquisition, remuxing, conversion, metadata, and artwork. |
| DownloadQueue and DownloadRules | DownloadEngine, JobRepository, DownloadRules | Room-backed queue, attempt IDs, two slots, one source acquisition, checkpoints, pause/resume/retry, and terminal history. |
| SourceBackgroundWork | DownloadService, NetworkMonitor | Android foreground data-sync service, progress notification, network waiting, timeout handling, and service ownership. |
| MediaStorage and MediaFolderPreferences | MediaStorage, UriFiles, FileOperation | Public Downloads folder, document-tree selection, verified copies, collision rejection, and recoverable operations. |
| LibraryView and MediaLibrary | feature/library, LibraryRepository | Real files, multi-file imports, thumbnails, search, filters, sort, grid/list, rename, sharing, and deletion. |
| HistoryView | feature/history | Date groups, filters, search, sort, grid/list, retry, removal, and confirmed history clearing that preserves saved files. |
| MediaDetailView and MediaPlayback | feature/player | Media3 audio/video, artwork, artist, seek, 15-second skips, system volume, Info, Share, and landscape fullscreen. |
| AppTheme and AccentPalette | core/design, ArtworkThemeController | Original fonts and theme seeds, System/Light/Dark, six themes, readable colors, artwork-driven Dynamic, and neutral fallback. |
| SettingsView and ExtractorDependencies | feature/settings, ExtractorUpdates | File statistics, folder changes, appearance, installed/latest extractor versions, check/update/cancel, SHA-256 verification, and rollback. |
| CaptureRequest and CaptureInbox | IncomingRequest, MainActivity | Android text/media sharing, multiple shared files, and yoinkd://add?url=...&kind=audio or video, with confirmation before saving. |

Video outputs are Automatic, MP4, and MOV. Audio outputs are Automatic, M4A/AAC, AAC, MP3, FLAC, ALAC, WAV, AIFF, and CAF when supported by the bundled FFmpeg encoders and muxers. FLAC preserves decoded audio and cannot restore quality already lost in a lossy source.

Android controls, file access, sharing, notifications, and background execution follow Android APIs. iOS Liquid Glass and AVKit are replaced by Compose and Media3. iOS database migration, a server backend, login, cloud sync, DRM, playlists, multi-video posts, and active livestreams are not included.

The Android runtime update mechanism differs from iOS. yt-dlp can update independently with verification and rollback. EJS follows the extractor's compatible remote component. Native binaries and certificate data require an app update.
