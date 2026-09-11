# Heap-exhaustion captures — 2026-09-11

Evidence captured **before** any build was installed, because installing anything restarts the
node and destroys the state. Two devices, both Samsung, both attached over adb.

> **The raw captures are deliberately not in this repo** (see `.gitignore` here). This repo is
> public, and `dumpsys diskstats` carries the device's complete installed-app list while the
> events buffer carries other apps' activity — neither belongs in a public history. The dumps
> stay on the machine that took them; everything below is the analysis, and the commands at the
> end reproduce it from a fresh capture.

| directory | device | build | role |
|---|---|---|---|
| `pinned-R58M307HEEN-20260911-1626/` | Galaxy S10+ (`beyond2`) | ours, 1.6.15-ui-h2 (vc43) | **subject — in the failure state** |
| `control-R3CW30FN1FM-20260911-1627/` | S23 Ultra (`dm3q`) | **stock** minima-core 1.2.6 (vc20) | control |

The zfold7 `RFCY71KW3LX` from `HEAP_EXHAUSTION_2026-09-11.md` was not attached.

## Headline numbers

| | subject (ours, vc43) | control (stock, vc20) |
|---|---|---|
| first installed | 2026-08-17 (**25 days**) | 2026-06-27 (**76 days**) |
| app data dir | **717.9 MB** | 160.6 MB |
| Dalvik heap | **501,501 / 524,288 KB** (22.8 MB free) | 71,000 KB alloc |
| `Waiting for a blocking GC` | **12,247** in 2 h 05 m | **0** |
| `Long monitor contention` | **575 events / 1,626 mentions** | — |
| process uptime at capture | 5.4 h (a restart, see below) | 2.2 days |

The *older* node holds a quarter of the data. Age does not explain the difference.

## What the capture shows

### 1. The lock, not just the heap — and it names one store

Every one of the 1,626 `Long monitor contention` mentions names **`TxPoWSqlDB`**. Not the
archive, not the wallet, not the coin DB. Breakdown by holder:

| site | events |
|---|---|
| `TxPoWSqlDB.java:409` (`getLatestTxPoW`) | **506** |
| `TxPoWSqlDB.java:301` (`getChildBlocks`) | 17 |
| `TxPoWSqlDB.java:547` | 10 |
| `TxPoWSqlDB.java:261` | 7 |
| `TxPoWSqlDB.java:474` | 2 |

Owner thread for 513 of them: **`pool-3-thread-1`**. That is
`MinimaReceiver.mCmdExecutor = Executors.newSingleThreadExecutor()`
(`receiver/MinimaReceiver.java:55`) — the **IPC command thread**. So a companion app is calling
`history`, which is the only caller of `getLatestTxPoW` (`history.java:144`).

Wait-time distribution:

| bucket | count |
|---|---|
| < 2 s | 3,253 |
| 2–5 s | 535 |
| 5–10 s | 49 |
| 10–30 s | 14 |
| **> 30 s** | **3** (longest **47.171 s**) |

`NodeApi.READ_TIMEOUT_MS` is 30 s. Those long holds are on a **single-threaded** IPC executor, so
every other companion command queues behind them. That is a direct, non-GC path to "the node
stopped answering".

### 2. Why that query is slow — a missing index

`TxPoWSqlDB.createSQL()` creates exactly one index (`:87`):

```sql
CREATE INDEX IF NOT EXISTS fastsearch ON txpow ( txpowid, parentid )
```

There is **no index on `timemilli` and none on `isrelevant`**, yet the hot statements are
(`:106-110`):

```sql
SELECT * FROM txpow ORDER BY timemilli DESC LIMIT ? OFFSET ?          -- getLatestTxPoW
SELECT * FROM txpow WHERE isrelevant=1 ORDER BY timemilli DESC ...    -- getAllRelevant
SELECT Count(*) AS tot FROM txpow WHERE isrelevant=1
SELECT Count(*) AS tot FROM txpow WHERE timemilli > ?
DELETE FROM txpow WHERE timemilli < ? AND isrelevant=0                -- the 12-hourly cleanup
```

Each is a **full table scan plus sort**, and `SELECT *` drags the `txpowdata` **BLOB of every
row** through that sort — on a table whose file is over 500 MB (see §4). Every method is
`synchronized` on the `TxPoWSqlDB` instance, so one `history` call stalls all node DB reads. The
node's own help text already concedes `history` "can be slow"; at this table size it is
catastrophic.

### 3. Why the table is that big

`SQL_DELETE_TXPOW` is `WHERE timemilli < ? AND isrelevant=0` — **rows marked relevant are never
deleted.** Combined with the coin-set pollution bug fixed in vc39 (`app/build.gradle` changelog:
a demoted covenant script stayed in `mAllTrackedAddress`, so the node "kept adopting EVERY
stranger's coin at a shared covenant … every block"), every wrongly-adopted coin's TxPoW is
retained permanently. This device was installed 2026-08-17 on vc38 — **before** the vc39 fix —
and not updated until 2026-09-09, so it ran polluted for 23 days. vc39 stopped the bleeding; it
did not clean the wound.

14 companion apps are installed on this device, including `com.eurobuddha.casino`.

### 4. A second, independent failure: the node is killed outright

`crash.txt` — two FATAL process kills in 12 hours, same signature:

```
09-10 22:36:16.839  FATAL EXCEPTION: H2-lob-cleaner
09-11 10:39:57.942  FATAL EXCEPTION: H2-lob-cleaner
org.h2.mvstore.MVStoreException: Reading from file ... failed at 525340455 (length -1) [2.1.214/1]
  at org.h2.mvstore.db.LobStorageMap.doRemoveLob(LobStorageMap.java:460)
Caused by: java.nio.channels.ClosedByInterruptException
```

H2's LOB-cleaner worker is interrupted mid-read; NIO closes the channel on interrupt; H2 throws
on a thread with no handler; Android kills the process. The current pid started at ~10:40,
**immediately after the second crash** — so the 5.4 h uptime is a restart, not a fresh boot.

The read offsets (525,340,455 and 479,872,006) prove **one `.mv.db` is over 500 MB** — most of
the 717.9 MB is a single store, and §1 says which one.

This is **upstream, not ours**: `core/Minima` has the same 30-minute `refreshSQLDB()` →
`closeAndReopen()` cycle (`MinimaDB.java:579-585`, `Main.java:92,891,900`) and upstream's own
`build.gradle:37` pins the same `com.h2database:h2:2.1.214`. Neither 2.1.214 nor 2.4.240
installs an uncaught-exception handler on that executor (checked in bytecode), so an H2 upgrade
would not help even if ART allowed one.

### 5. The GC flood destroys its own evidence

`logcat.txt` is 31,380 lines covering 2 h 05 m and is **almost entirely GC noise** — ~1,000
blocking GCs per 10 minutes, sustained. Not one line of the node's own `MinimaLogger` output
survives in the buffer. By the time anyone looks, `adb logcat` forensics are already gone. This
is why the fix needs an app-side rolling capture that does not live in logcat.

## Corrections to `HEAP_EXHAUSTION_2026-09-11.md`

- **"The fork bundles `h2-2.4.240.jar`"** — the APK does not. `app/libs/minima.jar` is the
  *nolibs* jar with zero `org/h2` entries; `app/build.gradle:85` pins **2.1.214**, downgraded at
  `cfe8856` for the ART VerifyError. 2.4.240 reaches only the desktop fat jar.
- **"64 MB per database … up to 448 MB of page cache"** — wrong by ~4×. In the jar the APK
  actually links, `MVStore.<init>` reads config key `cacheSize` with default **16** (→ 16 MB),
  plus a fixed 1 MB chunk cache; `org.h2.engine.Database` never sets `CACHE_SIZE` at all in 2.x.
  Six core stores + N MiniDapp stores ≈ **120 MB**, not 448 MB.
- **Sizing trap** — `Store.setCacheSize(kb)` does `mvStore.setCacheSize(max(1, kb/1024))`, so
  `CACHE_SIZE` is KB but rounds down to whole MB with a 1 MB floor. `CACHE_SIZE=512` and
  `CACHE_SIZE=1024` are the same thing.
- **"`largeHeap` is not the lever — it is already spent"** — device-specific, and not true of
  either attached device. Both report `dalvik.vm.heapgrowthlimit = 256m` with
  `dalvik.vm.heapsize = 512m`, so `largeHeap="true"` is what doubles the cap from 256 MB to
  512 MB. The conclusion still holds (512 m is a hard ceiling, the footprint must come down), but
  the stated reason was generalised from the zfold7 alone.

## Reproducing the analysis

```bash
cd pinned-R58M307HEEN-20260911-1626
grep -c "Waiting for a blocking GC" logcat.txt
grep "Long monitor contention" logcat.txt | grep -oE "TxPoWSqlDB\.java:[0-9]+" | sort | uniq -c | sort -rn
grep "Long monitor contention" logcat.txt | grep -oE "owner [A-Za-z0-9-]+" | sort | uniq -c | sort -rn
grep -oE "for [0-9]+\.[0-9]+s" logcat.txt | sort -t' ' -k2 -rn | head
grep "FATAL EXCEPTION" crash.txt
```
