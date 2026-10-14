# Code Review

## Summary

One Pandamonium-only switch controls the existing pair of startup flags. The change reuses the existing settings/persistence/restart flow and bundled core behaviour; no core JAR replacement or wallet conversion is introduced. Default installs retain block mode and low RAM. Classic mode omits both flags and the core resets its startup globals before parsing the next boot.

## Findings addressed

- **MAJOR — Free-text overrides could defeat the selected profile.** Startup arguments and settings now remove old managed flags and optional values; new overrides are rejected before the node parser runs. Individual SQL flags are included.
- **MAJOR — Restore limits were fixed at build time.** First-wallet restore uses the saved startup mode, and an in-process wallet rebuild uses the running node mode. Saving a future mode does not change live globals or restore semantics before restart.
- **MINOR — Fixed block-mode labels misrepresented classic mode.** Home, footer, drawer and restore wording now follow the applicable mode. Retained drawer activities refresh on menu open.
- **MINOR — First-wallet setup could not select classic derivation.** The launcher opens the same Startup Params screen before wallet creation/restore, with restart hidden until a wallet exists.
- **MINOR — Persistence failures were reported as success.** Save checks the existing synchronous preference commit result before offering a restart.

## Key and storage behaviour

Inspected `Wallet.signData`, `TreeKey.createDefault`, `GeneralParams.resetDefaults`, `Minima.main`, `ParamConfigurer`, `MinimaDB.loadAllDB`, `TxBlockDB` and `TxPoWTreeNode` in `/Users/eurobuddha/Projects/minima/core/minima-core/merge-1.1.2.31`. Existing rows retain their stored tree shape and monotonically advancing usage count. Changing mode does not convert addresses. New/seed-restored keys derive 64×3 classic or 128×4 block trees. The existing capacity/monotonic signing guards remain in place. Legacy classic keys can be refused under block mode when the required block index exceeds capacity.

The low-RAM coin/TxBlock databases are startup-rebuilt caches; the core reloads the chain tree using the chosen storage mode. This feature does not alter the persistence format.

## Validation

Initial build: 339 unit tests passed across all three flavors. Parser regression covers repeated block/classic cycles, profile isolation and free-text overrides. Restore tests cover both key capacities. Snapshot/import and direct-transport architecture checks passed.

Early emulator attempts were blocked by window-focus failures and a slow-wallet startup prompt. Window-manager inspection and a screenshot identified an Android system-process ANR dialog holding focus (`pandamonium-classic-emulator-focus.png`). Restarted the disposable emulator with two CPU cores and 3072 MB RAM. The fixture explicitly selects its confirmation dialog, handles the normal Keep waiting prompt, keeps the screen awake/unlocked and checks a non-zero disposable counter. Both final fresh-wallet emulator sequences passed after dismissing the Android System UI ANR dialog and verifying normal focus.

Final production build: 339 passing unit tests, zero lint errors/317 warnings, valid family release signature. APK version 1.9.2-Pandamonium/code 73. No production changes were needed for these test-environment issues.

## Verdict

✅ Approve. Both fresh classic and fresh block wallet sequences passed, including real clean restarts in both directions, all three runtime flags, saved preference isolation, retained original key shapes/public keys and a non-zero counter (7000), refreshed drawer mode, direct transport/file guards and navigation through all eleven apps.

Final logs: `pandamonium-mode-classic-passed.log`, `pandamonium-mode-block-passed.log`. Real wallet signing, populated mainnet-chain migration and long-duration RAM behaviour are not part of the offline disposable fixture. No physical phones were accessed and no publication was performed.


## Artifact

`dist/pandamonium-1.9.2.apk` — 32,790,682 bytes; SHA-256 `060bc9605411c33046f84daaefc44f88f1f90944d3250c6a3cd26b019ee12dc4`. Adjacent checksum verified. Release certificate SHA-256 `eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f`.
