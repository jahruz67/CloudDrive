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

## Current beta behavior

- Login uses Nextcloud Login Flow v2 in the system browser.
- Files are metadata-only until opened, cached, or made available offline.
- Android Files exposes one `CloudDrive` provider root.
- Temporary content is limited to 1 GB and offline content is not evicted.
- User uploads run through a persistent transfer queue. No camera, media, or folder backup exists.
