# Pandamonium implementation plan

Minima Core is the app; Pandamonium is its integrated-app build. It opens on the existing core home screen. Block-as-key-uses and low-RAM SQL storage are enabled by default; the Classic mode setting removes both. The left hamburger opens a left sidebar headed “Minima Core” with “Pandamonium” beneath it, containing the core and twelve native apps: ETH Wallet, AtomiX, PandaPools, Future Cash Next, PandaDEX, Zero Edge Casino, Minima Explorer, Filez, Minima History, Entropy, Terminal IDE, and MinimaMail. External companion APK support remains required alongside the embedded apps.

## Reuse and implementation

1. Add a distinct Pandamonium application ID and versioned artifact using base's existing product-flavor and release-signing configuration. Reuse `BLOCK_KEYUSES` startup enforcement, recovery validation and port-conflict detection.
2. Snapshot the named sibling apps' native source, resources and tests into feature library modules in this repository, recording their source revision and local file hashes. Preserve their financial logic and background workers. Standalone sibling projects remain unchanged.
3. Adapt integration boundaries: prefix resources; separate duplicated helper packages, provider authorities, notification IDs and encrypted-vault aliases; replace standalone transports with direct embedded-node commands, files and events; retain financial queues and recovery markers. Preserve existing signature/token checks and provider restrictions.
4. Reuse the native drawer structure from `apks/mail/app/src/main/java/com/eurobuddha/mail/MainActivity.java`, opening it on the left. Host the existing activities inside the same APK with a shared navigation shell. Keep screen-specific lifecycle, confirmations, secret hiding and background settlement behavior.
5. Build and review the merged manifest, resource and class boundaries. Run existing feature tests plus integration regressions, base tests, release assembly and lint. Inspect the resulting APK identity and signature. Record any device-validation limits before handing over the build.

## Sources inspected

- `app/build.gradle`, `MinimaApplication`, `main/MainActivity`, `service/MinimaService`, `receiver/MinimaReceiver`, `receiver/ReceiverDB`, and `minimaapi/src/main/java/com/eurobuddha/minimaapi/` in base.
- The native manifests, Gradle dependencies, entry points and storage/service integration points in `../ethwallet`, `../atomix`, `../pandapools`, `../futurecash-next`, `../pandadex`, `../casino`, `../blockexplorer`, `../filez`, `../history`, `../entropy` and `../terminalide`.
- `../mail`'s DrawerLayout navigation; ETH Wallet's `KeyVault`; casino's `SecretStore`; PandaPools' `NodeTransportService`; PandaDEX's source SDK.

The user clarified that “Minima Block” means **Minima Explorer**. This work builds the new variant; it does not install it onto a funded device.

## Progress

- [x] Identify existing native sources and integration boundaries.
- [x] Add flavor and reproducible feature imports.
- [x] Integrate node routing, storage and component identities.
- [x] Add left-side navigation shell (corrected to the user’s current instruction).
- [x] Build, test, review and produce the versioned APK.

## Previous build: 1.8.0

- Current candidate: **1.9.4-Pandamonium**, version code **75**, package `com.eurobuddha.pandamonium`.
- Signed installer: `dist/pandamonium-1.8.0.apk` with adjacent SHA-256 file.
- 1,638 unit-test executions passed: base 103 tests in each of three flavors, SDK 7, AtomiX 260, PandaPools 299, Future Cash Next 28, PandaDEX 705, casino 25, Entropy 5.
- Release lint: zero errors (317 warnings, including inherited library/layout warnings).
- Fresh offline Android 36.1 emulator: passed actual node-default checks, internal registration/status read, all eleven menu destinations, retained minimaCore screen, status-bar clearance and Back-to-close behavior.
- Importer reproduced all eleven snapshots and provenance exactly. The merged manifest has one launcher, private feature activities, distinct provider authorities, and disabled backups.

See `review/2026-10-06/PANDAMONIUM_REVIEW.md` for review findings and validation limits. The APK was built locally; it has not been published or installed on either connected physical phone.

## Direct integration extension: 1.9.0

The user requested all internal transport changes and the benefits of integration. Implemented a shared in-process node API, bounded command/read executors, simultaneous metadata-read sharing, typed and coalesced events, visible-screen subscriptions, background Terminal IDE log persistence, and direct Filez document streams. Removed both relay processes, all bundled broadcast clients/notification receivers, internal pairing and response-file handoffs. Preserved financial write queues and durable uncertainty/recovery records.

Review also fixed unsafe whole-package AtomiX reset advice, predictable temporary import files, non-atomic replacement, base-folder writes, recursive symlink deletion, export stream cleanup, callback isolation, and queued requests crossing a node restart. Standalone sibling repositories remain unchanged. External companion IPC authentication is retained.

Release **1.9.0-Pandamonium**, code **71**. Validation and limitations are recorded in `review/2026-10-06/PANDAMONIUM_DIRECT_REVIEW.md`.

Direct integration validation complete: 1,649 unit-test executions; release lint with zero errors; the dedicated offline emulator fixture passed; source provenance and transport architecture checks passed. Signed local artifact: `dist/pandamonium-1.9.0.apk` with adjacent SHA-256 file.

## Head-to-toe review: 1.9.1

Eleven confirmed findings fixed: ETH vault preservation, Casino encrypted-secret failure/recovery, atomic core backup imports, FutureCash reply validation and callback threading, shared-queue wallet authorization, Casino service lifecycle, notification identity isolation, reply-log allocations, Terminal IDE navigation, and complete snapshot source verification.

Release **1.9.1-Pandamonium**, code **72**: `dist/pandamonium-1.9.1.apk`. All 1,668 unit-test executions passed; lint reports zero errors and 317 warnings; the expanded offline emulator fixture passed. See `review/2026-10-06/PANDAMONIUM_HEAD_TO_TOE_REVIEW.md` for findings, evidence and validation limits. Built locally without publication or physical-device installation.


## Classic mode: 1.9.2

Pandamonium now exposes one **Classic mode** switch in Settings → Startup Params, also reachable before creating/restoring the first wallet. Off remains the default (`-blockaskeyuses -lowram`); on omits both flags. Save applies on the next node start; Save & Restart uses the existing clean restart flow. PandaBear and BlackBear keep their fixed startup profiles.

The existing core resets startup globals on every restart. Existing key rows, addresses and counters are retained; new or seed-restored keys follow the selected derivation mode. A mode change does not convert existing keys. Restore limits and home/drawer labels follow the applicable mode. Existing free-text low-RAM/key-mode overrides are removed, including the individual SQL aliases, so they cannot defeat the switch.

Local release: **1.9.2-Pandamonium**, code **73**, `dist/pandamonium-1.9.2.apk`. Validation is recorded in `review/2026-10-06/PANDAMONIUM_CLASSIC_MODE_REVIEW.md`.

Validation complete: 339 unit tests passed across the three flavors, zero lint errors/317 warnings, both disposable emulator mode round trips passed, and release signature/checksum verified.


## Corrections: 1.9.3 candidate (2026-10-07)

The app is **Minima Core**; Pandamonium, BlackBear and PandaBear are build names. The Pandamonium hamburger and sidebar now open on the left, with Minima Core above Pandamonium and no unsolicited tagline. MinimaMail joins the native destinations. The screenshot preference updates living retained windows and is reapplied on resume. External SDK discovery now includes Pandamonium while preserving the existing receiver, enable/admin controls and single-node command routing.

Candidate artifact: `dist/minima-core-Pandamonium-1.9.3-candidate.apk` (code 74); SDK: `dist/minimaapi-1.9.3.aar`. Review and validation are in `review/2026-10-07/`. The user's PandaPools error remains unresolved pending exact error text. Existing external companion APKs need SDK updates; affected app names have been requested. Nothing from this candidate has been installed on the Fold or published.

Additional requested app: Vesta. The closest local source is `../vestr` (Minima Vestr 0.4.4); identity confirmation is pending. It has not yet been imported.
