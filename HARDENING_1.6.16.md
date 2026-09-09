# Minima Core 1.6.16-ui-h2 (44)

Implements the six recommendations in `IMPROVEMENT_REVIEW_2026-09-09.md`.
The six-decimal wallet summary, exact detail amounts, keyboard behaviour, theme
fix, and retained resync progress from 1.6.15 remain part of this build.

## Changes and reuse

1. **Token metadata downloads:** reused PandaPools/UTXO `NetFetch` with the Core
   package/User-Agent and a connection-factory test seam. Image and verification
   URLs now use the same HTTP(S)-only, per-redirect host validation, three-redirect
   limit and byte caps. Inline image data also receives a pre-decode size cap.
   The sibling implementation's documented DNS re-resolution race remains;
   this change closes the redirect bypass, not every possible DNS-rebinding attack.

2. **Startup:** replaced `StartServiceActivity`'s uncancelled worker loops with
   main-thread readiness checks, paused while stopped and removed on destruction.
   Successful binding is tracked independently of connection delivery, so leaving
   before `onServiceConnected` still unbinds. After two minutes the user can keep
   waiting or close; this does not cancel or reset the foreground node. Startup
   errors and service-start exceptions offer explicit recovery actions. Reused
   existing main-thread/lifecycle patterns from Core and UTXO `NodeApi`.

3. **API input validation:** missing/empty authentication IDs, unknown/missing
   response IDs, missing payloads and invalid URI schemes are rejected safely.
   Authenticated malformed JSON becomes an explicit failed response. Handler
   removal is atomic, preventing duplicate delivery; destruction clears handlers
   and suppresses late replies. File-read errors are JSON-escaped correctly.
   This changes the `minimaapi` source and rebuilt AAR. Already installed companion
   apps retain their bundled API until rebuilt; installing Core cannot replace it.

4. **Read refreshes:** wallet and dashboard refresh bursts produce at most one
   active read and one follow-up per view. Superseded/disconnected results are
   discarded; destroyed views receive no render callbacks. Three shared read
   workers have a 64-job queue and report overload rather than executing on the
   caller. Writes/resync retain their independent execution path. Block events
   dispatch dashboard updates on the main thread. `CoalescingRefresh` is the small
   new helper needed beyond the existing in-flight pattern.

5. **Icons:** retain the existing 6 MiB bitmap cache and image downsampling.
   `(size, URL)` requests now share one fetch with weak subscribers, four workers,
   a 64-job queue, and a bounded 30-second failure cache. Recycled views reject
   obsolete results. The view owns its subscription, so static queued work does
   not retain the Activity. `SharedRequests` extends `WebValidate`'s existing
   in-flight pattern to deliver to multiple consumers.

6. **Verification recovery:** cache by token ID plus verification URL, distinguish
   unavailable downloads from nonmatching documents, retry transient failures
   after 30 seconds, and expire definitive results after five minutes. Cache holds
   at most 512 entries. Visible wallet rows check for expired metadata on the
   existing ten-second display tick; binding a row also retries expired checks.
   Off-screen rows do not cause repeated redraws. `ExpiringCache` supplies bounded
   storage and an injectable monotonic clock for deterministic expiry tests.

## Validation design

- JVM tests reuse PandaPools private-address/scheme cases and add manual redirect
  chains, private redirect refusal before connection, relative redirects, hop
  limits and exact byte caps, with no network traffic.
- Controlled executors verify a burst of 100 repeated refreshes produces two
  reads, and repeated subscribers to one icon produce one fetch. Also cover
  stale/disconnected results, closure, overload/retry, expiry and cache eviction.
  These demonstrate work reduction in controlled cases, not measured phone
  battery savings or frame-rate improvements.
- Android API tests capture outgoing messages rather than contacting a node, and
  verify malformed input, explicit JSON failure, duplicate suppression, owner
  destruction, and real FileProvider content-URI delivery.
- A debug-only startup harness never starts or binds a real node. It tests leaving
  before connection, rotation/backgrounding, the slow-startup action, and startup
  failure navigation. The harness is excluded from release builds.
- Existing decimal, keyboard and resync layout tests remain in the suite. Actual
  blockchain imports, live transactions and real-node startup failure injection
  are outside this validation.

## Results

- Debug/release APK and API AAR builds succeeded.
- All 27 JVM tests passed, including 14 new hardening/concurrency cases.
- All 18 Android instrumentation tests passed together on the isolated API 36.1
  emulator. Initial runs were interrupted by emulator system ANR dialogs; a cold
  boot resolved the environment problem. Startup dialog assertions explicitly
  target dialog roots instead of the background Activity window.
- App and API release lint passed with zero errors (warnings remain).
- Release APK identity is `org.minimarex.minimacore`, version `1.6.16-ui-h2`, code
  `44`; the debug-only startup harness is absent from its manifest.
- APK signature matches the existing family certificate SHA-256:
  `eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f`.
- Artifacts: `dist/minima-core-ui-1.6.16.apk`, `dist/minimaapi-1.6.16.aar`, their
  SHA-256 sidecars, and `dist/minima-core-ui-1.6.16-android-tests.log`.
- APK SHA-256: `69ea695e44754fbcffc0b23c83037c9eca4605c096c8fc2bc675627839e29b03`.
- AAR SHA-256: `fb7f4c176cc56c461bc4647d1b21d3a9ba80798339ff984285423d9415707f4e`.

## Code Review

### Summary

Reviewed the changed startup/service binding, API authentication and response
delivery, redirect policy, weak image subscriptions, verification expiry, and
refresh lifecycle paths. Reads cannot queue without limit or overwrite a snapshot
after it is invalidated. Metadata workers do not own screen callbacks strongly.
The existing exact amount and resync command semantics are preserved.

### Findings

No outstanding regression found in the changed paths. Review caught and fixed a
disconnected-node result invalidation gap and metadata expiry checks scanning
off-screen rows. DNS re-resolution and already-bundled companion API copies are
the explicit limitations documented above.

### Verdict

✅ Approve for device testing. Automated checks pass; live-node behaviour and
performance measurements on the Z Fold remain part of device testing.

Installed on the Z Fold (RFCY71KW3LX), with ADB confirming version 1.6.16-ui-h2, code 44. The S23 was not modified. Publication authorized after device installation.
