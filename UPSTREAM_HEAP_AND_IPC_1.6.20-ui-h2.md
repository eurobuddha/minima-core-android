# Overflow on Android: heap and IPC — what our fork does differently

**For upstream. Fork state: minimaCore `1.6.20-ui-h2` (versionCode 48), node jar at 1.1.2.**

Two things overflow on a phone, and they present completely differently. One kills the
*companion* app; the other leaves the node alive, on-chain, and useless. This document covers
both: the mechanism, how each shows up in the field, what we changed, and — the part most worth
your time — **what is still broken in stock that we have not fixed.**

Everything below was re-verified against the working tree before writing. Where a line is
upstream code we did not touch, it is cited from our own 1.1.2 tree, which differs from upstream
in exactly seven files (listed in `core/minima-core/UPSTREAM_CHANGES.md`); anything outside those
seven *is* your code.

### What this adds to the docs you already have

| doc | already covers |
|---|---|
| `core/minima-core/UPSTREAM_CHANGES.md` | the jar: `signData`, MegaMMR import heap work, `removeScript` |
| `apks/base/UPSTREAM_IPC_CHANGES.md` | the broadcast-IPC additions |
| `apks/base/MINIMACORE_CHANGES_FOR_UPSTREAM_1.6.10-ui-h2.md` | everything up to 1.6.10 |

New here: the 2026-09-11 heap-exhaustion incident, the four releases that followed
(1.6.17–1.6.20), and a corrected account of several things we previously got wrong ourselves.

---

# Part 1 — IPC overflow

## The mechanism, and why it is not obvious

A command reply crosses the Binder as one Intent String extra. There are two ceilings, and the
dangerous one is invisible from the sending side:

`length()` counts **UTF-16 chars**. `Parcel.writeString` serialises roughly **2 bytes per char**.
So a reply comfortably under a 256,000-**char** cap builds a ~512 KB **parcel**, and the broadcast
queue kills the **receiving** process at around a 256 KB parcel — `TransactionTooLargeException`
raised before any app callback runs. Nothing is catchable on either side. The sender sees a
successful send; the receiver simply dies.

Observed live: parcel size **262,240 bytes** for a ~131K-char `coins` reply, killing a companion
app on every launch. The diagnostic signature in the event log is
`am_kill … "Can't deliver broadcast"` — if you ever get a report of a companion app that dies
instantly and reproducibly on open, that log line is what to look for.

**Any wallet whose `coins` output grows through the 128K–256K-char band hits this
deterministically.** It is not load-dependent or racy. It is a function of wallet size.

## What we changed

- **Split the cap.** Inbound commands keep `MAX_MESSAGE_LEN = 256000`
  (`app/src/main/java/org/minimarex/minimacore/receiver/MinimaReceiver.java:30`) so signing
  payloads do not regress. Replies use a separate `MAX_RESPONSE_LEN = 100000` (`:41`) — about a
  200 KB parcel, safely clear of the kill line. Nineteen lines, and it is the single change we
  would most like you to take.
- **A `content://` hand-off above that cap.** Oversized results are written to
  `cacheDir/ipcresponses/`, exposed through a non-exported FileProvider, read-granted to exactly
  the calling package, and the reply carries a URI instead of the payload. A file descriptor
  crosses the Binder, not the data. Negotiated per command and three-way compatible: old client →
  new node gets the old "Result too long!" stub byte-identically; new client → old node sees its
  extra ignored and gets the stub. Small replies keep the inline path with zero overhead.
- **Commands run off the broadcast thread.** `onReceive` used to run them synchronously on the
  main thread, so anything opening a socket inline (`megammrsync`, `archive resync`) threw
  `NetworkOnMainThreadException` and it was swallowed into a misleading
  "Could not connect to Archive host!".
- **An admin-gated FILE bridge**, because `backup`, `megammr action:export` and `txnexport` all
  write into the node's private `getFilesDir()`, which no other app can reach without adb.

Full detail on all four is in `UPSTREAM_IPC_CHANGES.md` and §2 of the 1.6.10 doc.

## Still open on our side

`MinimaReceiver.java:179` runs the command and holds the whole result as a `String` before any
cap is applied:

```java
String result = mMinima.runMinimaCMD(cmd, false, userid);
if(result.length() > MAX_RESPONSE_LEN){ … }
```

The **inbound command** is capped and the **inline reply** is capped, but the result itself is
not. For an oversized reply taking the file path, three copies exist at once: the node's JSON
object graph, this UTF-16 `String`, and a fresh UTF-8 `byte[]` for the file write. On a heap
already under pressure (Part 2) that is exactly the allocation that tips it. We have not fixed
this yet.

Related, and ours to fix: the IPC command executor is a single thread
(`MinimaReceiver.java:55`, `Executors.newSingleThreadExecutor()`). That is deliberate — ordering
must be serialised — but it means one slow command blocks every companion app at once. Part 2
shows what that costs when a query goes bad.

---

# Part 2 — Heap overflow

Two different problems that both end in "out of heap". The first is well understood and already
documented; the second is what bit us this week.

## 2.1 Import-time — already sent to you

`UPSTREAM_CHANGES.md` Part 2 covers this: OOM is an `Error` not an `Exception` so
`catch(Exception)` let it kill the node; the IBD now streams in 256-block batches so peak heap is
MegaMMR + cascade + one batch rather than the whole decoded graph; a heap watermark of
`max(32MB, maxMemory/20)` aborts cleanly instead of dying; and the MMR **tree**, not the coin
table, turned out to be the real ceiling — measured 1,513,769 entries ≈ 1.0 GB, loaded *before*
the coins. Practical conclusion unchanged: mainnet MegaMMR needs ~1.2 GB, so on a 512 MB phone
the only viable path is `-megammr` **plus** `-megaprune`.

Stock has none of this — zero `maxMemory` references in `MMR.java` or `MegaMMR.java`.

## 2.2 Steady-state — the 2026-09-11 incident

This one is new, and the failure mode is the interesting part.

**Every companion app stopped working at once, and the node looked perfectly healthy.** Alive,
foreground service with `crashCount=0`, connected to peers, following the chain. Nothing in the
UI or the notification said anything was wrong. It reads exactly like a dropped IPC connection,
and that is the wrong trail — it costs an hour.

Measured on a Galaxy S10+ (`R58M307HEEN`), 25 days after install:

| | |
|---|---|
| Dalvik heap | **501,501 KB allocated of 524,288 KB** |
| `"Waiting for a blocking GC"` | **12,247** in a two-hour logcat window |
| `Long monitor contention` | **1,626 mentions, every one naming `TxPoWSqlDB`** |
| longest monitor hold | **47.171 s** |
| app data dir | 717.9 MB |

`NodeApi.READ_TIMEOUT_MS` is 30 s. A 47-second lock hold on the single-threaded IPC executor ends
every companion command, whether or not the heap recovers.

### The causal chain

1. **`Wallet.removeScript` left the relevance caches stale.** Demoting a shared script with
   `newscript trackall:false` deleted the DB row, but the in-memory `mAllTrackedAddress` cache
   kept the address until restart — so a node that had ever tracked a shared covenant kept
   adopting **every stranger's coin at that address into its relevant set, every block**. (Our
   fix, `UPSTREAM_CHANGES.md` Part 3. Please take it.)
2. **Those rows are then immortal.** `TxPoWSqlDB.java:118` —
   `DELETE FROM txpow WHERE timemilli < ? AND isrelevant=0`. Rows marked relevant are **never**
   deleted. The cleanup fix stopped the bleeding; it could not clean the wound.
3. **The table gets large, and the query to read it was unindexed** (next section), so
   `TxPoWSqlDB` methods — all `synchronized` on the instance — hold the monitor for tens of
   seconds.
4. **Heap and lock starve each other.** The oversized relevant set is re-materialised per
   `TxPoWTreeNode` and again on every cascade, while the slow queries hold the lock under GC
   pressure.

### The index fix (our seventh jar file, `f7f0e08`)

`createSQL()` created exactly one index — `fastsearch (txpowid, parentid)` — while five hot
statements sorted or filtered on `timemilli` and `isrelevant`, neither indexed. So
`SELECT * FROM txpow ORDER BY timemilli DESC LIMIT ?` was a full table scan that dragged every
row's `txpowdata` **BLOB** through a sort.

We added:

```sql
CREATE INDEX IF NOT EXISTS txpowtime     ON txpow ( timemilli )
CREATE INDEX IF NOT EXISTS txpowrelevant ON txpow ( isrelevant, timemilli )
```

**The index alone does nothing.** H2 will not use an index for a bare `ORDER BY … LIMIT` with no
`WHERE`, so `SQL_SELECT_TOPTXPOW` also carries a deliberate, load-bearing `timemilli > 0`
(`TxPoWSqlDB.java:130`). Verified with `EXPLAIN ANALYZE` against **H2 2.1.214** on 60k rows:

| | plan | page reads |
|---|---|---|
| index only, query unchanged | `tableScan` | 25,535 |
| index + `WHERE timemilli > 0` | index | **3,755** |

Wall-clock on a warm SSD showed 85 ms vs 88 ms — no signal at all, and it would have sent us the
wrong way. Measure pages read, not elapsed time.

Because `createSQL()` already used `CREATE INDEX IF NOT EXISTS` and runs on every open, existing
databases index themselves on the next start with no migration. Expect one slow first boot on a
large store.

### Be honest about what fixed it

**A `megammrsync action:resync` fixed both affected devices; the index patch did not.** The
cleanest evidence: the second device recovered fully on the *unpatched* build, same process, no
restart — heap 501,501 KB → 132,314 KB, blocking GCs 12,247 → 0 — from a resync alone. The zfold
went from a ~1.18 GB store to **29.0 MB**.

So the index change is preventive. It stops a large txpow table locking the node again; it has
never been demonstrated against a live fault, and we would not want you to adopt it believing
otherwise.

---

# Part 3 — Four things we had wrong, in case you inherit the same assumptions

1. **The Android APK does not run the H2 in your jar.** `app/libs/minima.jar` is the nolibs jar
   with zero `org/h2` classes; the APK pins `com.h2database:h2:2.1.214` from Maven. We downgraded
   from 2.4.240 because ART's verifier rejects `org.h2.security.SHA256.getPBKDF2` on Samsung
   builds and the node dies at boot with a `VerifyError` (`H2_VERIFYERROR_FIX.md`). Worth knowing
   before anyone bumps H2. Incidentally your own `build.gradle` pins 2.1.214 too.
2. **H2's per-database page cache defaults to 16 MB, not 64 MB.** Disassembled from the linked
   jar: `MVStore.<init>` reads config key `cacheSize` with default `16`, plus a fixed 1 MB chunk
   cache; `org.h2.engine.Database` never sets `CACHE_SIZE` at all in 2.x. Six core stores plus one
   per open MiniDapp is roughly **120 MB**, not the 448 MB we first calculated. Capping it is
   worth having, but it is headroom, not a cure.
3. **`CACHE_SIZE` is in KB but lands on whole MB.** `Store.setCacheSize(kb)` does
   `mvStore.setCacheSize(max(1, kb/1024))`, so `CACHE_SIZE=512` and `CACHE_SIZE=1024` are the same
   thing, with a 1 MB floor.
4. **`largeHeap` is not always spent.** We wrote that it buys nothing because the zfold reports
   `heapgrowthlimit = heapsize = 512m`. Both other Samsungs here report `heapgrowthlimit 256m`
   with `heapsize 512m`, so on them `largeHeap="true"` is exactly what doubles the cap. Read both
   properties on the device in front of you, and never express a heap threshold in absolute MB.

---

# Part 4 — What we did in the app, not the jar

Because the 2026-09-11 failure was invisible for weeks, 1.6.18–1.6.20 added a health check in the
Android app. Nothing here needs anything from you; it is described so you know what our users
see.

It reads `status complete:true` and classifies `OK / WATCH / DEGRADED`, with each verdict having
to hold ten minutes in both directions. Heap thresholds are **fractions of
`Runtime.maxMemory()`** (0.60 warn, 0.80 act) plus your own `max(32MB, maxMemory/20)` watermark as
the floor, so the app and the node agree on "nearly out". A `WATCH` or `DEGRADED` verdict offers a
resync; an opt-in switch (off by default) will run one unattended, at most daily and never while
the mempool is non-empty.

Two findings from that work that generalise beyond us:

- **`AlarmManager` is not a schedule on modern Android.** We first drove the check from an hourly
  `setInexactRepeating`. On a Z Fold 7 that alarm had **not fired once six minutes after start** —
  Doze and OEM battery management defer it indefinitely. It is now driven by the node's own
  `NEWBLOCK` heartbeat (~50 s) with the interval enforced in our own code. If anything upstream
  relies on `AlarmManager` for liveness on Android, it is not reliable.
- **The store size is a bad health signal, and we shipped that mistake before catching it.** Our
  first thresholds fired on total disk. A real reading was **712 MB of store, 558 MB of it
  archive**, on a node running perfectly — because the archive is *supposed* to grow to
  `MAX_KEEP_BLOCKS`. That threshold would have told every node older than fifty days to wipe and
  refetch its chain, which would not even have helped. Only the txpow table is diagnostic.

---

# Part 5 — Still broken in stock, and not fixed by us

The part we would most like you to look at. All verified in our 1.1.2 tree in files we have not
touched, so this is your code as it stands.

### 1. `H2-lob-cleaner` kills the process outright

Two FATAL process deaths in twelve hours on one device:

```
FATAL EXCEPTION: H2-lob-cleaner
org.h2.mvstore.MVStoreException: Reading from file ... failed at 525340455 (length -1) [2.1.214/1]
  at org.h2.mvstore.db.LobStorageMap.doRemoveLob(LobStorageMap.java:460)
Caused by: java.nio.channels.ClosedByInterruptException
```

H2's LOB-cleaner worker is interrupted mid-read; NIO closes the channel on interrupt; H2 throws on
a thread with no handler; Android kills the process. The interrupt comes from the 30-minute
`refreshSQLDB()` → `closeAndReopen()` cycle (`Main.java:99`, `CLEANDB_RAM_TIMER = 1000*60*30`)
racing the cleaner. Neither H2 2.1.214 nor 2.4.240 installs an uncaught-exception handler on that
executor, so upgrading H2 would not help. Slow I/O under GC pressure widens the window, which is
why the two failures compound.

### 2. Rows marked relevant are never deleted

`TxPoWSqlDB.java:118`. Combined with anything that inflates the relevant set, the txpow table
grows without bound and nothing reclaims it. This is the root of §2.2 and the one structural fix
we could not make ourselves without changing history semantics.

### 3. Retention constants a mobile node cannot reach

- `ArchiveManager.java:28` — `MAX_KEEP_BLOCKS = 2000 * NUMBER_DAYS_ARCHIVE` = **100,000 blocks
  (~50 days)**, hard-coded, **no CLI override**, and written unconditionally on mobile. On a
  phone this is the single largest consumer of disk: 558 MB of a 712 MB store on our test device.
- `TxPoWOnChainDB.java:22` — `MAX_ONCHAINSQL_MILLI` = **1,000 days**, i.e. cleanup is a no-op for
  about three years.

A `-archivedays` style parameter, or simply a smaller default under `-mobile`, would help a lot.
Note `-mobile` currently gates only metrics/P2P behaviour and is not wired to any memory or
retention tuning.

### 4. `status complete:true` NPEs during startup

Called while the node is coming up, it throws — first on `SeedRow.getSeed()` with the wallet not
yet loaded, then on `TxPoWTreeNode.getTxPoW()` with no chain tip. Harmless to a human typing it at
a terminal; anything that *polls* status has to tolerate it, and we had to add a retry path. A
clean "not ready yet" reply would be better than an NPE.

### 5. `restore` has no rollback

A wrong password is handled well — caught at `restore.java:109-111` before anything destructive.
Everything after that has **one** `catch` in the whole file, and it is that one. A corrupt or
truncated file fails with the node already torn down, `cascade.db` / `chaintree.db` /
`userprefs.db` / `p2p.db` already overwritten **in place**, the txpow DB already wiped, and the
wallet already `DROP ALL OBJECTS`'d. No temp-then-rename, no staging, no recovery.
`setAllowSaveState(false)` is also never re-enabled on the error path. `decryptbackup` exists and
is non-destructive, so a pre-flight is possible — but nothing makes you do it.

### 6. `backup` with no password is effectively plaintext

`backup.java:109`:

```java
String password = getParam("password","minima");
```

No `password:` does not mean "unencrypted" — it means AES with the literal string `"minima"`,
which every Minima binary knows. `decryptbackup` uses the same default and its success message
says so out loud: *"You can now open the output file in a HEX editor to get the seed if your
backup was not locked."*

The sharp end: **the 24-hour auto-backup runs bare `backup`** (`Main.java:1188-1191`,
`runMultiCommand("backup")` under `isAutoBackup()`), so
every node with `auto:true` is writing wallet-and-seed files protected by a universally known
password into its data folder, unpruned, indefinitely. On Android that folder is app-private, so
it is contained today — but it is one `allowBackup` misconfiguration away from being a cloud
upload, which is a mistake the stock Android build was in fact making until we fixed it
(`MINIMACORE_CHANGES_FOR_UPSTREAM_1.6.10-ui-h2.md` §3.1).

We would suggest: refuse a blank/absent password for a user-invoked `backup`, and give the
auto-backup a device-specific key rather than a constant.

### 7. `restoresync` rejects its own documented parameter

`restoresync.java:72` — `getValidParams()` returns `{"file","password","host"}`. `keyuses` is
missing, so `restoresync … keyuses:512` is rejected with `Invalid parameter : keyuses`, even
though the help text documents it and line 214 reads it. One-word fix.

---

# Appendix — how to tell the two overflows apart in the field

| symptom | it is |
|---|---|
| A companion app dies instantly and reproducibly on open; `am_kill … "Can't deliver broadcast"` in the event log | **IPC overflow.** Reply crossed the parcel kill line. |
| Every companion times out at once, but the node is alive, on-chain and `crashCount=0` | **Heap/lock.** Count `"Waiting for a blocking GC"` for the node pid: 0–3 is normal startup, thousands is thrash. Then check `Long monitor contention` for the class holding the lock. |
| Node process disappears and restarts on its own | Look in the **crash** buffer, not main — likely `H2-lob-cleaner` (§5.1). |

For the second row, `status complete:true` is the instrument that matters —
`memory.disk`, `memory.files.*` and `txpow.txpowdb` give the real picture. Note that
`adb shell dumpsys diskstats` is a **cached snapshot** and will lie: it reported 1,182.9 MB for a
device whose store had already fallen to 29.0 MB, unchanged across both an APK install and a
97.5% reduction.
