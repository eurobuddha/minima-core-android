# Code Review

## Summary

Focused source review of release c1d9b63 (1.6.15-ui-h2), using the existing graph
to locate startup, command, wallet, image, and API paths. Confirmed three earlier
reliability/security findings and identified three opportunities to reduce repeated
work or improve recovery. This is not a full embedded-node audit or a performance
benchmark. No application code changed and no live-node experiments were performed.

## Findings

### 🟠 MAJOR — Token URL redirects bypass the private-network check

**Files:** `app/src/main/java/org/minimarex/minimacore/main/views/balance/tokens/ImageLoader.java:148`
and `WebValidate.java:53` in the same directory.

**Problem:** Only the initial host is checked; automatic redirects can subsequently
reach a loopback or LAN address. Token metadata supplies these URLs. Impact on a
local service depends on which services are enabled; no exploit was attempted.

**Fix:** Reuse the sibling UTXO/PandaPools `NetFetch` implementation, which checks
each redirect destination, restricts schemes, limits hops and response bytes, and
covers additional private address ranges. Adapt package and User-Agent; retain
Core's existing timeout/size values. Its documented DNS re-resolution race remains
a separate hardening issue, so this should not be described as complete SSRF protection.

**Validation:** Reuse PandaPools host/scheme tests and add redirect-chain tests;
the existing tests do not exercise redirects.

### 🟠 MAJOR — Startup waiting is not cancelled with the Activity

**File:** `app/src/main/java/org/minimarex/minimacore/launcher/StartServiceActivity.java:61`

**Problem:** Two polling loops have no cancellation/deadline. Destroying the
Activity before service connection can leave a thread waiting for a reference that
will never arrive. Progress-dialog dismissal also happens on the worker thread,
and callbacks can try to navigate after the Activity has gone away.

**Fix:** Track service binding independently of the connected service reference,
cancel the wait when its owner is destroyed, and deliver progress/navigation on
the main thread. Report slow/failed startup with retry rather than waiting forever.
Reuse existing lifecycle checks in `CoinsDialog.deliver` and main-thread callback
cleanup from sibling UTXO `NodeApi`; the latter wraps broadcast IPC, so it cannot
replace Core's direct service startup unchanged.

**Validation:** Leave/rotate before connection, stop the service during startup,
and verify no surviving waiter or duplicate navigation. A waiting timeout must
not erase node data or assume the underlying startup was cancelled.

### 🟠 MAJOR — Missing broadcast IDs can crash API clients

**File:** `minimaapi/src/main/java/org/minimarex/minimaapi/MinimaAPI.java:118`
(also line 42).

**Problem:** An exported response receiver calls `equals` on an Intent extra that
can be absent. `MinimaAPIReceive` does not catch the resulting null dereference.
Other missing response fields also need explicit validation before JSON/map use.

**Fix:** Reject missing/empty IDs before comparison, then validate response ID and
payload. Preserve the existing unguessable IDs and authentication requirements.
This needs a small boundary fix; the inspected UTXO wrapper does not protect this
lower-level receiver. Companion apps must rebuild with the repaired API library;
updating the node APK alone cannot repair their already-bundled copies.

**Validation:** Malformed/wrong-ID broadcasts are ignored; authenticated inline
and content-URI replies still reach the correct callback once.

### 🟡 MINOR — Refresh requests can overlap and apply stale results

**Files:** `app/src/main/java/org/minimarex/minimacore/main/views/balance/BalanceView.java:141`,
`main/views/home/HomeView.java:75`, and `utils/MinimaCMD.java:38` under the same package.

**Problem:** Wallet refresh has no in-flight or generation guard, so tab selection
and balance events can launch concurrent queries and an older result can overwrite
a newer result. Home refresh launches three commands; every command creates a raw
thread. Extra work increases during event bursts even when views are not visible.

**Fix:** Coalesce duplicate read refreshes and discard superseded responses. Reuse
the local `WebValidate.INFLIGHT` concept, with a pending-refresh flag so events
during a read trigger one follow-up. Bound read concurrency and marshal UI results
consistently. Do not blindly serialize all commands: long resync/transaction work
must not block dashboard reads, and write timeouts do not prove failure.

**Validation:** Rapid refresh/event bursts with deliberately reordered completions;
measure query count and maximum active workers before and after.

### 🟡 MINOR — Duplicate icon loads and excessive retry work

**File:** `app/src/main/java/org/minimarex/minimacore/main/views/balance/tokens/ImageLoader.java:56`

**Problem:** Every uncached render queues another fetch, including when the same
URL is already loading. Four workers limit execution but not queue size. Failed
URLs have no retry backoff. Queued tasks retain their Activity/ImageView references.

**Fix:** Keep the existing byte-bounded bitmap cache and downsampling. Adapt
`WebValidate`'s in-flight tracking to a `(size, URL)` key and notify all still-live
consumers from one fetch. Bound pending work and add short failure backoff.

**Validation:** Repeated binds of one slow icon cause one download; recycled rows
never receive another token's image; closing the screen releases subscribers.

### 🟡 MINOR — Temporary verification failures last for the process lifetime

**File:** `app/src/main/java/org/minimarex/minimacore/main/views/balance/tokens/WebValidate.java:39`

**Problem:** Network errors and actual verification failures both become cached
`false` with no expiry. Opening the wallet offline can therefore suppress a token's
verification badge even after connectivity returns. Successful results also never
expire in that process.

**Fix:** Keep the existing cache/in-flight structure, distinguish transient fetch
failure from a valid nonmatching response, and give results bounded lifetimes.
Allow a manual refresh to retry expired checks. No ready-made expiry implementation
was found in the inspected UTXO/PandaPools versions; this requires a small extension.

**Validation:** Offline-to-online recovery, expiry, and repeated refreshes during
one active request, using controlled time and fake responses.

## Reuse sources inspected

- Current `ImageLoader`, `WebValidate`, `StartServiceActivity`, `MinimaAPI`,
  `MinimaAPIReceive`, `MainAdapter`, `HomeView`, `BalanceView`, `MinimaCMD`, and
  relevant `CoinsDialog`/service callback paths.
- `../utxo/app/src/main/java/com/eurobuddha/utxo/NetFetch.java` and `NodeApi.java`.
- `../pandapools/app/src/main/java/com/eurobuddha/pandapools/NetFetch.java`:
  compared against the fully read UTXO implementation; only package/User-Agent differ.
- `../pandapools/app/src/test/java/com/eurobuddha/pandapools/NetFetchTest.java`.
- Confirmed `NetFetch` is called by both sibling apps' image and verification loaders.

## Verdict

🔁 Request changes for the reviewed hardening areas: prioritise redirect handling,
startup lifecycle, and API input validation, then refresh/image efficiency. Each
should be a separately versioned, tested change. No measured speed or battery
improvement is claimed; the proposed performance work needs before/after profiling.
