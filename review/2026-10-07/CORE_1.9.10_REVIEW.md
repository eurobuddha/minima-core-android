# Code Review — Minima Core 1.9.10

## Summary

The three Android builds use the reviewed Core 1.1.2.31-euro4 jar. Pandamonium retains the compact Core toolbar, History tab, persistent sidebar drag ordering, MinimaMail, Minima Vestr, direct embedded transport and normal external companion permissions. The owner confirmed 1.9.9 working on the Fold. These 1.9.10 artifacts have not been installed on that device or published to stores.

## Findings

### MAJOR — Ownership filtering ran after the result limit — fixed

**File:** `core/minima-core/merge-1.1.2.31/src/org/minima/system/commands/search/coins.java`.

`coins own:true max:1` could return no coins when a tracked coin preceded an owned coin. Reproduced against the incoming jar, then fixed by using the existing search predicate before its result cap. The regression now passes. Plain relevant queries still include tracked coins; explicit watched-address balance queries still work.

### Release coordination — pending

The renamed family Core IDs require the matching companion SDK releases. Earlier namespace APKs are separate Android identities, not in-place updates. Preserve previous releases and data. The owner explicitly approved keeping Wallet 0.2.12 listed; newer concurrent Wallet changes are separate from this Core review.

## Verification

- Core Java suite: 244 tests passed.
- Android Core unit tests: 113 PandaBear, 113 BlackBear, 115 Pandamonium; all passed.
- Five isolated ownership/balance checks passed against Android H2 2.1.214; the capped ownership case failed before the fix.
- Existing CoreReviewCheck signing, search, lock and malformed-input regressions passed; TxPoWSqlDBCleanCheck passed all ten checks.
- All three release APK builds and release lint tasks passed.
- All three APKs verify with the existing family signing certificate; DEX, manifest and compiled resources contain no retired developer namespace.
- Embedded snapshots reproduce and the direct-transport architecture guard passes.
- Earlier external Mail/PandaDEX registration, enable/admin access and revocation tests covered all three Core variants. Those device results predate the ownership change; they are not described as a new 1.9.10 device run.
- Artifact hashes and IDs: `core-1.9.10-artifacts.json`.

## Verdict

Approve the corrected ownership change and locally validated 1.9.10 builds. Store selection remains with the owner. This report does not claim the entire application library has completed a fresh end-to-end audit or that funded swaps/trades were exercised.
