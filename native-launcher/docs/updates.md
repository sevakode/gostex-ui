# Signed updates and diagnostics

GOSTEX Android and Linux use the same JVM17 `release-runtime`. `version.properties` owns the build version/code. Android retains `tv.orbit.shield`, its existing signing identity and `SharedPreferences("orbit")`; desktop preferences remain in the existing desktop store.

## User behavior

Settings → Updates and diagnostics contains a release feed URL, manual check, automatic checking/download toggles, a saved 1/6/24-hour interval, debug mode, diagnostic export and log clearing. Current development defaults enable checks/downloads/debug, but an empty feed performs no network access. The launcher checks when it becomes visible and the interval is due, with no background polling timer. Checks stop when hidden or a tracked game runs. An available release has a persistent in-launcher action that does not take focus.

Android 11 downloads and verifies automatically, then uses native PackageInstaller after an explicit Install action. The owner may need to allow this app to install packages. Installation confirmation belongs to Android; no silent-install/root/device-owner claim is made. Package name, newer version code, signing certificate set, exact size and SHA256 are rechecked immediately before installation. See [PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams).

Installed Linux builds stage verified ZIPs and `pending.properties`; the stable `gostex-launch` bootstrap applies them at the next launcher start. It retains the previous image for fallback/rollback. It never replaces a running image during gameplay. Desktop development runs on macOS expose diagnostics/settings but do not pretend to install Linux packages.

## Feed protocol

A UTF-8 properties file has exactly these fields:

```properties
schema.version=1
platform=android-arm64
versionCode=41
versionName=0.33.0
url=https://distribution.example/releases/gostex-android-arm64-0.33.0.apk
sha256=FULL_LOWERCASE_SHA256
size=EXACT_BYTE_COUNT
```

Linux uses `platform=linux-x64` and a ZIP. A detached binary SHA256withRSA signature is served at the feed URL plus `.sig`. The client verifies the exact feed bytes before parsing using its pinned public key. HTTPS redirects remain HTTPS and are bounded. Wrong platform, downgrade, malformed fields, oversized/truncated downloads, bad signature and hash mismatch are rejected. An interrupted download retains the old file; atomic staging is used. The publisher's private key is never in the APK/ZIP/source repository.

Prepare an artifact and signed feed with `tools/release/publish_feed.py --help`. Linux packaging starts from `:desktop:createDistributable` on Linux; `tools/release/package_linux.py` dereferences internal links, rejects cycles/external links and preserves executable file modes in the ZIP. Installation instructions: [Steam Deck](steam-deck.md).

## GitHub pipeline

The repository's `GOSTEX continuous releases` workflow builds Android and native Linux on Ubuntu, tests both, then publishes a complete signed release only if both jobs succeed. It restores the existing Android identity from an encrypted repository secret. The separate feed signer is also an encrypted secret. No private key values appear in source or output.

Every main-branch build gets `versionCode=100000+GITHUB_RUN_NUMBER`, `versionName=0.33.<run number>`; these increase across development pushes. Published tags are `build-<run number>`. The app feed URL is the chosen accessible repository's `/releases/latest/download/android-arm64.properties` or `linux-x64.properties`.

Private GitHub releases require authentication. The initial private source repository alone is **not** an anonymous device update endpoint. Public package distribution or an authenticated channel must be chosen before setting a production feed; no GitHub token is embedded in the application. The initial blank feed is deliberate and can be configured in settings without rebuilding.

## Diagnostic report

Debug mode records bounded app-only technical events, lifecycle/launch/update errors and sanitized exception stacks. Four 128KiB files rotate locally. No logcat, complete preferences, cookies, tokens, URLs, ROM lists, filenames, screenshots, saves or memory dumps are collected. Call sites use fixed event codes; arbitrary user content must not be logged.

The explicit ZIP export includes `metadata.txt`, `events.log` and `threads.txt` (up to32 threads/16 frames, no thread names, max64KiB). The whole ZIP is capped at2MiB. URLs, credentials, personal paths and token patterns are redacted. Export captures threads only on demand; disabling debug stops ongoing events and there is no telemetry timer. Review the archive before attaching it to a private GitHub issue. Export does not send anything automatically.

## Verification boundaries

Local tests cover signature/transport/hash failures, direct process lifecycle, preference persistence, UI focus/Back, redaction/log bounds, thread export, safe ZIP paths/limits, atomic pointers and fallback/rollback. Android release/lint and source-level regressions are separate. GitHub Linux build success is Linux host evidence, not physical Deck/Steam Input/Gaming Mode evidence. Unknown-source consent, real self-update installation, return focus, sleep/wake and hardware performance need target-device checks.
