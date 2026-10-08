# Pandamonium native apps

These thirteen Android library snapshots are compiled only into the `pandamonium` flavor. The standalone apps remain in their sibling repositories. `sources.lock.json` records each source revision, app version and original file hashes (including any uncommitted source changes present at import).

History occupies the core utility tab. The sidebar contains ETH Wallet, AtomiX, PandaPools, Future Cash Next, PandaDEX, Zero Edge Casino, Minima Explorer, Filez, Minima Vestr, Entropy, Terminal IDE and MinimaMail. The user confirmed that the requested “Vesta” is the existing Minima Vestr app. On the Core screen, centred Minima Core / Pandamonium branding shares one toolbar row with the left drawer button and right overflow menu. Long-hold an app in the drawer to drag it vertically; the order persists across screens and restarts. Core home remains first. Sidebar rows show each app's bundled launcher icon, bound to its destination so icons follow the saved order. Embedded app headers retain their own controls.

`scripts/import-pandamonium.py` records provenance and adapts Android packaging: resource prefixes, private activities, distinct export providers, notification IDs, encrypted-vault aliases and duplicated helper namespaces. `adapt-pandamonium-direct.py` and `adapt-pandamonium-ui.py` then replace the standalone node transports and setup/recovery guidance. Financial protocols, signing queues, pending-write records and recovery checks are retained. PandaDEX also retains its strict persisted-JSON parser; its separate broadcast SDK and relay are removed.

The importer refuses to overwrite an existing snapshot. Run `python3 scripts/import-pandamonium.py --verify` to recreate imports in a temporary directory and compare them with the snapshots and source lock. Run `python3 scripts/verify-pandamonium-transport.py` to guard the direct-only integration boundary. Builds use these local snapshots and never require sibling repositories or automatically pull upstream code. Updating an app requires a reviewed source refresh and validation.

All thirteen apps use `DirectNodeApi`, backed by `EmbeddedNodeTransport` and the core JAR's existing admin command runner. There are no internal registration broadcasts, pairing tokens, Binder command/result parcels, response-cache files or relay processes. Commands and file metadata use structured JSON copies. External companion clients retain their authenticated IPC bridge; Android file pickers, services and notifications still use normal system APIs.

The transport shares simultaneous identical `status`, `balance`, `block`, `network` and `peers` reads. It never caches completed replies or coalesces signing, address creation, coin selection or batches. Integrated requests, core UI mutations and external bridge mutations share a bounded execution queue. Requests are tied to the node instance that accepted them. Closed clients cancel queued work; executing writes retain late results for the existing durable recovery logic. Missing results remain uncertain and never impersonate a confirmed node rejection.

`DirectNodeEvents` delivers filtered local events, coalescing pending block/balance refresh signals. UI subscriptions stop with their screens; settlement services retain their existing lifetimes. Terminal IDE persists log events on a background worker. Each subscription has at most 256 pending events; overload discards oldest notification/log events and increments `droppedEvents()`. Command results and financial recovery records are not part of that lossy notification queue.

Filez streams the selected document directly into the guarded node file implementation, without an intermediate provider copy or an internal URI grant. Imports use unique temporary files and atomic replacement; base-folder containment, live-database protection and recursive-delete checks apply to both direct and external file clients.

All apps share one Android UID, process and embedded node wallet. They are not separate security sandboxes. Feature-specific stores and encryption aliases remain distinct. AtomiX retains its identity-mismatch halt and recovery exports, but its standalone whole-package clear-storage/uninstall actions are removed because they would erase every integrated app and the node.

## Build

From the base repository:

```sh
./gradlew :app:assemblePandamoniumRelease :app:lintPandamoniumRelease
```

The new package is `com.eurobuddha.pandamonium`. It does not replace PandaBear or BlackBear. The existing family signing configuration is used. The delivered installer filename includes the release version.

## Validation scope

Existing unit tests are copied with the apps; fixture paths are adapted to the library layout. The Pandamonium instrumentation test is in `app/src/androidTestPandamonium`. It requires the explicit `pandamoniumDisposable=true` argument, a fresh emulator and fresh application data. It generates a disposable random wallet, checks node defaults, command parity/batches, direct file round-trips and path guards, local log delivery, removal of relay components, and every destination through the shared menu. It does not send funds or sign transactions.

Select the fixture explicitly; the older general UI fixtures assume a different preconfigured device:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  -e class com.eurobuddha.minimacore.integrated.PandamoniumIntegrationTest \
  -e pandamoniumDisposable true \
  com.eurobuddha.pandamonium.test/androidx.test.runner.AndroidJUnitRunner
```

Current local candidate: **1.9.9-Pandamonium**, version code **80**, `dist/minima-core-Pandamonium-1.9.9-candidate.apk`. Review fixes are reproduced by the importer adapters, including secure-store failure handling, strict FutureCash replies, final mutation authorization, service/notification isolation, the History tab and regression fixtures. The navigation and Vestr tests in `NavigationRegressionTest` reuse the existing disposable emulator data; they do not clear it. Vestr's focused test checks embedded node status, script resolution and opening its creation/calculator forms, without funding a contract.


Pandamonium defaults to block key uses with low RAM. Settings → Startup Params → Classic mode removes both startup flags after a restart. This setting is also available before the first wallet is created/restored. Existing keys/counters stay intact; seed restoration must use the original wallet mode. PandaBear/BlackBear are unaffected.

The disposable integration fixture now switches modes twice and checks the live flags, persistence, retained key shapes/counters and refreshed drawer label. Add `-e classicMode true` on a fresh disposable emulator installation to start with classic keys and test the reverse sequence. The fixture never signs transactions or imports an existing wallet.

The owner confirmed 1.9.9 working on Fold R3GL805K7MB after an in-place update. History normalizes serialized token metadata for new responses, cached rows and imported records without deleting history. Mail retains encrypted identity keys across restarts and preserves the prior identity when a malformed backup fails to import.
