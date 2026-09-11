# Minima Core 1.6.18-ui-h2 (46)

The node now watches its own database and says when it needs a resync.

## Why

The 2026-09-11 incident ran for weeks with nothing to look at. The node stayed alive,
on-chain and a healthy foreground service while its heap pinned at the ceiling, and the
first sign anything was wrong was companion apps timing out. Nothing in the app monitored
anything — there was no heap sampling, no health notion, no thresholds — and the hourly
`Alarm` only ever restarted a **dead** service, so a node that was alive and starving looked
fine to it.

And the fix, once found, was `megammrsync action:resync` — which the user had to know to
run, on a screen they had to know to open.

## What it does

**`service/NodeHealth.java`** — a pure classifier. **`service/NodeHealthMonitor.java`** — the
poller, notification and automatic path. One `status complete:true` per check, at service
start and on the existing hourly `Alarm`. No WorkManager, no JobScheduler, no new scheduling
dependency; the app has none and does not need one to watch something that degrades over
weeks.

`OK` → `WATCH` → `DEGRADED`. A verdict must hold for **10 minutes in both directions** before
it is reported, so neither a spike nor a single quiet sample can flip it. `WATCH` and
`DEGRADED` raise a notification that opens the resync screen.

Every check logs one line through `MinimaLogger`, including the healthy ones — so it lands in
both logcat and the Logs tab:

```
Node health: OK - store 720 MB, txpow 144 MB / 22540 rows, archive 558 MB, heap 91 MB of 512 MB, mempool 0
```

A check that says nothing when all is well is indistinguishable from a check that never ran.
When the dwell has not yet elapsed the line names the pending verdict — `OK (DEGRADED
pending)` — because a reassuring "OK" beside alarming numbers is the exact failure mode this
feature exists to end.

### Heap is the primary signal; the store is secondary

Heap is what actually broke (501,501 KB of a 524,288 KB ceiling) and it measures the harm
directly. Thresholds are **fractions of `Runtime.maxMemory()`** — 0.60 warn, 0.80 act — never
absolute MB: `dalvik.vm.heapgrowthlimit` is 256m on two of the three test devices and 512m on
the third, so "warn at 400 MB" does not travel. The node's own watermark,
`max(32MB, maxMemory/20)` from `MMR.java` and `MegaMMR.java`, is reused as the floor so the
app and the node agree on "nearly out".

### The store check watches txpow only — corrected by field data

The first cut triggered on **total disk** at 250 MB / 600 MB. A check against a real node
killed it: `R58M307HEEN` reported **712 MB of store, 558 MB of it archive**, while running
perfectly. The archive is *supposed* to grow, to `MAX_KEEP_BLOCKS` (100,000 blocks, ~50 days,
`ArchiveManager.java:28`, no CLI override). Triggering on total disk would have told every
node older than fifty days to wipe and refetch its chain — which would not even help, since
the archive grows straight back.

So total disk and the archive are **reported, never triggers**. Only the txpow table carries
the pollution a resync actually clears.

Those thresholds are **provisional**, and deliberately loose. Two field data points exist: a
resynced node at 2,050 rows / 10.1 MB, and a long-running one at 22,540 rows / 144 MB that
was not complaining. `WATCH` at 250,000 rows or 400 MB and `DEGRADED` at 1,000,000 rows or
900 MB sit far above both, because the cost of a false positive is telling someone to rebuild
the chain on a node that was fine.

## Automatic resync — opt-in, off by default

New switch in Startup Params: **"Resync automatically when unhealthy"**. Off, the app tells
you. On, it resyncs unattended, with guards:

- at most **one automatic resync per 24 hours**, counted when it *starts*, so a host outage
  cannot retry around the clock;
- **never while a transaction is waiting to be mined** (`txpow.mempool`, from the same status
  reply) — a resync drops the mempool;
- never while a resync is already running, or while the node is shutting down.

Hosts are `eurobuddha.com:9001`, `spartacusrex.com:9001` and `megammr.minima.global:9001`,
**shuffled per attempt** so no single host carries every device. A host the user has
configured themselves always leads — they may be running their own node, and we should not
override that with ours.

**Failover is limited to pre-flight failures.** `megammrsync` tests the host with
`archive.sendArchiveReq` *before* `Main.archiveResetReady()` deletes `txpow.mv.db` and
`archive.mv.db`, so "Could not connect to Archive host" is safe to retry elsewhere. "Error
getting MegaMMR data from host" is thrown **after** the deletion, and is deliberately not
retried: the node state has already changed and that is a decision for the user, not a loop.

## Also: the resync screen now asks first

`SeedSyncActivity` fired the command immediately, with no warning, on a node holding real
funds. It now confirms, and says what actually happens — chain data is rebuilt, the node
stops and restarts, anything unmined is dropped, **the wallet and seed phrase are untouched**.
That last point is the one people need before they will press the button.

Both paths now go through one **`main/ResyncLauncher`**, so the manual and automatic routes
cannot drift: the pending flag is committed before the destructive command (a process death
must never look like success), the alarms are cancelled so restart stays under the user's
control, and MainActivity's service binding is released or the node's `stopSelf` at
completion never reaches `onDestroy`.

## Reuse

No new resync engine. `main/ResyncSession` already had the single-shot state machine, the
attempt counter with its stale-callback guard, host validation via the node's own
`connect.createConnectMessage`, and success decided only by `Feedback.errorOf` and never by
log text. This release adds a checker and a prompt in front of it.

## Validation

- **46 JVM tests pass** (was 45 before this change, 28 before 1.6.17), including a fixture
  built from the real zfold `status complete:true` reply and a regression test asserting that
  the archive-dominated 712 MB node classifies `OK`.
- Thresholds tested against a faked `maxMemory` of both 256 MB and 512 MB, so the fraction
  logic is proven on both device shapes.
- Dwell tested in both directions, including that a transient spike never becomes the verdict.
- Host pool tested for coverage, no duplicates, varying order, a configured host leading, and
  junk being ignored; failover classification tested to retry pre-flight failures only.
- Release lint clean. Release APK is `org.minimarex.minimacore` `1.6.18-ui-h2` (46), signed
  with the family certificate
  `eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f`.
- **On device** (`R58M307HEEN`): the check runs at startup, logs the line above, and posts no
  notification on a healthy node. This is where the disk-threshold flaw was caught.
- **Android instrumentation tests were not run** — no emulator session this release. The
  confirmation dialog and the notification tap path are therefore unexercised by automation.
- Artifacts: `dist/minima-core-ui-1.6.18.apk`, `dist/minimaapi-1.6.18.aar` and sidecars.

## What this does not do

It does not shrink anything. It watches, tells you, and offers the one thing that does work.
The underlying causes remain open and are tracked in `HARDENING_1.6.17.md`: rows marked
relevant are never deleted (`TxPoWSqlDB.java:104`), the on-chain index keeps 1,000 days, the
archive has no CLI override, `CoinsDialog` still issues an unbounded `coins relevant:true`,
`MinimaReceiver` still does not cap a command result, and the upstream `H2-lob-cleaner`
process kills are unfixed.
