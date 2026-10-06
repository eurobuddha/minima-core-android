PandaBear and BlackBear 1.7.4 ship the reviewed Minima core 1.1.2.31-rex3, Android version code 69, signed with the existing Minima-family key.

### PandaBear or BlackBear?

- **PandaBear** (`org.minimarex.minimacore`) updates the existing classic wallet app. It keeps the per-key signing counter and 64³ key trees. Choose it to continue using an existing PandaBear wallet and its addresses.
- **BlackBear** (`org.minimarex.minimablock`) is a separate installation using block-as-key-uses and 128⁴ key trees, with low-RAM SQL storage enabled. Choose it for a fresh wallet in the newer mode.
- Both use the same Minima network. **The same seed produces different addresses across these modes.** Restoring a PandaBear seed in BlackBear does not transfer its coins. Complete active swaps and close/migrate pool positions before moving funds to a new wallet.
- Run one node at a time: both use port 11001. Restore in the matching mode, stop the old wallet, and use a recovery floor above all previously used indexes.

[Full PandaBear / BlackBear explainer](https://github.com/eurobuddha/minima-core-android/blob/ui/full-redesign/PANDABEAR_BLACKBEAR.md)

### Fixes

Exact validation prevents signing-index overflow and fractional truncation. Temporary password unlocks now relock even when signing fails. Coin limits apply after sendable, age and mempool filtering; MegaMMR search/token paths release database locks. Low-RAM coin lookups are indexed. Streaming import rejects malformed headers/counts, bounds allocation and waiting, and checks that each batch was actually accepted. Restore guidance no longer assumes chain height proves an index unused. The startup port probe completes before node startup.

The companion SDK discovers and pins one responding node; it refuses commands if both apps reply. AtomiX 0.1.71 and PandaPools 0.9.64 include this SDK. Older companion APKs may still require an SDK update for BlackBear. PandaPools MiniDapp 0.6.32 fixes the remaining block-mode signing/confirmation limits while preserving recovery holds.

### Validation

Core: 244 unit tests, 18 focused regression checks, 10 SQL retention checks. Android: 103 tests per flavor, release builds and full lint. SDK: four routing regressions. All pass. This release has not had a new multi-day device soak or mainnet-scale import test.
