# Android TV compatibility

The Android APK requires Android 9 (API 28) or later. The published Android update channel is `android-arm64` for `arm64-v8a`. [Android build configuration](../app/build.gradle.kts) · [Signed update channel](updates.md)

## Verified coverage

Snapshot from November 14, 2026. Each row records the scenarios exercised in that environment.

| Environment | Verified scenarios |
| --- | --- |
| NVIDIA SHIELD TV Pro (2019), Android 11 / API 30, physical device | In-place installation of signed builds through 0.33.230. An earlier launcher check confirmed that hiding a completed download from history kept its media library reference across a launcher restart. [0.33.230 release](https://github.com/sevakode/gostex-ui/releases/tag/build-230) |
| Android 9 / API 28, emulator | Launcher startup and selection as the system home app. The TorrServer client completed a local request and cancelled a blocked prepare request during headers and body reads. |
| Android 12 / API 31, emulators | The system folder picker restored access to a paused download. Loopback tests covered direct download, pause and resume, explicit restart, and opening a completed file. Android's installer prompt and returning to the player picker were exercised. The browser opened a test page, sent an attachment to Downloads and filled the magnet form. [0.33.231](https://github.com/sevakode/gostex-ui/releases/tag/build-231) · [0.33.232](https://github.com/sevakode/gostex-ui/releases/tag/build-232) · [0.33.233](https://github.com/sevakode/gostex-ui/releases/tag/build-233) · [0.33.234](https://github.com/sevakode/gostex-ui/releases/tag/build-234) · [0.33.235](https://github.com/sevakode/gostex-ui/releases/tag/build-235) |

## Release status and pending coverage

- **Published:** 0.33.235 (`8e6b4ca`) includes the browser and download handoff. [0.33.235 release](https://github.com/sevakode/gostex-ui/releases/tag/build-235)
- **Pointer candidate:** on API 31, D-pad and OK opened a link outside the normal focus order. Back returned focus to the pointer button. [Pointer implementation](https://github.com/sevakode/gostex-ui/commit/f9a93a1)
- **Downloads with a browser session:** source candidate `bc61efc` passed Android compilation and a host TLS check. Its Android runtime check is pending.
- **Physical coverage pending:** other TV models, physical USB disconnect and recovery, browser control with a hardware remote or gamepad, and sustained use across the browser, downloads, video and games. The browser depends on Android System WebView. Game launching and playback depend on the installed emulator and player.
