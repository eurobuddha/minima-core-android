# minimaCore — every change from the official build to 1.6.10-ui-h2

**For:** Spartacus Rex / Minima maintainers
**From:** eurobuddha
**Date:** 2026-08-17
**Scope:** the complete delta between the official upstream code and our current shipping
build, **Minima Core — New UI 1.6.10-ui-h2** (`org.minimarex.minimacore`, versionCode 38),
written so each piece can be reviewed and cherry-picked into the official release
independently.

Two repositories are involved, forked at these points:

| Repo | Upstream | Our fork | Fork point (last upstream commit) |
|---|---|---|---|
| Android app | https://github.com/spartacusrex-minima/minima-core-android | https://github.com/eurobuddha/minima-core-android — branch `ui/full-redesign` | `054e29864aea09a10a86fa09e9bf607dbc319afd` (1.2.5) |
| Node core (minima.jar) | Minima core | https://github.com/eurobuddha/minima-core — branches `security/signdata-race`, `feat/megammr-import-robustness`, `main` | `7b5994a` (1.1.2.4 line; we are a few commits behind your `main` — docker/`rescue.java` work — and none of our changes touch those files) |

Three earlier stand-alone write-ups exist and are summarised (not replaced) here:
`UPSTREAM_IPC_CHANGES.md` and `UPSTREAM_UI_CHANGES.md` in the app fork, and
`UPSTREAM_CHANGES.md` in the core fork. This document is the union, brought current
through 1.6.10-ui-h2, in recommended adoption order.

---

## Priority guide — what we'd ship first if we were you

1. **§1.1 `Wallet.signData` race** — real Winternitz one-time-signature reuse, confirmed
   in the wild. Node core, 2 files.
2. **§2.4 IPC reply cap split (`MAX_RESPONSE_LEN 100000`)** — without it, a broadcast
   reply of ~128K–256K chars **kills the receiving companion app uncatchably**
   (`TransactionTooLargeException` in the broadcast queue). Observed live killing a
   companion on every open, twice (2026-08-09 and again 2026-08-17 on a 1.2.5 node).
   App receiver, ~19 lines.
3. **§3.1 `allowBackup` off** — Android Auto Backup was uploading the node's data dir
   (including the cleartext BIP39 seed in `main_prefs`) to the user's cloud. Manifest +
   two rules files.
4. **§3.3 H2 pin `2.4.240 → 2.1.214`** — fixes nodes that never start (ART `VerifyError`).
   One line.
5. **§1.2 `sendsign` leaf burn** — N-input send consumed N key leaves instead of 1.
6. Everything else (MegaMMR robustness, IPC additions, UI/UX) at your leisure.

---

# Part 1 — Node core (minima.jar) changes
*Fork: https://github.com/eurobuddha/minima-core . Six files total; no consensus,
transaction-validation, or network code touched. Full detail: `UPSTREAM_CHANGES.md`
(commit `4c2576f8e81f0ca523a45b0724bca3845661df03`) in that repo.*

## 1.1 SECURITY — `Wallet.signData` is an unsynchronized read-modify-write
**Commit `c400cf933d39de6b8f5a81a107de8362e441ef84` · branch `security/signdata-race` (2 files, clean off your main, ready to PR)**

`Wallet.signData` (Wallet.java:793) reads the key-use counter, signs at that leaf, and
writes `uses+1` — and it was the **only non-`synchronized` mutator on the class**. Two
concurrent signers both read `uses = N`, both sign leaf N over different data, both write
N+1: a reused Winternitz one-time signature, which leaks that leaf's private key.
`TreeKey.verify()` has no index-monotonicity notion, so the reuse verifies and the network
accepts it. The race window is milliseconds wide (thousands of SHA3 hashes inside
`tk.sign()` between read and write).

It is reachable in ordinary use: HTTPServer spawns a thread per RPC request, MDS a handler
per MiniDapp socket, `SendPollManager` is its own thread, and on Android every companion
app shares one node with commands running on their own threads. Collisions concentrate
because `getDefaultAddress()` (Wallet.java:465-477) picks uniformly at random from only
64 keys, never consulting `uses`.

**Confirmed in the wild:** 7 of 64 default keys on a live phone node re-used,
witness-exact (one leaf, two distinct on-chain transactions). The alternative explanation
(resync with `keyuses` too low) is ruled out by the data: the busiest key had 58 on-chain
signatures while every bulk counter rewrite in the node sets 100+. A standalone repro
harness (`disclosure/repro/RaceProof.java`, 8 concurrent signers, no node needed) shows
`distinct leaves=1, REUSED LEAVES=1` unsynchronized vs `distinct leaves=8, REUSED
LEAVES=0` synchronized.

**Fix:** make `signData` and its write half `updateUses` `synchronized` (monitors are
reentrant, so the synchronized helpers it calls are unaffected). This also fixes the
shared `PreparedStatement`s `SQL_GET_KEY` / `SQL_UPDATE_KEY_USES` (Wallet.java:152-154),
which are not thread-safe and had bound parameters clobbered by the same race.

Only the node can fix this: an app can serialise its own signing, but nothing an app does
prevents a collision with a *different* app signing on the same key at the same moment.

Related observations (not changed, your call — detailed in `UPSTREAM_CHANGES.md`):
`TreeKey.sign()` wraps to leaf 0 and keeps signing at exhaustion instead of refusing;
`getaddress` ignores `uses` (least-used selection would spread load); `keys action:list`
publishes a misleading cross-key `maxuses` aggregate; there is no per-key `uses` setter
for safe recovery tooling.

## 1.2 `sendsign` burns a key leaf per input coin
**Same commit `c400cf933d39de6b8f5a81a107de8362e441ef84`**

`reqsigs.add(pubkey)` was never called in `sendsign.java`, so the dedupe guards at :114
and :142 never fired: an N-input transaction from one address consumed **N leaves instead
of 1** and appended N duplicate signatures to the witness. (`send.java:464-465` has the
correct pattern.) Subtlety when fixing: the burn transaction needs its **own** dedupe
list — it has a different transaction ID, so a key that signed the main transaction must
still sign the burn.

## 1.3 MegaMMR import on constrained heaps
**Commits `1770d8aec7d6b1b088a9f81005388d3261a727d5`, `a9ef09dbd1fb70396a3e4978e45fd2b8e90a5cea`, `49feed4607f8b5d846fed704a0d8741317339905`, `33c438d7b7b4fc3827890f171e1557d882ba48ad` · branch `feat/megammr-import-robustness`**

A 343 MB mainnet `mega.mmr` needs ~1.3 GB decoded and killed the process on a 512 MB
phone heap with no usable error. Four causes, fixed in sequence:

- **`1770d8a…` — survive OOM, fail loudly on truncation, halve peak RAM.** OOM is an
  `Error` — the `catch(Exception)` at the import site let it kill the node; now
  `catch(Throwable)` + a pre-flight heap check with real numbers.
  `MiniFile.loadObjectSlowStrict` propagates `IOException` so a truncated file fails
  loudly instead of importing a silent partial graph. `MegaMMR.pruneUnspendable` prunes
  in place instead of copying the whole unspent-coins table (which doubled the largest
  structure at the worst moment); `readDataStream` pre-sizes and logs progress.
- **`a9ef09d…` — stream the IBD in 256-block batches.** Peak heap becomes MegaMMR +
  cascade + one batch, not the whole decoded `TxBlock` list. Honours: cascade only in
  batch #1, `restore=true` on every call, per-batch `isIBDProcessFinished` poll, and
  `resetFirstIBDTimer()` to defuse the 5-minute/3-hour tip gate that otherwise silently
  drops later batches.
- **`49feed4…` — heap watermark + `-megaprune` at read time.** Heap exhaustion is
  process-global: field data showed an unrelated timer thread OOM-ing first on a 24-byte
  allocation, so the import's own catch never saw it. The coin-read loop and each IBD
  batch now abort cleanly below `max(32MB, 5% of heap)` free. With `-megaprune`,
  prunable coins are dropped **before** entering the table (`isPrunable` is a pure
  per-coin test) instead of loading all coins and pruning after.
- **`33c438d…` — the MMR tree is the real ceiling.** Measured: 1,513,769 tree entries
  ≈ 1.0 GB in RAM, loaded *before* the coin table. The same watermark applied every 64k
  entries in `MMR.java`; verified at `-Xmx512m` against the mainnet file — aborts at
  1.18M/1.51M entries with the **process alive**. Practical conclusion: mainnet MegaMMR
  needs ~1.2 GB heap; on a 512 MB phone the path is `-megammr` **plus** `-megaprune`.

Shipped in the app as patched jars from 1.5.1-ui-h2 onward; the signData fix rides the
bundled jar from 1.6.7-ui-h2 (`ec862a7403f314f33d407aeae2a98dd529086341` in the core
fork; app commit `6855b5148793de6b7f47dfa1b9198a36989b61e3`).

---

# Part 2 — Android app: broadcast-IPC layer
*All self-contained in `receiver/MinimaReceiver.java`, `service/MinimaService.java` (one
IntentFilter line), `AndroidManifest.xml` (one FileProvider), `res/xml/ipc_response_paths.xml`,
and the `minimaapi` client module. Full detail: `UPSTREAM_IPC_CHANGES.md` in the app fork.*

## 2.1 Run companion commands off the broadcast (main) thread
**Commits `f576d4551cf4cd6dd003b143e5d06676adca7228`, merge `930697faf61db6924a7ec32bf0adfeefd644b08e` · was PR #1**

`onReceive` ran commands synchronously on the main thread: anything opening a socket
inline (`megammrsync`, `archive resync`) threw `NetworkOnMainThreadException`, swallowed
into a misleading "Could not connect to Archive host!". Fix: a single-thread
`ExecutorService`; ordering still strictly serialised; a command that throws now returns
`status:false` instead of failing silently.

## 2.2 Large results as a `content://` file hand-off (unbounded response size)
**Commit `6bcefae798c1f92a6973dd783fe859d910ca71da` (1.3.0-ui-h2)**

The response travels as one Intent String extra, under two ceilings: the 256,000-char
`MAX_MESSAGE_LEN` stub and, beneath it, the ~1 MB Binder transaction limit (an over-limit
broadcast throws `TransactionTooLargeException` **in the receiving process, uncatchably**).
Negotiated per command, three-way compatible:

- New client sets boolean extra `…CMD_FILERESP=true`. Oversized results are written to
  `cacheDir/ipcresponses/<responseid>.json`, exposed via a non-exported FileProvider
  (authority `org.minimarex.minimacore.ipcresponses`), read-granted to exactly the
  calling package, and the RESPONSE intent carries `…RESPONSE_URI` + `…RESPONSE_LEN`
  instead of `…RESPONSE_RESULT`. A file descriptor crosses Binder, not the data.
- Old client → new node: no flag → the existing "Result too long!" stub, byte-identical.
- New client → old node: unknown extra ignored → the stub. Nothing breaks either way.
- Small results keep the inline path in all pairings — zero overhead.

Hygiene: response files pruned after 5 minutes and wiped at receiver start; grants
revoked on prune; a failed write falls back to the stub, never silence. New constants in
`MinimaAPIMessages`: `MINIMA_API_CMD_FILERESP`, `MINIMA_API_RESPONSE_URI`,
`MINIMA_API_RESPONSE_LEN`.

## 2.3 Admin-gated FILE bridge (node base-folder access for companions)
**Commits `d68135555f5a30613390dbcb863d32fae90ef3ac` (1.3.1-ui-h2), `e0b418f25875d30a325aa30ac439fefdf60c20fd` (1.4.1-ui-h2)**

`backup`, `megammr action:export`, `txnexport` write into the node's private
`getFilesDir()`, unreachable without adb. New broadcast action
`org.minimarex.minimacore.FILE` with `list` / `stat` / `get` / `put` / `mkdir` / `move` /
`delete`. Security model: **admin-gated** (same per-app flag as `Userid 0x00` commands —
an admin app can already run `backup` and read everything, so this is reach-parity, not
new privilege); **path containment** (canonical resolution, no traversal out of the base
folder); **write-protected `databases/`** (a file manager must never corrupt the live
wallet/chain DBs). `stat` resolves paths exactly as `MiniFile.createBaseFile` does and
reports canonical ground truth. Old nodes simply don't have `.FILE` in their
IntentFilter and ignore it cleanly.

## 2.4 Split the reply size cap — replies hand off at 100K chars
**Commit `b326c7ae3f6afe935278d850b7f6d294de84521e` (1.6.9-ui-h2). The companion-app-killer fix — please take this even if you take nothing else in Part 2.**

`MAX_MESSAGE_LEN` counts **UTF-16 chars** but `Parcel.writeString` serialises ~2 bytes
per char: a reply just under the 256,000-char cap builds a ~512KB parcel, and the
broadcast queue kills the **receiving** app at roughly the 256KB-parcel line —
`TransactionTooLargeException` before any app callback, nothing catchable on either side.
Observed live: parcel 262,240 bytes for a ~131K-char `coins` reply, killing a companion
app on every launch (`am_kill … "Can't deliver broadcast"` in the event log — that log
line is the diagnostic signature). Any wallet whose `coins` output grows through the
128K–256K-char band hits this deterministically.

Fix: inbound commands keep `MAX_MESSAGE_LEN 256000` (signing payloads must not regress);
both reply sites now use a separate `MAX_RESPONSE_LEN 100000` (~200KB parcel, safely
under the kill line). Bigger replies ride the §2.2 file hand-off for new clients or the
honest stub for legacy ones. 19 lines in `MinimaReceiver.java`.

---

# Part 3 — Android app: security fixes

## 3.1 `allowBackup` off — the node's data dir was going to the cloud
**Commit `60559d174b4739d6340d49c20cfc7413a966218a`**

The app's `getFilesDir()` **is** the Minima node's data directory
(MinimaService.java:250-260), and `main_prefs` holds the BIP39 seed phrase in cleartext
(NewWalletRestoreActivity.java:96). The manifest carried the scaffold default —
`allowBackup="true"` with **no** `fullBackupContent`/`dataExtractionRules` attributes, so
the rules files that existed were never referenced. Android Auto Backup was therefore
uploading a spendable wallet and its private keys to the user's cloud, and a restore
could reconstitute a signing node on any phone — which is also key-reuse territory
(§1.1). Fix: `allowBackup="false"` plus explicit excludes for
sharedpref/database/file/external/root in both `backup_rules.xml` (legacy) and
`data_extraction_rules.xml`.

## 3.2 Runtime "Allow Screenshots" toggle
**Commit `cfe88567534a200c240ccb23bf2485d7aedd6117` (part of the redesign)**

`FLAG_SECURE` stays the default (screenshots blocked), but is now a persisted user
choice (`ALLOW_SCREENSHOTS` in `main_prefs`, also bypassed on debuggable builds), applied
per-activity from `MinimaApplication`. Default release behaviour unchanged until the
user opts in.

## 3.3 H2 `VerifyError` — node never starts on some devices
**One line in `app/build.gradle` · was PR #2 · full root-cause in `H2_VERIFYERROR_FIX.md`**

`com.h2database:h2:2.4.240` ships bytecode the Android ART verifier rejects on affected
devices/ABIs — the node sits in a "Starting…" loop forever with a `java.lang.VerifyError`
in logcat. Pin to the last Android-clean release: `com.h2database:h2:2.1.214`.

## 3.4 Latent `-clean` boot hazard in the extra-params path
**Fixed while building the Startup Params screen (§4.4), branch `fix/extra-params-boot-leak`**

A stored `-clean` in `minima_extra_params` was stripped from the *saved* string, but the
*original* string was tokenized into the boot args — so the wipe ran on the very boot it
was set. Small, nasty, worth lifting out on its own.

---

# Part 4 — Android app: UI / UX / node-facing features
*Full detail: `UPSTREAM_UI_CHANGES.md`. The design work is opinionated and we don't expect
upstream wants it wholesale; the individual pieces below lift out cleanly. No `org.minima.*`,
`MinimaService`, DB, consensus, or signing code is touched by any of it.*

## 4.1 Restyle + navigation
- `b37fe5bbbbf51e49bf8edc386a896a7eab0d3c1d` — brand-native dark-first restyle,
  **resources only** (Material3 token system: Manrope font, `minima_orange #FF4A2B`
  palette in `values/` + `values-night/`, type scale, shape scale). All `@id`s and view
  types preserved so Java bindings are untouched. Fixes a real `values-night` bug:
  `on_primary` was near-black `#12100F`, making labels on orange buttons unreadable in
  dark mode — now `#FFFFFF`.
- `cfe88567534a200c240ccb23bf2485d7aedd6117` — structural redesign: tabs
  `Home / Wallet / Terminal / Apps` (Apps stays index 3 and the dashboard index 0, so
  the `MainAdapter.getAppsView()` cast and `MinimaNewBlock()` refresh wiring are
  untouched); a Home dashboard (block height, node + APK versions, connection state);
  Send/Receive become thin host activities around the **unmodified** `SendView` /
  `ReceiveView`.

## 4.2 Wallet: token icons + web-validation
**Also `cfe88567534a200c240ccb23bf2485d7aedd6117` — new package `main/views/balance/tokens/`**

Token rows get the canonical wallet's icon + verification treatment. Remote icon URLs
are treated as hostile: byte-bounded LRU-cached async loader, downsampling, SVG via
AndroidSVG (`com.caverock:androidsvg-aar:1.4`), and **loopback/LAN hosts blocked** so
token metadata can't point the app at the local node RPC. A token is validated when the
document at its `token.webvalidate` URL contains the tokenid (domain-ownership proof);
deterministic identicon fallback; defensive parsing of the token field in all its shapes.

## 4.3 Wallet: truthful numbers, full identifiers, working send
- `1083eb9c1e083af55ad75b4af1607d5d5bff3b95` + `f5d4db48f8f3f05e39af1b293b572f62dd343588`
  (1.6.3-ui-h2) — balance breakdown (confirmed / locked / unconfirmed / coin count /
  staleness; locked = confirmed − sendable clamped at zero) and a per-token coins modal
  including contract-locked coins, with a 13-field coin detail view where every field is
  copyable and nothing truncated (ids must round-trip into `coins coinid:…` byte for byte).
- `be0764371d59bf33cb114092ebf2c2404271db43` (1.6.4-ui-h2) — every token row shows
  **sendable** (was `confirmed`, overstating every balance that had contract-locked
  coins); per-row locked/unconfirmed/coin-count; nothing truncated anywhere (coin ids,
  token ids, amounts, peer strings all full; `Format.shortHash` deleted outright);
  long-press copies ids; Receive gains an explicit Copy button.
- `94a886780aeb7445d49adaf98216c98c23071756` (1.6.5-ui-h2) — Send: QR scanner (zxing was
  already a dependency; `CAMERA` declared with `required="false"` so the app stays
  installable without one; `Format.cleanAddress()` handles `minima:` URIs), a **Max**
  button that fills the token's *sendable* (not confirmed, which would build a failing
  send), and no more false "Funds Sent!" on `status:false` replies.
- `f226bb7a3f8ed7de371b059fc856bd32999bf4e3` (1.6.6-ui-h2) — truthful chain feedback:
  `send` reports failures under `"message"` not `"error"` (send.java:369,378) — the app
  now reads both, so the node's precise reason ("Insufficient funds.. you only have X
  require:Y") reaches the user; "Funds Sent!" became "Transaction posted — mining…"
  (a send reply means built-and-posted, not on-chain); and the NEWBALANCE service event —
  the honest confirmation point — now reaches the UI (`MinimaServiceListener` gains a
  default no-op `MinimaNewBalance()`; MainActivity refreshes the wallet and toasts
  "Balance updated on chain"). So the user sees posted → mined instead of one premature lie.

## 4.4 Startup Params screen
**Commit `8506e8f1fbcc13d4edda965aec5ae1b56cfa980b` (1.4.0-ui-h2)**

Server mode (`-server` vs `-isclient`, exactly one passed — both write
`IS_ACCEPTING_IN_LINKS`, and passing both was iteration-order dependent), MegaMMR and
RPC toggles, plus a free-text extra-args field wired to the pre-existing
`minima_extra_params` pref the service always consumed but no UI ever set. Wipe/seed
flags (`-clean -genesis -solo -seed -anyseed -dbpassword`) and app-managed flags are
blocked in the UI; extra args are validated with `ParamConfigurer.checkParams` at save
time. Save & Restart does a clean quit-and-relaunch.

## 4.5 Logs tab
**Commit `d2ac345a70c66248df19540741ff0b3519cc4d7a` (1.5.0-ui-h2)**

Live on-device tail of the node's own log — zero jar changes: `MinimaLogger` already
posts every line as a `MINIMALOG` notify event; the app just never consumed it. Bounded
600-line buffer filled from service start; Pause/Resume/Clear/Share, substring filter,
and per-subsystem verbosity chips (scripts/mining/blocks/networking/ibd/maxima) driving
the node's own `logs <key>:true|false` command, chip state seeded from the reply.

## 4.6 MegaMMR jar rollouts
`6b53402338e8d3e28ff8dada0fa28ebde3458e9b` (1.5.1), `f40328149e76b669087c9da7d17b01448cb91663`
(1.6.0, streaming import), `6a9c0aab3d353e41feb666d474272c1332731149` (1.6.1, watermark +
megaprune), `fff50e09718890d2dab9666dbf4916d8cabb7291` (1.6.2, MMR-tree watermark) — the
app-side releases carrying the Part 1.3 jars.

---

# Part 5 — Release engineering (ours; listed for completeness, not for adoption)

- `57765d83e489c956cef82a4cba3e18090a41f683` (1.4.2-ui-h2) — version-line unification
  after two concurrent sessions published clashing versionCodes; no code change.
- `7ced3b6540842d989e2a3b6bf5e869a0be0712c7` — dist artifacts + JDK pin so `./gradlew`
  works out of the box.
- `984573d1ba796c757164c93b1e59ba48a7b6c0d3` — releases signed with a managed
  4096-bit RSA family key instead of the debug key (also enables
  `android:protectionLevel="signature"` gating of the IPC receivers across our app
  family). Note for anyone tracking our builds: this is why upgrading from an
  older-signed install requires uninstall/reinstall.
- `6583d45c75cc17cc9984120f1d1c1312e4f0680e` — pre-commit hook enforcing a version bump
  on every code change.
- **1.6.10-ui-h2 (versionCode 38) is 1.6.9-ui-h2 code with no source change** — a
  version-bumped rebuild under the family release key. There is no hidden delta beyond
  `b326c7ae3f6afe935278d850b7f6d294de84521e`.

---

# Field testing

The fork is the daily-driver node on our mainnet phones and the host for a fleet of
broadcast-IPC companion apps (terminal, terminal IDE, file manager, wallets, NFT minter,
DEX/AMM, social) since 2026-07-21, running alongside unmodified legacy companions on the
same node. The MegaMMR work is measured against a real 343 MB mainnet `mega.mmr`
(1,513,769 MMR entries); the signData fix has run in the bundled jar since 1.6.7-ui-h2
with no ill effects; the 1.6.9 reply-cap split has twice been proven necessary by a
companion app dying on a pre-fix node (`am_kill "Can't deliver broadcast"`).

PR status: the off-main-thread executor was PR #1 and the H2 pin PR #2 on
`minima-core-android`; `security/signdata-race` on `minima-core` is a clean two-file
branch ready to PR. Everything else we can raise as PRs in whatever slices suit you, or
you can cherry-pick from the branches named above. Thanks for Minima.
