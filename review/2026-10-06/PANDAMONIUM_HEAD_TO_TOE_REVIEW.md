# Code Review — Pandamonium 1.9.1

## Summary

Reviewed the Pandamonium integration across the Android host, embedded command/file/event transport, eleven native feature libraries, financial reply handling, persistent secrets, background services, notifications, navigation, snapshot generation and release packaging. Eleven confirmed findings below are fixed locally. Three concern potential loss or exposure of financial recovery data. The shared transport retains bounded queues, cancellation, node-instance checks, late financial replies, native command results and external-client authentication.

This is a broad, risk-focused source and runtime review, not a claim that every line of the inherited applications or every smart contract has been independently re-audited. The source inventory contains 330 feature Java files plus 73 host and eight SDK Java files; manual review concentrated on changed boundaries and sensitive callers, supported by the existing feature regression suites. No live financial transaction was submitted.

## Findings

### 🔴 CRITICAL — Opening the ETH vault could delete a recoverable private key — FIXED

**File:** `integrated/ethwallet/src/main/java/com/eurobuddha/ethwallet/KeyVault.java:22`

**Problem:** The inherited constructor caught any opening failure, deleted the encrypted preferences and Keystore alias, and retried. A temporary Keystore failure could therefore permanently discard an imported key. The UI also removed the selected wallet source and incorrectly said the key was gone.

**Fix:** Keep encrypted data and the alias intact, report storage unavailable, and preserve the imported-wallet selection for a later retry. Reused the existing encrypted storage and unavailable-state behavior. A regression simulates an opening failure followed by successful recovery and verifies that no storage deletion or replacement write occurs. The sibling standalone app is unchanged.

### 🔴 CRITICAL — Casino secrets could disappear when encryption recovered — FIXED

**File:** `integrated/casino/src/main/java/com/eurobuddha/casino/SecretStore.java:33`

**Problem:** An encryption failure silently switched new financial secrets to plaintext preferences. Once encryption recovered, the constructor switched back to a different store, hiding those preimages from reveal/resolve operations. Losing access to a committed preimage can forfeit a bet at its deadline.

**Fix:** Refuse new financial commitments unless encrypted persistence succeeds. Preserve the existing durable-write check in `CasinoTxn`. Read old plaintext fallback secrets alongside encrypted storage for recovery, but never create new plaintext secrets. The regression covers both outage and recovery. Existing plaintext recovery data is deliberately retained; this change does not claim to encrypt or erase copies created by an earlier build.

### 🔴 CRITICAL — Failed backup imports could destroy an existing backup — FIXED

**File:** `app/src/main/java/org/minimarex/minimacore/main/backup/NodeFiles.java:187`

**Problem:** The core restore-screen import opened the destination directly. A truncated or disconnected source could replace a valid backup with partial data, or delete the destination after a size mismatch. Filez already had the safer implementation.

**Fix:** Extracted `MinimaReceiver`'s unique temporary-file, sync, length-check and atomic-replacement implementation into `NodeFiles`; both import routes now call it. Existing containment and live-database checks remain. Tests verify preservation of old bytes on a short copy and an I/O exception, successful replacement, and temporary-file cleanup. The emulator repeats the direct import round-trip.

### 🟠 MAJOR — FutureCash could report a failed transport as a successful post — FIXED

**Files:** `integrated/futurecash-next/src/main/java/com/eurobuddha/futurecashnext/Node.java:54`, `Tx.java:65`

**Problem:** `txnpost` defaulted a missing `status` to success, while `unreadable` recognized only an empty object. Direct failures contain `transporterror` and no status, so they could produce a false “posted/swept” result.

**Fix:** Reused PandaDEX's Boolean-status validation. Missing or malformed status is an unknown outcome, never success; async mining remains accepted when the node envelope actually reports success. The packaged JAR's `Command.getJSONReply()` was inspected and supplies Boolean status. Four regressions cover missing/malformed replies, transport failure, mining/rejection distinction, and stopping after an uncertain signing result while retaining cleanup.

### 🟠 MAJOR — Wallet authorization was checked before the wrong queue — FIXED

**Files:** `minimaapi/src/main/java/org/minimarex/minimaapi/direct/DirectNodeApi.java:36`, `integrated/pandadex/src/main/java/com/eurobuddha/pandadex/CommandSession.java`, `NodeApi.java`

**Problem:** PandaDEX checked its existing wallet-session predicate before entering the shared node queue. Invalidating the session while another app occupied that queue did not stop the already-enqueued mutation.

**Fix:** Carry the same predicate into `Request.begin()`, immediately before mutation execution. Publish the existing generation fields safely across threads. Preserve completion of writes that already started and retain conservative uncertainty markers. Unit tests cover invalidation and throwing/missing guards. The emulator occupies the real queue, invalidates a queued request, and verifies that it never reaches the node command runner.

### 🟠 MAJOR — FutureCash callback state was modified across threads — FIXED

**File:** `integrated/futurecash-next/src/main/java/com/eurobuddha/futurecashnext/NodeApi.java:79`

**Problem:** `Node.cmd` runs on a guardian worker, but `NodeApi.cmd` directly modified a HashSet and completion state also accessed by main-thread callbacks and destruction. This violated the wrapper's own main-thread ownership assumption.

**Fix:** Dispatch command setup through the existing main Handler, following the other financial wrappers' serialization pattern. Requests submitted after release receive an error rather than silently leaving a blocking caller waiting. Two dedicated regressions verify worker submission waits for the main Handler and release before dispatch delivers an error without submitting.

### 🟠 MAJOR — Casino's foreground-service failure could kill the shared process — FIXED

**Files:** `integrated/casino/src/main/java/com/eurobuddha/casino/CasinoService.java:262`, `integrated/casino/src/main/AndroidManifest.xml`

**Problem:** The persistent watcher used `dataSync`, did not handle foreground-start rejection, and had no timeout callback. Android 15+ applies a background execution budget to this type; failure to stop after timeout causes a fatal service exception. In Pandamonium this affects the shared node process. See [Android's foreground-service timeout documentation](https://developer.android.com/develop/background-work/services/fgs/timeout).

**Fix:** Reused the existing FutureCash/PandaPools persistent-watcher pattern: declared `specialUse` with its purpose, selected it on Android 14+, contained start rejection, and added graceful timeout shutdown. The merged manifest and emulator fixture verify the service declaration. A six-hour background soak was not performed.

### 🟠 MAJOR — Alert IDs collided with foreground notifications — FIXED

**Files:** `integrated/futurecash-next/src/main/java/com/eurobuddha/futurecashnext/Notifier.java`, Casino/AtomiX alert producers, PandaPools `StrandingWatch.java`

**Problem:** Android notification channels do not isolate numeric IDs within a package. FutureCash's second alert used ID 3101, also its guardian's foreground ID. Incrementing and hashed IDs from the other integrated apps could overwrite or cancel unrelated notifications.

**Fix:** Reused Casino `TimeoutAlerts`' existing `(tag, id)` pattern for non-foreground alerts and matched their cancellation calls. Foreground IDs remain distinct. FutureCash's alert counter is atomic. The emulator posts the first two FutureCash alerts and verifies that an untagged ID-3101 notification remains intact.

### 🟡 MINOR — Logging serialized whole replies on the UI thread — FIXED

**File:** `integrated/pandadex/src/main/java/com/eurobuddha/pandadex/NodeApi.java:227`

**Problem:** A diagnostic character count called `toString()` on every full response on the main thread, recreating large allocations that direct transport was intended to eliminate.

**Fix:** Keep the command/status diagnostic and remove the unnecessary full-response serialization.

### 🟡 MINOR — Terminal IDE could not be selected from its editor — FIXED

**File:** `app/src/pandamonium/java/org/minimarex/minimacore/integrated/PandamoniumHooks.java:117`

**Problem:** The script editor shares Terminal IDE's selected menu index. Clicking that entry returned early even though the destination activity was different.

**Fix:** Compare the actual activity class with the requested destination before treating the click as a no-op.

### 🟡 MINOR — Snapshot verification ignored unexpected source files — FIXED

**File:** `scripts/import-pandamonium.py`

**Problem:** Verification compared generated files but did not reject additional source files already present in a snapshot. Such files could be compiled without being explained by the importer.

**Fix:** Check the reverse source-file inventory as well. Verified both normal reproduction of all eleven snapshots and rejection of a temporary unexpected source fixture. Review adaptations live in `scripts/adapt-pandamonium-safety.py`; their regression fixtures are also reproduced by the importer.

## Coverage and validation

- Read the existing graph and review context, then checked graph-selected paths against current source; the graph predates the new integrated modules and was not treated as current evidence for those modules.
- Reviewed command ownership, node readiness/restart checks, shared reads, mutation queues, callback delivery, event lifetime, external IPC gates, file containment/import/export, backup handling, app lifecycle, preferences/databases/Keystore aliases, provider identities, foreground services, notifications, financial response classifiers, and import/release configuration.
- Checked current and sibling implementations before adapting them. Sibling repositories remain unchanged. Financial contract/transaction construction algorithms and the embedded JAR are unchanged in this review.
- **1,668 passing unit-test executions:** base 109 in each of Classic/Block/Pandamonium, SDK 18, AtomiX 260, PandaPools 296, FutureCash 34, PandaDEX 701, Casino 26, ETH Wallet 1, Entropy 5. No failures/errors/skips in these results. Older unrelated result files were excluded from the count.
- **Release lint:** zero errors/fatal issues, 317 warnings, unchanged in count from 1.9.0.
- **Offline Android 36.1 emulator:** one dedicated integration fixture passed in 55.612 seconds. It covers fresh disposable wallet startup, block-key-use/low-RAM flags, native command parity and batches, real shared-queue cancellation, guarded 384 KiB file operations, Terminal IDE log persistence, notification isolation, all eleven destinations, retained core navigation, insets and drawer Back behavior.
- Snapshot/provenance reproduction, unexpected-source rejection, direct-transport architecture checks, merged manifest checks, release signature and APK identity passed.
- No physical phone was used. The emulator had no active default network. No funded swap, pool, casino bet, or sweep was executed. Long background operation and live-chain settlement remain outside this validation.

Evidence: `pandamonium-review-full-build.log`, `pandamonium-review-targeted.log`, `pandamonium-review-threading.log`, `pandamonium-review-emulator.log`, `pandamonium-review-import.log`, `pandamonium-review-signature.txt`, `pandamonium-review-validation.json` in this directory.

## Artifact

- `dist/pandamonium-1.9.1.apk` — **1.9.1-Pandamonium**, version code **72**, package `org.minimarex.pandamonium`.
- Size: **32,787,526 bytes**.
- SHA-256: `1a6b22fb1e5f28ebfcc334ce3dba83d0afe22d96f9a0c6ebd7120812a1568221`.
- Signing-certificate SHA-256: `eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f`.
- Built locally; no commit, push or publication was performed for this review.

## Verdict

✅ **Approve with suggestions**, for the reviewed local changes within the validation scope above. All eleven confirmed findings were fixed. Retain live-chain settlement and background soak testing as separate release evidence; the offline tests do not establish those outcomes.
