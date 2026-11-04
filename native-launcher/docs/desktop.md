# GOSTEX Desktop — first working slice

Status: a runnable Compose Desktop prototype, not a Steam Deck release. The Android launcher remains 0.32.0/40, with its existing `orbit` preferences and launch adapters. The common core is additive; Android does not consume it yet.

## Run

Use JDK 21 and Gradle. This checkout can use its existing bundled tools:

```sh
python3 native-launcher/tools/run-desktop.py
```

On Linux with JDK 21 and Gradle installed:

```sh
cd native-launcher
gradle --no-daemon -Pgostex.desktop=true :desktop:run
```

The prototype is opt-in. Ordinary Android builds do not include or resolve `:desktop` / `:shared-core`. Tool versions: existing Kotlin 2.4.21, Compose Multiplatform 1.12.2, existing Gradle 9.8.2 and AGP 9.5.0. The published KMP compatibility table currently ends at Gradle 9.7.0 / AGP 9.3.1 for this Kotlin release. We kept the existing Android toolchain, verified the available host, and recorded Gradle deprecation warnings; supported Linux/Deck behavior still needs its own checks. Sources: [Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html), [KMP compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html).

An isolated settings file can be supplied without touching the owner's desktop choices:

```sh
python3 native-launcher/tools/run-desktop.py :desktop:run --args=--config=/absolute/path/desktop.properties
```

## Controls and workflow

- Left/right selects a card; Enter opens it. Mouse and trackpad also work.
- F1 opens the right settings drawer; Escape backs out of the editor/drawer and restores the card. Escape from the home screen opens settings. Quit is an explicit drawer action.
- For Steam Input, configure D-pad → arrow keys, A → Enter, B → Escape and Y → F1. This is a keyboard-input adapter, not a native gamepad backend or a bundled Steam controller profile. Actual Deck buttons, focus and Gaming Mode have not been verified.
- The two removable test cards start a **separate JVM process/window**. Close it with Enter, Escape or its Return button. The launcher awaits its exit off the UI thread, then resolves the return anchor against the current shelf and requests window/card focus.
- Settings → Add an app accepts an absolute executable/AppImage path and literal arguments in separate fields. Browse can choose a local executable. An empty argument and embedded newline are preserved. Arguments are never parsed by a shell. A macOS `.app` bundle is a directory, so choose its actual executable for this prototype.
- Add/edit/remove/reorder cards, demo visibility, language (system/EN/ES/RU), full screen and selected-card identity persist. New arbitrary applications are not silently discovered or added. Removing a card never removes the application or its files.

The generic adapter tracks the **direct child process** only. An executable that forwards to an already-running application or daemonizes can exit before its window closes. Steam/Flatpak-specific lifetime/window integration is future work; this prototype must not be presented as a verified Steam launch loop. A second launch is blocked while the tracked child remains alive. Closing GOSTEX cancels its waiter and does not terminate the user's external application.

## Data and architecture

- `shared-core/src/commonMain`: stable content identities, typed launch descriptions and return anchors. No Android, Compose, filesystem or OS APIs. `game:` / `app:` helpers preserve existing Android namespaces. No Android persistence migrations.
- `desktop/.../platform`: validated direct `ProcessBuilder` launch, cancellable process observation, one desktop preference source. Writes run off the UI thread and replace a sibling temporary file atomically. Unknown/corrupt schema is read with defaults and protected against overwrite; individual invalid values use validated defaults. No network worker or catalogue scan.
- `desktop/Main.kt`: one lazy shelf, window focus listener, right drawer, localized resources. Focus restoration scrolls/composes an offscreen tile before requesting focus. Window listeners/coroutine owners are disposed with the UI. There is no idle timer, background controller poll or animation loop.
- Linux preferences: `$XDG_CONFIG_HOME/gostex/desktop.properties`, falling back to `~/.config/gostex/desktop.properties`; macOS: `~/Library/Application Support/GOSTEX/desktop.properties`. Desktop settings do not read/write Android `SharedPreferences("orbit")`.

Images, Android screensavers, Steam discovery, ROM scanning, media sessions, YouTube and Zona are not implemented in this slice. SmartTube APK and Android Zona compatibility on SteamOS remain unverified. The first slice deliberately adds no games, ROMs, BIOS files, account data or artwork.

## Verification and remaining device work

```sh
python3 native-launcher/tools/run-desktop.py :shared-core:jvmTest :desktop:test :desktop:classes
python3 .tools/build-orbit.py :app:compileDebugKotlin :app:lintRelease
```

Evidence lives in `../../.reports/universal/` plus `../../.reports/universal-build.log`. Automated Compose rendering/input tests are host tests, not physical controller or OS focus evidence. Native macOS visual QA was initially blocked by the locked Mac; see the runtime report for the final recorded state.

Required on a real Steam Deck before calling this a Deck release:

1. Linux x86-64 build/run and packaging on the target Linux baseline.
2. Add GOSTEX as a non-Steam app, configure Steam Input, navigate/start/return in Desktop Mode and Gaming Mode; ensure no double button events and no keyboard/focus trap.
3. Direct executable/AppImage and later Steam/Flatpak launch contracts, window activation and overlay/exit behavior. Preserve card focus across reorder/removal while an app runs.
4. Suspend/resume, controller reconnect, full screen, on-screen keyboard, Back and focus after file chooser/editor.
5. Cold settings persistence, locale/system-language selection, corrupt/unsupported config and unavailable executable recovery.
6. Idle/background CPU, RSS, graphics behavior and battery impact. JVM/Skia support alone does not establish low Deck resource use.

Source-level Android host checks and compiler/lint checks do not replace regression QA on SHIELD. This stage sends no ADB commands and installs no APK.
