# Code Review — Pandamonium 1.9.0 direct integration

## Summary

All eleven native features now connect directly to the embedded node. The existing command runner, file guards, bounded worker and shared-request primitives are reused. Transaction algorithms, command-safety checks, serial signing flows and durable pending-write/recovery records remain in the native feature code. The external authenticated bridge remains available for separate companion applications.

This review covers the changes from the local 1.8.0 integration: shared SDK/backend and lifecycle, core/receiver wiring, all feature client substitutions, notification consumers, file transport, merged component boundaries, repeatable import adaptations, and regression tests. The earlier packaging review remains in `PANDAMONIUM_REVIEW.md`.

## Findings — fixed

### CRITICAL — Whole-package reset actions in the integrated AtomiX recovery screen

**File:** `integrated/atomix/src/main/java/com/eurobuddha/atomix/MainActivity.java`

Standalone uninstall/clear-storage instructions would erase the Pandamonium node wallet and every feature. Removed the package-reset actions from the integrated screen, explained the shared-data scope, and retained the identity-mismatch halt, recovery identifiers, key export and sweep options.

### CRITICAL — Predictable staging file and destructive replacement

**File:** `app/src/main/java/org/minimarex/minimacore/receiver/MinimaReceiver.java`

The inherited file bridge reused `<destination>.part` and deleted an existing destination before rename. A stale symlink could redirect staging writes; failure after deletion could lose the existing file. Imports now create unique sibling temporary files, verify provider-reported length where available, sync and atomically replace the destination, with cleanup on failure.

### MAJOR — File guards did not protect base-folder writes and recursive symlinks

**Files:** `main/backup/NodeFiles.java`, `receiver/MinimaReceiver.java`

Shared write validation now independently rejects the base itself, paths outside it and live databases. The receiver reuses those guards. Recursive deletion preflights the tree and refuses symbolic links. Tests cover traversal, database aliases, base moves/deletion and preservation of an outside symlink target.

### MAJOR — Queued work must not migrate to a restarted node

**Files:** `integrated/EmbeddedNodeTransport.java`, `receiver/MinimaReceiver.java`

Direct commands/files and queued external bridge requests check the captured node instance and readiness before execution. Shared read keys use object identity plus a revision. Mutations advance revisions before and after execution; block/balance events invalidate the current read grouping. No completed result is cached.

### MAJOR — Request lifetime and uncertain signing outcomes

**File:** `minimaapi/.../direct/DirectNodeApi.java`

A compare-and-set completion gate prevents duplicate callbacks. Closing a client cancels queued requests before execution, while running writes retain their late result for existing recovery-marker reconciliation. Transport failures omit `status`/`enabled`, so absence of a result cannot appear to be a confirmed node rejection. Pending requests are bounded per client, and their retention is limited to the prior ten-minute late-result window. Consumer failure cannot break direct delivery to another client, and callback exception text is not logged.

### MAJOR — Hidden-screen refreshes and UI-thread log persistence

**Files:** `DirectNodeEvents.java`, adapted feature activities/services, `PandamoniumHooks.java`

Local event subscriptions filter by event type, coalesce pending block/balance refreshes and use bounded queues. UI subscriptions follow screen start/stop. Existing background settlement services remain subscribed. Terminal IDE log persistence runs on a background worker with throttled retention pruning. Subscriber JSON is isolated, and queued deliveries are invalidated on unsubscribe/re-subscribe.

### MINOR — Redundant Filez copies and stream leaks

**File:** `integrated/filez/.../MainActivity.java`

Removed internal provider staging and URI grants. The same-UID node reads the selected SAF document directly. Export opens both streams in try-with-resources, including cleanup if opening the second stream fails. Its existing copy helper and byte-count check are retained.

### MINOR — Standalone setup text and obsolete SDK/process code

Replaced app-enablement/pairing guidance with embedded-node readiness guidance. Removed PandaPools/PandaDEX relays and PandaDEX's unused broadcast SDK classes; retained its strict JSON parser because persisted maker records depend on it. The public broadcast SDK rejects accidental use from inside Pandamonium. The repeatable importer and an architecture guard verify this boundary.

## Validation

- 1,649 unit-test executions passed: base 106 in each of three flavors; shared SDK 16; AtomiX 260; PandaPools 296; Future Cash Next 28; PandaDEX 701; casino 25; Entropy 5. Removed tests exercised deleted IPC-only code; financial tests remain. New tests cover request races, cancellation, late results, isolation, 5 MiB replies, overload, event coalescing/lifetime and the strict read-sharing allowlist.
- Pandamonium debug/release assembly and classic/block compilation passed. Release lint: zero errors, 317 warnings, matching the earlier integration warning count.
- Importer reproduced all eleven source snapshots and provenance. Architecture guard found no bundled legacy node client, relay process, node broadcast receiver or whole-package reset action.
- Fresh offline Android 36.1 emulator: **OK (1 test)**, 54.958 seconds. Checked actual block-key-use/SQL-storage flags, structured parity with native `block` (including the correct no-blocks-yet rejection), successful `status;status` batch, unknown-command rejection, absence of pairing tokens and relay components, a 384 KiB file round-trip, replacement/move/list/delete, traversal/live-database/base/symlink guards, background Terminal IDE log persistence, every native drawer destination, retained minimaCore activity, status-bar clearance and Back closing the drawer.
- The fixture explicitly grants its notification permission and battery exemption before launching. Early runs exposed fixture assumptions about Android overlays and an empty offline chain; these were corrected. An accidental broad run picked up unrelated legacy fixtures requiring a preconfigured device; it was stopped, and the dedicated fixture was selected explicitly.
- Release metadata: `org.minimarex.pandamonium`, `1.9.0-Pandamonium`, version code `71`; one launcher, no secondary process declarations, private bundled activities, no duplicate provider authorities, backups disabled.
- Signed with the existing family certificate SHA-256 `eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f`.
- Installer: `dist/pandamonium-1.9.0.apk` (32803826 bytes), SHA-256 `59e418f6895e924f429462b8784a714f5554e240b9a79e9b0440bfc473f427c9`. The previous 1.8.0 artifact remains available.


## Limits and trade-offs

The apps share one UID/process and wallet, not separate sandboxes. Android system-service communication, document providers, external companion IPC and remote blockchain/network APIs still exist. Notification overload can drop old queued notifications/log records (counted by `droppedEvents()`); signing results and durable financial records never use that queue. Performance improvements are structural; no battery-life percentage or long-running RAM benchmark is claimed.

No funded device was accessed. Mainnet trades, extended background settlement, restore/reseed recovery and sustained low-memory behavior still require separate validation of the combined APK. This release is a local signed build, not a store publication.

## Verdict

**Approve for local release testing within the validated scope above.** No unresolved critical or major review findings. Mainnet and extended recovery/low-memory scenarios remain outside this validation.
