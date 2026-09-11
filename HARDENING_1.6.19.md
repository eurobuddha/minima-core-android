# Minima Core 1.6.19-ui-h2 (47)

A fix to 1.6.18's own health check, found by reading the first log line it ever produced in
the field.

## What was wrong

The hourly `Alarm` both starts the service **and** asks for a check — and starting an
already-running service delivers `onStartCommand`, which asks again. Every tick therefore
sampled twice and wrote two identical lines:

```
18:14:59.460  Node health: OK - store 720 MB, txpow 144 MB / 22544 rows, ...
18:14:59.467  Node health: OK - store 720 MB, txpow 144 MB / 22544 rows, ...
```

Harmless in itself — two reads of `status complete:true` an hour — but a log nobody can trust
is worse than no log, and duplicate entries are exactly how that trust goes.

## The fix

Checks are debounced to at most one a minute, in `NodeHealthMonitor`. Done at the monitor
rather than by deleting one of the two call sites, so it holds for any future caller and does
not depend on Android's service-start semantics staying as they are.

Two things fell out of writing the test for it, both real:

- **A backwards clock would have silenced the check entirely.** The wall clock can move
  backwards — an NTP correction, or the user changing the time — and a naive `now - last`
  then goes negative, reads as "too soon", and suppresses every check until the clock catches
  up. A jump backwards now re-baselines and runs.
- **The debounce slot must be claimed only when a sample is actually taken.** Claiming it
  first meant the check fired at service start, *before the node had finished coming up*,
  found no node, returned — and left the minute spent, suppressing the next trigger that
  could have sampled. On a device this showed as no health line at all. The claim now comes
  after the node-up and not-mid-resync guards.

The second of those was caught only because the on-device check produced **zero** lines where
one was expected. It would have shipped silently otherwise: the unit tests passed throughout.

## Validation

- 49 JVM tests pass (46 before), including the debounce, the backwards-clock case, and that
  a genuine hourly tick is not debounced away.
- On device (`R58M307HEEN`, 1.6.19): one alarm tick now produces exactly one health line
  where 1.6.18 produced two.
  ```
  18:35:55.742  MINIMA ALARM RECEIVED : Start Service
  18:35:55.764  Node health: OK - store 707 MB, txpow 131 MB / 20665 rows, archive 558 MB, heap 70 MB of 512 MB, mempool 2
  ```
  That reading also exercised the auto-resync mempool guard for real: `mempool 2` means an
  automatic resync would correctly have declined to run.
- Release lint clean; APK is `org.minimarex.minimacore` `1.6.19-ui-h2` (47), signed with the
  family certificate `eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f`.
- Android instrumentation tests not run (no emulator session).

## Note for the next release

Thresholds remain provisional — see `HARDENING_1.6.18.md`. The three field readings so far,
all from nodes that were behaving: 2,050 rows / 10.1 MB (freshly resynced), and 20,665–22,544
rows / 131–144 MB (long-running). Nothing yet observed from a genuinely polluted node with
this instrumentation in place, so the WATCH and DEGRADED lines have not been validated against
a real fault.
