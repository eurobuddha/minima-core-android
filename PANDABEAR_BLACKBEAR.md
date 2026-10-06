# PandaBear and BlackBear: which Minima node should I use?

**PandaBear is the update for existing PandaBear wallets. BlackBear is the separate app for the newer block-as-key-uses wallet mode.** Both run on the same Minima network and include our companion-app, signing-safety and reliability fixes.

| | PandaBear | BlackBear |
| --- | --- | --- |
| Android package | `org.minimarex.minimacore` | `org.minimarex.minimablock` |
| Wallet key mode | Classic, per-key signing counter | Block-as-key-uses, based on chain height and persisted counters |
| Key tree | 64 × 64 × 64: 262,144 leaves per key | 128 × 128 × 128 × 128: 268,435,456 leaves per key |
| Best fit | Keep using your existing classic wallet and addresses | Start a new wallet in the newer mode |
| Installation | Updates our existing family-signed PandaBear app | Separate installation and separate app data |
| Storage mode | Existing classic configuration | Low-RAM SQL coin/block storage enabled by default |

## The important difference: wallet addresses

The same seed produces **different addresses** in these two modes. Installing BlackBear and entering a PandaBear seed does not move the coins on the old addresses. BlackBear is not an in-place wallet migration or an automatic replacement for PandaBear.

To move from PandaBear to BlackBear:

1. Complete or unwind active AtomiX swaps and close or migrate PandaPools positions while the original wallet can still sign. A live contract keeps the public keys it was created with.
2. Create the destination wallet in BlackBear and record its receive address and recovery information.
3. Stop BlackBear, run PandaBear, and send funds to the recorded BlackBear address. Confirm receipt when running BlackBear.
4. Re-pair companion apps with the destination node. AtomiX needs a valid identity in that wallet; counterparties need the new maker key when it changes.

Run **one node at a time**. Both Android apps use port 11001. Installing both is supported; running both together is not the migration procedure.

## Signing counters and recovery

In PandaBear, a key's use count advances when that key signs. In BlackBear, the selected index is at least the chain height and also advances past persisted use counters. A large block-mode number is normal; it is not the number of swaps you have made.

Neither mode permits reuse of a one-time signing leaf. Restore with the matching wallet mode, stop the old wallet first, and set a recovery floor above every previously used index. Chain height alone cannot prove that a signing index is unused. Neither mode makes two simultaneously signing copies of the same wallet safe.

## Companion apps

AtomiX 0.1.71 and PandaPools Android 0.9.64 include the updated SDK. It discovers which node responds, then sends commands only to that node. If both respond, it refuses commands and asks you to stop one. Older companion APKs may target PandaBear's package explicitly and need an SDK update to connect to BlackBear.

PandaPools MiniDapp 0.6.32 also fixes block-mode key-capacity checks while retaining restored-key quarantine and counter-regression checks.

## This release

PandaBear **1.7.4-PandaBear** and BlackBear **1.7.4-BlackBear** use Android version code **69** and core **1.1.2.31-rex3**. Both are signed with the existing Minima-family key. Their source and regression findings are documented in [the review report](REVIEW_BLACKBEAR_2026-10-06.md).

Automated tests, release builds and lint pass. These releases have not had a new multi-day device soak or a mainnet-scale import test. Keep your recovery information before upgrading. The earlier 1.7.3 local builds were review artifacts; these distinctly named 1.7.4 builds are the store release.
