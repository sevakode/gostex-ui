# GOSTEX

Gamepad-first launcher for Android TV / NVIDIA SHIELD and native Linux / Steam Deck.

- Android launcher: `native-launcher/app`.
- Compose Desktop: `native-launcher/desktop`.
- Platform-neutral catalogue contracts: `native-launcher/shared-core`.
- Signed updates and bounded diagnostics: `native-launcher/release-runtime`.

[Desktop development](native-launcher/docs/desktop.md) · [Updates and diagnostics](native-launcher/docs/updates.md) · [Steam Deck install](native-launcher/docs/steam-deck.md)

Development uses the existing Android application id and signing identity, and preserves owner preferences, ROMs and saves. A working host prototype is not Steam Deck Gaming Mode QA; actual device checks are recorded separately.

The owner-approved public repository is [sevakode/gostex-ui](https://github.com/sevakode/gostex-ui). Android's default signed update channel is `https://github.com/sevakode/gostex-ui/releases/latest/download/android-arm64.properties`; the channel remains editable and an explicitly saved empty value disables network checks. Signing secrets, local reports, user attachments and device data are excluded from source control.
