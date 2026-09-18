# CloudDrive for Nextcloud

CloudDrive is a native Android virtual-drive client for a single Nextcloud account. It exposes cloud files in the app and through Android's Storage Access Framework without automatically uploading anything from the device.

## Requirements

- Android Studio with JDK 17
- Android SDK 36 and build-tools 36.x
- An Android 8.0+ device or emulator
- A Nextcloud server reachable over publicly/system-trusted HTTPS

## Build

```powershell
./gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Release signing

GitHub releases use a stable release key when all of these Actions repository
secrets are configured:

- `ANDROID_SIGNING_KEYSTORE`: the release keystore encoded as a single-line Base64 value
- `ANDROID_SIGNING_ALIAS`: the key alias in that keystore
- `ANDROID_SIGNING_STORE_PASSWORD`: the keystore password
- `ANDROID_SIGNING_KEY_PASSWORD`: the key password

Keep the keystore and passwords backed up. Every release must use the same key so
Android can install future versions as updates.

If these secrets are absent or incomplete, the workflow publishes an installable
debug-signed APK instead. That fallback is suitable for testing and fresh installs,
but it does not provide a stable signing identity for upgrades between releases.

## Test

```powershell
./gradlew.bat testDebugUnitTest lintDebug
./gradlew.bat connectedDebugAndroidTest
```

The connected test command requires a running emulator or attached device. Server credentials are entered only at runtime and are never part of the source tree.

## Project structure

- `app`: application startup, Login Flow v2 UI, navigation, and scheduled maintenance.
- `core:model`: shared domain models and contracts. `FolderSnapshot` records whether a folder is never loaded, empty, current, stale, or failed.
- `core:data`: encrypted credentials, Room metadata, WebDAV/OCS access, the shared `FolderSyncCoordinator`, and resumable metadata indexing.
- `core:transfers`: persistent uploads/downloads, foreground transfer recovery, conflicts, progress/speed, and cache cleanup.
- `documentsprovider`: Android Storage Access Framework provider used by Android Files.
- `feature:drive`: CloudDrive browsing, Offline, Transfers, and Settings screens.

## Login and configuration

The login screen accepts the HTTPS base URL of a Nextcloud server and starts Nextcloud Login Flow v2 in the system browser. The returned app password is encrypted with an Android Keystore key and is never written to source or logs. The app currently supports one account (`primary`). HTTP and URLs containing embedded credentials are rejected.

After login, CloudDrive refreshes the root immediately and starts a network-constrained incremental metadata crawl. Completed folders are recorded in Room, so an interrupted crawl resumes without repeating them. Settings includes **Refresh cloud index** for a full metadata refresh and **Pause indexing** to stop background crawling while retaining cached listings.

## Virtual-drive behavior

CloudDrive stores metadata—names, paths, sizes, dates, permissions, ETags, favorites, and preview availability—rather than mirroring cloud content. Folder metadata is considered fresh for five minutes. Android Files receives cached listings immediately; never-loaded and stale folders are fetched directly through the shared coordinator and the affected folder URI is notified when the transaction completes. Cached listings remain visible offline.

Search, Recent, Favorites, and preview thumbnails use the metadata index or dedicated Nextcloud endpoints and do not depend on visiting a folder in CloudDrive. Nextcloud previews are cached separately; requesting a thumbnail never downloads the full cloud file.

File bytes are downloaded only when a file is opened or explicitly made available offline. Uploads happen only after an explicit create/edit/upload action. There is no camera backup, phone-folder synchronization, or physical `/storage/nextcloud` mirror.

Temporary file and thumbnail cache entries have a default 1 GB limit and seven-day expiry. Offline files are excluded from eviction. A manual folder refresh compares ETags and replaces changed offline files. Transfer rows, staged uploads, completed chunk counts, and conflict names survive process restarts; boot/package replacement wakes the queue. Failed work uses exponential retry delays, and abandoned chunks and temporary files are cleaned by maintenance.

## Server/API configuration

The server must expose standard Nextcloud WebDAV under `remote.php/dav`, Login Flow v2, the OCS app-password endpoint, and the core preview endpoint. The app requests only Internet/network-state, transfer foreground-service, notification, and boot-recovery permissions. Android Files access is provided by a protected `DocumentsProvider`; broad storage permissions are not requested.
