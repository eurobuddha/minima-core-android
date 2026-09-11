# Heap exhaustion — the node stops answering IPC and looks fine while doing it

**Found live 2026-09-11 on the zfold7 (`RFCY71KW3LX`), minimacore `1.6.16-ui-h2` (versionCode 44).**
No code change was made. This is a report for whoever picks up the fix.

> **Updated later the same day.** A second device (`R58M307HEEN`, 1.6.15-ui-h2, vc43) was found
> already in this state and captured before anything was installed — see `diag/README.md` and
> `diag/pinned-R58M307HEEN-20260911-1626/`. That capture found the mechanism, and corrected three
> things in this document. Read the **What the capture added** and **Corrections** sections below
> before acting on the H2 cache lead, which turns out to be a fifth of the problem rather than
> the cause.

## What the user sees

Every companion APK stops working at once — a casino bet that will not resolve, an NFT
wallet stuck on load — and it reads exactly like the node crashed or lost its connection.
It did neither.

The node process is alive. `MinimaService` is still a healthy foreground service with
`crashCount=0`. It is connected to peers and following the chain. Nothing in the UI or the
notification says anything is wrong. **Do not go looking for a dropped IPC connection or a
reconnect bug — that is the wrong trail, and it costs an hour.**

## What is actually happening

The node is starving on heap. Every allocation blocks behind a GC that frees a few MB and
immediately refills, so all work slows to a crawl. Broadcast dispatch back to the companion
apps (`Binder transaction to android.app.IApplicationThread code 19`) starts taking
1.4–2.4 seconds each. The companion SDK gives up long before the node gets round to
answering — `NodeApi.READ_TIMEOUT_MS` is 30s in the casino and the scaffold apps — so from
the app side it looks exactly like a node that is not there.

## Diagnosis — one command

```bash
d=<serial>
pid=$(adb -s $d shell pgrep -f org.minimarex.minimacore | tr -d '\r' | head -1)
adb -s $d logcat -d --pid=$pid | grep -c "Waiting for a blocking GC"
```

A healthy node returns 0 to 3 (startup only). The sick one returned **3,844 in twenty
minutes**. Corroborating lines in the same log:

```
Clamp target GC heap from 546MB to 512MB
Background concurrent mark compact GC freed 7564KB ... 2% free, 498MB/512MB
Long monitor contention with owner MAIN ...
  org.h2.mvstore.cache.CacheLongKeyLIRS$Segment.put ... txpow.mv.db ... for 2.593s
Forcing collection of SoftReferences for 56B allocation
```

`2% free, 498MB/512MB` and `Forcing collection of SoftReferences for 56B` are the tell: the
heap is pinned at the ceiling and the collector is scraping for bytes.

## Immediate remedy

Force-stop and relaunch. Heap drops from the pinned 512MB to ~170–350MB and IPC answers
again within about two minutes.

```bash
adb -s $d shell am force-stop org.minimarex.minimacore
adb -s $d shell monkey -p org.minimarex.minimacore -c android.intent.category.LAUNCHER 1
```

**This is safe with a bet or a transaction in flight**, and the companion apps do not need
restarting. Bet state is derived from on-chain coins and the commit preimage lives in the
app's own SecretStore, never in the node. On the live incident the casino's `AutoProcessor`
re-posted by itself and the bet resolved two minutes after the node came back, with no
intervention in the app at all. MVStore is crash-safe on reopen.

## Why `largeHeap` is not the lever

`app/src/main/AndroidManifest.xml:51` already sets `android:largeHeap="true"`. On this
device it buys nothing:

```
dalvik.vm.heapgrowthlimit = 512m      # normal cap
dalvik.vm.heapsize        = 512m      # largeHeap cap — identical
```

Samsung set both ceilings to the same value, so `largeHeap` is a no-op **there** and 512MB is
a hard wall.

**Correction — this does not generalise.** Both other Samsungs measured on 2026-09-11 report
`heapgrowthlimit = 256m` with `heapsize = 512m`, so on them `largeHeap="true"` is exactly what
doubles the cap from 256MB to 512MB. Never assume it is spent; read the two properties on the
device in front of you. The conclusion is unchanged either way — `heapsize` is a hard ceiling,
so **the footprint has to come down; the ceiling cannot go up.**

Device RAM is irrelevant and will mislead you — the phone had 15.4GB total and 4.7GB free
while the node was suffocating.

## The driver is on-disk database size

| device | minimacore | node data dir | state |
|---|---|---|---|
| `RFCY71KW3LX` zfold7 | 1.6.16-ui-h2 | 1240 MB | thrashed |
| `R58M307HEEN` | 1.6.15-ui-h2 | 753 MB | same trajectory — watch it |
| `R3CW30FN1FM` S23 Ultra | 1.2.6 | 168 MB | fine |

Read it with `adb -s $d shell dumpsys diskstats` and match the package against `App Data
Sizes`. The 753MB device is the next one to fall over.

**It already had.** Measured hours later: 717.9 MB, heap pinned at 501,501 / 524,288 KB,
12,247 blocking GCs in two hours. Note also that the "fine" S23 is **stock** minima-core on a
different lineage, and was installed 2026-06-27 against the sick device's 2026-08-17 — the
*older* node holds a quarter of the data, so age does not explain the gap.

## What the capture added — the lock, and why one query is slow

The zfold7 report reached for the heap because that is what the log showed. The
`R58M307HEEN` capture shows a second, partly independent path to the same symptom, and it is
more actionable.

**Every one of the 1,626 `Long monitor contention` mentions names `TxPoWSqlDB`.** Not the
archive, not the wallet, not the coin DB. 506 of 575 events sit at `TxPoWSqlDB.java:409`
(`getLatestTxPoW`), and the thread holding the monitor is `pool-3-thread-1` —
`MinimaReceiver.mCmdExecutor`, which is `Executors.newSingleThreadExecutor()`. So the **IPC
command thread itself** takes the lock, and every other companion command queues behind it.
Longest hold measured: **47.171 s**, against a 30 s `NodeApi.READ_TIMEOUT_MS`.

`getLatestTxPoW` has exactly one caller: the `history` command (`history.java:144`).

The reason it is slow is a schema gap. `TxPoWSqlDB.createSQL()` creates one index (`:87`):

```sql
CREATE INDEX IF NOT EXISTS fastsearch ON txpow ( txpowid, parentid )
```

There is **no index on `timemilli` and none on `isrelevant`** — yet five hot statements
(`:106-110`) sort or filter on exactly those:

```sql
SELECT * FROM txpow ORDER BY timemilli DESC LIMIT ? OFFSET ?          -- getLatestTxPoW
SELECT * FROM txpow WHERE isrelevant=1 ORDER BY timemilli DESC ...    -- getAllRelevant
SELECT Count(*) AS tot FROM txpow WHERE isrelevant=1
SELECT Count(*) AS tot FROM txpow WHERE timemilli > ?
DELETE FROM txpow WHERE timemilli < ? AND isrelevant=0                -- the 12-hourly clean
```

Each is a full table scan plus sort, and `SELECT *` drags every row's `txpowdata` **BLOB**
through that sort. Every method is `synchronized` on the instance. The node's own help text
already concedes `history` "can be slow"; at this table size it stalls the whole node.

**Why the table is that big:** the delete is `AND isrelevant=0`, so **rows marked relevant are
never removed**. Pair that with the coin-set pollution bug fixed in vc39 — a demoted covenant
script stayed in `mAllTrackedAddress`, so the node "kept adopting EVERY stranger's coin at a
shared covenant … every block" — and every wrongly-adopted coin's TxPoW is retained forever.
`R58M307HEEN` was installed on vc38, *before* that fix, and ran polluted for 23 days. vc39
stopped the bleeding; it never cleaned the wound.

## Second failure: the node is killed outright

Not visible on the zfold7, obvious on `R58M307HEEN` — **two FATAL process kills in 12 hours**:

```
09-10 22:36:16.839  FATAL EXCEPTION: H2-lob-cleaner
09-11 10:39:57.942  FATAL EXCEPTION: H2-lob-cleaner
org.h2.mvstore.MVStoreException: Reading from file ... failed at 525340455 (length -1) [2.1.214/1]
  at org.h2.mvstore.db.LobStorageMap.doRemoveLob(LobStorageMap.java:460)
Caused by: java.nio.channels.ClosedByInterruptException
```

H2's LOB-cleaner worker is interrupted mid-read; NIO closes the channel on interrupt; H2
throws on a thread with no handler; Android kills the process. The read offsets (525,340,455
and 479,872,006) independently prove **one `.mv.db` is over 500 MB**.

This is **upstream, not ours**: `core/Minima` has the same 30-minute `refreshSQLDB()` →
`closeAndReopen()` cycle (`MinimaDB.java:579-585`, `Main.java:92,891,900`) and upstream's own
`build.gradle:37` pins the same H2. Neither 2.1.214 nor 2.4.240 installs an uncaught-exception
handler on that executor, so an H2 upgrade would not help even if ART allowed one.

## Corrections — read before acting on the H2 cache lead

The lead below is still worth doing, but it is **headroom, not the cause**, and two of the
numbers originally given here were wrong.

**1. The APK does not run H2 2.4.240.** `app/libs/minima.jar` is the *nolibs* jar and bundles
zero `org/h2` classes; `app/build.gradle:85` pins `com.h2database:h2:2.1.214`, downgraded
deliberately because 2.4.240's `org.h2.security.SHA256.getPBKDF2` fails ART's verifier on
Samsung and kills the node at boot (`H2_VERIFYERROR_FIX.md`). The 2.4.240 jar in
`core/minima-core/lib/` reaches only the desktop fat jar. Test against **2.1.214**.

**2. The default is 16 MB per database, not 64 MB.** Disassembled from the jar the APK
actually links: `org.h2.mvstore.MVStore.<init>` reads config key `cacheSize` with default
**16** (× 1024 × 1024 → 16 MB page cache), plus a second fixed **1 MB** chunk cache.
`org.h2.engine.Database` never sets `CACHE_SIZE` at all in H2 2.x. So six core stores plus N
MiniDapp stores is roughly **120 MB**, not 448 MB — about a fifth of a 501 MB problem.

**3. `CACHE_SIZE` is KB but lands on whole MB.** `Store.setCacheSize(kb)` does
`mvStore.setCacheSize(max(1, kb/1024))`, so values round down to whole MB with a 1 MB floor:
`CACHE_SIZE=512` and `CACHE_SIZE=1024` are the same thing. Size per database accordingly.

## Lead for the durable fix — bound the H2 cache

Cheap, worth doing, but see the corrections above: this buys perhaps 60–90 MB of headroom and
does not remove the mismatch.

Every database goes through `core/minima-core/src/org/minima/utils/SqlDB.java`, and none of
its four JDBC URLs set `CACHE_SIZE`:

```java
String h2db = "jdbc:h2:"+path+";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE";
```

Lines **66** (plain) and **104** (encrypted) are the open paths; **162** and **170** are the
reopen pair in `checkOpen()`, which rebuilds the URL from scratch. **Miss the reopen pair and
the cap silently evaporates** the first time a connection is recycled — which happens every 30
minutes via `MinimaDB.refreshSQLDB()`.

Seven classes extend `SqlDB` — `TxPoWSqlDB`, `TxPoWOnChainDB`, `CoinDB`, `ArchiveManager`,
`Wallet`, `MDSDB`, `MiniDAPPDB` — and `MiniDAPPDB` is **one store per MiniDapp**, held in
`MDSManager.mSqlDB` and never evicted, so each opened MiniDapp permanently adds another.

Suggested experiment: append `;CACHE_SIZE=<kb>` sized per database rather than uniformly, with
the txpow store given the largest share. Confirm the default first with
`SELECT * FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME='CACHE_SIZE'` rather than
trusting the doc, then measure the blocking-GC count over a day.

Worth pairing with a look at whether the txpow/archive stores should be pruned on mobile at
all — a 1.2GB database on a 512MB heap is the underlying mismatch, and capping the cache
only buys headroom rather than removing it.

## Priority order, after the capture

1. **Clean the affected devices.** `megammrsync action:resync host:<ip:port>` (not
   `archive action:resync`) rebuilds the coin set from the wallet's real addresses and
   discards the adopted covenant coins. vc39 cannot do this retroactively.
2. **Index `timemilli` and `isrelevant`** on the `txpow` table, and stop `SELECT *` hauling
   BLOBs through the sort. Targets the measured hot path; `CREATE INDEX IF NOT EXISTS` in
   `createSQL()` means existing databases pick it up on next start, no migration.
3. **Make the state visible and recoverable** — nothing in the UI or the notification says
   anything is wrong, which is this incident's most expensive property.
4. **Bound the amplifiers** — `CoinsDialog.java:142` issues `coins relevant:true` with no
   `limit:` and no paging; `MinimaReceiver.java:181` caps the inbound command and the inline
   reply but not the command result itself.
5. **Then** the H2 cache cap and retention work above.

## Shipping a fix from here

The node source is a jar, not sources in this repo — `app/libs/minima.jar`. A `SqlDB.java`
change reaches the APK through the surgical jar patch, never a Gradle rebuild of the fork
(its Gradle 6.7.1 will not run on the installed JDKs). Three jars are patched, each compiled
against itself: `core/minima-core/jar/minima.jar`, `core/minima-core/jar/minima-nolibs.jar`,
and `apks/base/app/libs/minima.jar`. Use `zip` rather than `jar uf` on the fat jar.

**The full procedure and its gotchas are in `JAR_PATCH_PROCEDURE.md`** — follow it rather than
improvising. (This paragraph previously claimed such a document already existed. It did not;
the only record was prose in four commit messages. It does now.)

Per this repo's versioning guardrail, any such change ships with a `versionCode` +
`versionName` bump in `app/build.gradle` (currently 44 / `1.6.16-ui-h2`). Note that the
pre-commit hook does **not** enforce this for a jar-only change — it keys on `^app/src/`, so a
patched `app/libs/minima.jar` commits cleanly with no bump. The discipline is yours.
