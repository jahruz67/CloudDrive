# CloudDrive for Nextcloud

CloudDrive is a native Android virtual-drive client for a single Nextcloud account. It exposes cloud files in the app and through Android's Storage Access Framework without automatically uploading anything from the device.

**Website:** [jahruz67.github.io/CloudDrive](https://jahruz67.github.io/CloudDrive/)

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

GitHub releases automatically use the permanent signing key checked into
`.github/signing/cloudapp-release.jks`. No Actions secrets or manual signing setup
are needed. The key alias, store password, and key password are all `cloudapp`.
This key is intentionally public for convenience: anyone with the repository can
sign an APK with the same identity.

Keep this exact keystore for all future releases so Android can install updates.
Never regenerate or replace it. If it is accidentally deleted, restore it from Git
history. The workflow fails if it is missing and never generates temporary signing
keys or publishes debug-signed releases.

Tags such as `v0.2.0` and `v0.2.0-beta01` become the APK's `versionName`. Each new
workflow run uses its run number plus one for `versionCode`, so it is greater than
the old fixed code of `1` and increases for future releases. Rerunning the same
release keeps its code. Keep this workflow's run-number history when changing the
release process; resetting it requires choosing a code above all published builds.
Local builds retain their development versions unless explicitly overridden:

```powershell
./gradlew.bat :app:assembleRelease '-PreleaseVersionCode=123' '-PreleaseVersionName=0.2.0'
```

Download a newer release APK on the device and open it to update in place, keeping
the account, settings, and offline files. Android requires the same application ID
and signing key, and a version code at least as high as the installed build.
If the currently installed app was signed by an earlier runner's temporary debug
key and that private key was not saved, it cannot be upgraded with the permanent
key. A one-time uninstall/reinstall is then unavoidable and removes local app data;
subsequent releases signed with the permanent key can update normally. Do not
uninstall while uploads are pending.

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

Sign-in opens in the default browser so installed Nextcloud link handlers do not interrupt its cookie session. **Continue sign-in in browser** reopens the same pending login link, and brief network failures during polling do not require a new flow. Sign-in can be canceled or expires after 20 minutes. Browser-side access-denied errors can also depend on the server's session and proxy configuration and require validation against that server.

After login, CloudDrive refreshes the root immediately and starts a network-constrained incremental metadata crawl. Completed folders are recorded in Room, so an interrupted crawl resumes without repeating them. Settings includes **Refresh cloud index** for a full metadata refresh and **Pause indexing** to stop background crawling while retaining cached listings.

## Virtual-drive behavior

CloudDrive stores metadata—names, paths, sizes, dates, permissions, ETags, favorites, and preview availability—rather than mirroring cloud content. Folder metadata is considered fresh for five minutes. Android Files receives cached listings immediately; never-loaded and stale folders are fetched directly through the shared coordinator and the affected folder URI is notified when the transaction completes. Cached listings remain visible offline.

Search, Recent, Favorites, and preview thumbnails use the metadata index or dedicated Nextcloud endpoints and do not depend on visiting a folder in CloudDrive. Nextcloud previews are cached separately; requesting a thumbnail never downloads the full cloud file.

The app's list, grid, and Offline screens use the same preview cache as Android Files for all files that advertise a preview, including documents. File icons remain visible while previews load or when they are unavailable. Automatic folder navigation and background refreshes run quietly; the top refresh indicator appears only after a manual pull-to-refresh.

File bytes are downloaded only when a file is opened or explicitly made available offline. Uploads happen only after an explicit create/edit/upload action. There is no camera backup, phone-folder synchronization, or physical `/storage/nextcloud` mirror.

Temporary file and thumbnail cache entries have a default 1 GB limit and seven-day expiry. Offline files are excluded from eviction. A manual folder refresh compares ETags and replaces changed offline files. Transfer rows, staged uploads, completed chunk counts, and conflict names survive process restarts; boot/package replacement wakes the queue. Failed work uses exponential retry delays, and abandoned chunks and temporary files are cleaned by maintenance.

Opening a file from Files or Offline grants the receiving app access to the durable cloud document URI, including write access when the server allows it. Editors that support Android document-provider saving can save changes back to the same file. Share still sends a read-only copy. The file list refreshes on return to the app and every minute while visible.

Each completed save stages an immutable upload and remains readable locally while waiting for connectivity. Uploads to the same path run in save order, with ETags advanced after successful local saves to avoid spurious conflict copies. Actual server conflicts still produce a separate conflict file. WorkManager recovers queued work when foreground services cannot start; network failures retry automatically, while authentication, quota, and permission errors require attention in Transfers. Paused or failed saves block newer uploads to the same path until resumed, retried, or canceled. Pending, paused, and failed uploads are excluded from cache expiry.

To validate editor integration on a device, open a DOCX in Word and an XLSX in Excel, edit and save, then confirm the original server file changes without a new copy. Repeat with two quick saves, airplane mode followed by reconnection, Wi-Fi-only enabled, an app restart, and a concurrent server edit. Open the same documents through Android Files as well. This requires the target Office apps and a test Nextcloud account; JVM tests do not verify Office behavior.

## Server/API configuration

The server must expose standard Nextcloud WebDAV under `remote.php/dav`, Login Flow v2, the OCS app-password endpoint, and the core preview endpoint. The app requests only Internet/network-state, transfer foreground-service, notification, and boot-recovery permissions. Android Files access is provided by a protected `DocumentsProvider`; broad storage permissions are not requested.
