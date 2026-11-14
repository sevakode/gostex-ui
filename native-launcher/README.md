# GOSTEX UI for Android TV

GOSTEX UI is a TV launcher for apps, a local game library, downloads, and web browsing. The Android application requires Android 9 or later. Public development builds are available for ARM64. See [Android compatibility and tested workflows](docs/compatibility.md).

## Get started

1. Install a trusted GOSTEX APK through Android's system installer. The system may ask permission to install from the chosen source.
2. Open GOSTEX and, if you want it as your home screen, select it using your device's Android home-app settings. The exact system screens depend on the device.
3. Open **Library**, choose to add a game or folder, select its platform, and grant access through the Android file picker. A multi-file disc may require selecting its containing folder so the emulator can access its companion files.
4. Open a game card to launch it. Install a supported external emulator separately. Some systems require firmware supplied by the user; the launcher does not include games or BIOS files.
5. Use the settings panel to manage language, sections, library visibility, clocks, covers and other preferences. Hold a game or app tile to open its available actions.
6. Open **Downloads** to add a direct file link, or choose **Browse the web** to find a link and review it in the queue. Install TorrServer for torrent transfers. Use Android's file picker to select a destination folder.

Your ROMs and saves remain in their chosen locations; adding a source does not move them. If a drive is disconnected or permission is revoked, reconnect it or grant access again. Archive support and game launch compatibility depend on the platform and emulator; importing an archive does not mean its contents have been extracted or verified.

## Covers, media and updates

Cover selection and provider order are configurable. Some online providers require an individual API key; network access is optional for locally available content. A manual cover choice takes priority over automatic updates.

The media panel uses Android media sessions. Picture-in-picture depends on Android and the player application; the launcher does not decode video. The optional Grafana screensaver is configured by the user and requires access to the chosen server.

The update mechanism verifies signed manifests and uses Android's installation confirmation. A user can change or disable the update channel. Before upgrading an existing installation, use an APK signed with the same certificate so Android can preserve the app's data. Development builds and release preparation should not be treated as a verified stable release. See [updates and diagnostics](docs/updates.md).

## Build

The Android app is in `app/`, with shared update code in `release-runtime/`. Use JDK 21, Gradle 9.8.2, and the Android SDK packages required by the project. There is no Gradle wrapper checked into this repository.

```sh
gradle -p native-launcher :app:assembleDebug :app:lintDebug
```

Release builds require the intended signing configuration and release checks. A debug build is for testing; do not install it over an existing installation unless its signing identity matches. Tests in `checks/` cover specific contracts and do not replace device testing.

## Scope and limitations

GOSTEX opens games in compatible external Android emulators and uses TorrServer for torrent transfers. The built-in browser uses Android System WebView. Linux and Steam Deck development has its own [setup guide](docs/steam-deck.md).

The application source code is available under the [MIT License](../LICENSE). Game descriptions, artwork, fonts and dependencies have their own sources and licensing; see the attribution displayed in the app and their respective notices.
