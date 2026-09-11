# Minima Core 1.6.17-ui-h2 (45)

One change: the node's `txpow` table gets the indexes it always needed, and the query that
reads it gets the `WHERE` clause H2 requires before it will use one.

## Why

`HEAP_EXHAUSTION_2026-09-11.md` reported companion APKs timing out against a node that was
alive and on-chain, and reached for the heap. A capture from a second device already in that
state (`diag/README.md`, `diag/pinned-R58M307HEEN-20260911-1626/`) found a second and more
direct path to the same symptom.

Every one of the **1,626** `Long monitor contention` mentions in that capture names
`TxPoWSqlDB`. By method — taken from the log's own method names, not line numbers, since these
jars carry no `LineNumberTable`:

| holding the monitor | events | | blocked waiting | events |
|---|---|---|---|---|
| `getLatestTxPoW` | **506** | | `exists` | 299 |
| `getChildBlocks` | 17 | | `getTxPoW` | 210 |
| `closeAndReopen` | 10 | | `getChildBlocks` | 26 |
| `getTxPoW` | 7 | | `getLatestTxPoW` | 7 |

Longest hold measured: **47.171 s**, against a 30 s `NodeApi.READ_TIMEOUT_MS`. `getLatestTxPoW`
has exactly one caller — the `history` command — and the thread holding the lock was
`pool-3-thread-1`, which is `MinimaReceiver.mCmdExecutor`, a `newSingleThreadExecutor`. So one
`history` call from any of the 14 installed companion apps stalls every node DB read *and* every
other companion command, on the same single thread.

The cause is a schema gap. `createSQL()` created one index, `fastsearch (txpowid, parentid)`,
while five hot statements sorted or filtered on `timemilli` and `isrelevant` — neither indexed.
`SELECT * FROM txpow ORDER BY timemilli DESC LIMIT ?` was therefore a full table scan that
dragged every row's `txpowdata` **BLOB** through a sort, and every `TxPoWSqlDB` method is
`synchronized` on the instance.

## Changes and reuse

In `core/minima-core/src/org/minima/database/txpowdb/sql/TxPoWSqlDB.java`, reaching the APK via
the surgical jar patch (`JAR_PATCH_PROCEDURE.md`):

- `CREATE INDEX IF NOT EXISTS txpowtime ON txpow ( timemilli )` — serves `getLatestTxPoW`'s
  `ORDER BY`, `getLatestTxPoWSize`'s `COUNT`, and the 12-hourly cleanup `DELETE`.
- `CREATE INDEX IF NOT EXISTS txpowrelevant ON txpow ( isrelevant, timemilli )` — serves
  `getAllRelevant` and `getRelevantSize`, and the `isrelevant=0` half of the `DELETE`.
- `SQL_SELECT_TOPTXPOW` gains `WHERE timemilli > 0`.

Both indexes are created by the existing `createSQL()`, which already ran `CREATE INDEX IF NOT
EXISTS` and is called on every open and every `checkOpen()` reopen — so **existing databases
index themselves on the next start**, with no migration and no schema version. Expect one slow
first boot while the index builds on a large store.

### The `WHERE timemilli > 0` is load bearing

It looks redundant — every row carries a real epoch time — and it must not be tidied away.
**H2 will not use an index for a bare `ORDER BY … LIMIT` with no `WHERE`.** Without the
predicate the query stays a full table scan even with `txpowtime` present. This was caught only
by testing the plan; the first cut of this patch verified perfectly through jar and dex and
would have shipped a fix that did nothing for the hot path.

## Validation design

- Query plans and work done were measured against **H2 2.1.214** — the version the APK links
  (`app/build.gradle:85`), pinned because 2.4.240 fails ART's verifier on Samsung
  (`H2_VERIFYERROR_FIX.md`). The 2.4.240 in `core/minima-core/lib/` reaches only the desktop fat
  jar and is the wrong thing to test against.
- A scratch H2 database was seeded with 60,000 rows carrying 2 KB BLOBs, `ANALYZE`d, and each
  hot statement run under `EXPLAIN` and `EXPLAIN ANALYZE`, before and after the indexes.
- Work done (pages read) was used as the metric, not elapsed time. Wall-clock on a warm SSD
  showed 85 ms vs 88 ms — no signal, and it would have pointed the wrong way.
- This measures query plans and I/O on a scratch database. It is **not** a measurement of the
  47 s lock holds on a 500 MB+ store under GC pressure on a phone; that requires the device, and
  is the next check.

## Results

| statement | before | after |
|---|---|---|
| `getLatestTxPoW` | `tableScan`, **25,535** page reads | index, **3,755** page reads |
| `getAllRelevant` | `tableScan` | index |
| `getRelevantSize` | `tableScan` | index |
| cleanup `DELETE` | fell back to a constraint index | `txpowrelevant` |

The gap widens with the table, because the scan is O(rows) and the index path is O(log n + limit).

- Jar patch verified in all three jars: entry counts unchanged (**3278 / 478 / 478**), `org/h2`
  entries unchanged (**1030 / 0 / 0**), member count unchanged (**34**), archives intact, and
  the patched class carries an **identical CRC `3c5467a8` in all three** — which it did not
  before, since the app jar was a different build vintage.
- Verified into the dex of the built release APK: `classes2.dex` carries all three `CREATE INDEX`
  statements and `SELECT * FROM txpow WHERE timemilli > 0 ORDER BY timemilli DESC LIMIT ? OFFSET ?`.
- Release APK identity `org.minimarex.minimacore`, version `1.6.17-ui-h2`, code `45`.
- Signature matches the family certificate SHA-256
  `eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f`.
- All **28** JVM tests passed; release lint passed with zero errors.
- **Android instrumentation tests were not run** for this release (no emulator session); the
  change is confined to the node jar's SQL layer, which those tests do not exercise.
- Artifacts: `dist/minima-core-ui-1.6.17.apk`, `dist/minimaapi-1.6.17.aar` and their sidecars.
- APK SHA-256: `371e39fa1db9bf4bf17e479b54f0421e43a987e998892a3f96502d5203deb10a`
- AAR SHA-256: `fb7f4c176cc56c461bc4647d1b21d3a9ba80798339ff984285423d9415707f4e`
  (unchanged from 1.6.16 — `minimaapi` did not change.)

## What this does not fix

This removes a stall, not the cause of the stall. The `txpow` table is large because
`SQL_DELETE_TXPOW` is `WHERE timemilli < ? AND isrelevant=0`, so **rows marked relevant are
never deleted** — and the coin-set pollution bug fixed in vc39 spent weeks marking strangers'
covenant coins relevant on affected nodes. Indexing makes a bloated table survivable; it does
not un-bloat it. The cleanup (`megammrsync action:resync`) is still needed on affected devices,
and is tracked separately along with the heap watchdog, the unbounded `CoinsDialog` query, the
uncapped IPC reply, and the `H2-lob-cleaner` process kills.
