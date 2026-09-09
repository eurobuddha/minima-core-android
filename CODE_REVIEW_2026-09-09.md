# Minima Core review — 9 September 2026

Reviewed the project graph, saved project context, startup/service lifecycle, node command and IPC paths, wallet/coin views, amount formatting, and token metadata loaders. Checked resync semantics against the local Minima core source and the bundled JAR. This is a focused review, not an exhaustive audit of the embedded node.

## Implemented in 1.6.12-ui-h2 (versionCode 40)

| Finding | Change |
| --- | --- |
| Long decimal amounts could overrun the single-line wallet total and squeeze token names out of their row. | Amounts retain every digit and wrap without hyphenation. Token amounts occupy their own full-width line. The total remains selectable; tapping a token amount copies its exact source value. |
| `Format.tidyAmount("1.0E-10")` returned `1.0E-1`, changing the value by a factor of a billion. | Reused PandaDEX's `BigDecimal.stripTrailingZeros().toPlainString()` normalization. No rounding or floating-point conversion. Preserves placeholders and avoids expanding extreme exponents into enormous strings. |
| Resync provided no ongoing feedback and did Activity work from a command worker thread. | Added a selectable, copyable terminal of actual node log events, elapsed time, progress status, command errors and an explicit restart action. UI updates run on the main thread. |
| Activity recreation could lose command state; repeated input could submit another resync. | The session outlives the Activity, rejects concurrent starts and stale callbacks, and never interprets log text as command success. A persisted marker reports an interrupted process as completion unknown. |
| MainActivity's service binding could delay shutdown after the core called `stopSelf()`. | Entering resync finishes the old main screen and releases its binding. Successful resync enables restart only after service cleanup completes. Reopening the app returns to pending progress. |
| The repeating service alarm could restart a completed resync without user action. | Resync cancels the existing restart alarm. Explicit restart uses the existing startup flow after service shutdown. |
| An exception executing a node command could leave its caller waiting forever. | `MinimaCMD` returns a failed reply through the normal callback. |
| The global log sink could retain a destroyed Logs screen. | Destroyed screens detach their sink; the resync observer detaches on completion. Log tails retain at most 600 entries. Terminal text is rebuilt only when entries change, and polling stops off-screen. |

Reuse sources: the repository's `utils/Format.java`, `utils/LogBuffer.java`, `utils/Feedback.java`, `launcher/StartServiceActivity.java`, existing amount/identifier styles; sibling `pandadex`'s `PriceMath.fmt`, `atomix` and `utxo` formatting utilities, and `pandapools` formatter tests and AndroidX scroll-container usage. AtomiX's five-decimal display truncation was not used because the saved requirement is to show amounts in full, including tiny nonzero values. No dependencies were added.

## Remaining findings

These are existing issues outside the decimal/resync changes and remain open.

1. **High — token URL redirects bypass the private-host guard.** `app/src/main/java/org/minimarex/minimacore/main/views/balance/tokens/ImageLoader.java:144` and `WebValidate.java:49` check only the initial host, then enable automatic redirects. A token-controlled public URL can redirect to a loopback or LAN address. This can reach local services, potentially including node RPC when enabled. Validate every redirect destination and restrict protocols; also cover IPv6 unique-local addresses. Confirmed by source inspection; no exploit was run against a node.
2. **Medium — malformed broadcasts can crash clients of the bundled API library.** `minimaapi/src/main/java/org/minimarex/minimaapi/MinimaAPI.java:42` and `:118` call `equals` on an ID taken from an Intent without checking for null. The response receiver is exported and `MinimaAPIReceive.onReceive` does not catch this exception. A matching-action broadcast without the ID reaches a null dereference. Reject missing or empty IDs before comparison. This concerns companion apps using this library, not an authentication bypass demonstrated in Core.
3. **Medium — startup polling does not follow Activity lifetime.** `app/src/main/java/org/minimarex/minimacore/launcher/StartServiceActivity.java:61` polls service/node state on a raw worker without cancellation. `onDestroy` clears its service reference, and later code repeatedly dereferences the global node and dismisses the progress dialog from the worker. Rotation, exit or concurrent shutdown can strand the worker or cause a null-reference/UI-thread failure. The new pending-resync redirect avoids this path during an active resync, but normal startup still needs lifecycle-aware waiting and main-thread UI callbacks.

An additional optimization opportunity is to coalesce icon loads by URL while a fetch is in flight. `ImageLoader.loadOver` queues another fetch on every uncached render; its four-worker pool limits concurrency but its queue is unbounded. `WebValidate` already contains an in-flight coalescing pattern suitable for inspection and reuse.

## Validation

- 10 app JVM tests passed, including exact decimals, exponent regression, host/port validation, duplicate starts, stale callbacks, failure responses, interrupted state, observer isolation and bounded log retention.
- 3 Android instrumentation tests passed on the isolated API 36.1 emulator. The new tests exercise real text measurement at 320dp width with 100%, 150% and 200% font scale, and interruption feedback before and after Activity recreation. Every amount character remains within its view with no ellipsis or vertical clipping. The generated token-row image was also visually inspected.
- Debug and release builds and lint passed; lint reports warnings, not a warning-free codebase. `apksigner verify` passed and the new APK's signing certificate matches versionCode 39.
- No live node resync, transaction, phone installation or end-to-end node restart was performed. Command success/failure is covered with a test runner; actual chain import and service restart still need a disposable-node integration test.
- The previous versionCode 39 APK was preserved in `dist/minima-core-ui-1.6.11.apk`. The new APK is `dist/minima-core-ui-1.6.12.apk`, with a SHA-256 sidecar. This is a local build; nothing has been published.
