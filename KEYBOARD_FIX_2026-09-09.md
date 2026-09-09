# Keyboard accessibility — 1.6.13-ui-h2 (versionCode 41)

The base app padded its screens for system bars only, so an edge-to-edge window could leave input fields and actions behind the keyboard. All ten screen hosts now share `utils/KeyboardInsets.java` and declare `adjustResize` in the manifest.

- Root padding includes the greater of the keyboard and navigation-bar heights, preserving the layout's original padding.
- Existing form scroll areas shrink above the keyboard, allowing lower actions to be scrolled into view.
- A toolbar **Hide keyboard** action appears while the keyboard is visible. It dismisses the keyboard without clearing entered text.
- A lifecycle-bound Back callback dismisses the keyboard first and disables itself when the keyboard closes, restoring normal navigation.
- Inputs request that landscape keyboards keep the app UI visible instead of switching to a fullscreen editor. Terminal command rows use less vertical padding to fit the remaining space.
- In landscape, the main screen temporarily hides its tabs and status footer while typing so they cannot consume all the command field's height. Both return when the keyboard closes.

## Reused sources

The insets calculation and non-forced edge-to-edge setup come from `../terminalide/app/src/main/java/com/eurobuddha/terminalide/MainActivity.java`, `ide/ScriptEditorActivity.java`, and `../utxo/app/src/main/java/com/eurobuddha/utxo/MainActivity.java`. Explicit dismissal reuses Terminal IDE's `terminal/TerminalView.setSelectionMode` implementation. The Back dispatcher registration follows `../filez/app/src/main/java/com/eurobuddha/filez/MainActivity.java` using the same AndroidX Activity version as this app. The shared helper and toolbar control are the minimum adaptation needed to apply these behaviors consistently across the base app. No dependency was added.

New screens should call `KeyboardInsets.install` after setting up their toolbar and use `adjustResize`. Pass optional tabs/footer views only when they must yield space in landscape. Keep long forms in scroll containers.

## Review

The keyboard changes preserve screenshot protection, form validation, transaction handling and node startup/resync commands. Insets listeners remain attached to their own views; Back callbacks follow the Activity lifecycle. Review found no outstanding blocker in this change. The separate findings in `CODE_REVIEW_2026-09-09.md` remain open.

Real-keyboard instrumentation covers Send, Params, resync, both restore forms, the standalone terminal in portrait and landscape, and the main tab/terminal layout in landscape without starting a node. It checks button visibility above the IME, explicit dismissal, Back dismissal, text preservation, restored padding, and restoration of the main tabs/footer. Device coverage is the isolated API 36.1 emulator; no phone was changed.

All 11 instrumentation tests and 10 JVM tests passed. Release build, Android lint and APK signature verification passed; lint still reports existing warnings. Final Params and landscape main-terminal screenshots were visually checked. The signed local APK is `dist/minima-core-ui-1.6.13.apk`, accompanied by its SHA-256 checksum. Its certificate matches the previous release. Nothing was published or installed on a phone.
