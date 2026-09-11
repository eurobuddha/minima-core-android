# Minima Core 1.6.20-ui-h2 (48)

The health check now actually runs on every device. On one of them it never did.

## What was wrong

1.6.18 and 1.6.19 scheduled the check on the hourly `Alarm`. On the Z Fold 7 that alarm had
**not fired once six minutes after start** — `setInexactRepeating` is subject to Doze and OEM
battery management. The feature looked fine only because the older Galaxy happened to run its
alarm promptly. That device would have gone hours with no health check at all.

Two further faults, each found only by watching a real phone:

- **The first check after a restart always failed.** `status complete:true` throws during
  early startup — first on `SeedRow.getSeed()` with the wallet not yet loaded, then on
  `TxPoWTreeNode.getTxPoW()` with no chain tip yet — and the service-start check lands squarely
  in that window.
- **That failure then cost a full interval**, because the debounce slot was spent whether the
  read succeeded or not. A freshly started node was left unmonitored for fifteen minutes.

Combined, a restarted Z Fold produced **no health line at all** — which is indistinguishable,
from the outside, from a node in perfect health. For a feature whose entire purpose is to stop
silent degradation, that is the worst possible failure.

## The fix

- **The node's own block heartbeat is the schedule.** `NEWBLOCK` asks for a check; a block
  arrives roughly every 50 seconds while the node runs. `MIN_CHECK_INTERVAL_MILLIS` (15 min)
  does the scheduling by turning almost every request away. Service start and the `Alarm`
  still ask, and are debounced like everything else — but nothing now depends on AlarmManager
  firing.
- **A failed read retries in 60 seconds**, not after the full interval.
- **A failed read says so**, with the node's own error text. Silence now means "the check ran
  and found nothing wrong", and nothing else.

## Verified on the device that exposed it

Z Fold 7, from a cold start:

```
18:55:25  Node health: could not read node status - java.lang.NullPointerException: ...
          TxPoWTreeNode.getTxPoW() on a null object reference (retrying shortly)
18:57:00  Node health: OK - store 30 MB, txpow 10 MB / 2522 rows, archive 3 MB,
          heap 93 MB of 512 MB, mempool 0
```

The first check fails while the node is still coming up, reports why, and the retry 95
seconds later succeeds. No notification, which is correct — this node was resynced earlier
today and is healthy.

- 51 JVM tests pass (50 before), including that an hour of blocks yields only scheduled
  checks, and that a failed read is retried well inside the normal interval.
- Release lint clean. APK is `org.minimarex.minimacore` `1.6.20-ui-h2` (48), signed with the
  family certificate `eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f`.
- Android instrumentation tests not run (no emulator session).

## Standing caveat

Every field reading so far — 2,522 and 20,665–22,544 txpow rows — comes from a node that was
behaving. The WATCH and DEGRADED thresholds still have not been exercised against a real
fault, and remain provisional (see `HARDENING_1.6.18.md`).

## Worth noting for upstream

`status complete:true` NPEs rather than failing cleanly when called during node startup —
once on the wallet seed row, once on a null chain tip. Harmless to a human typing it at a
terminal, but anything that polls status has to tolerate it.
