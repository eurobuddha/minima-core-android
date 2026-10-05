# MinimaBlock Merge Handoff — for independent model review

Session of 2026-10-05 · Minima build family (`~/Projects/minima`) · author: Claude Fable 5, directed by eurobuddha.

Subject: adoption of upstream minima-core 1.1.2.31 ("block as key uses") into our fork, a new Android SKU that forces the new key system, and compatibility fixes across the app family. Real funds, live chain.

---

## 1 · What upstream shipped

Spartacus Rex released minima-core **1.1.2.31** (upstream `main` at `c2a54a2e46b2c3a4fd541b80e7d7f5e7afe7d4da`, "250 million keys in block as keyuses") and minima-core-android **1.7** (`70cffca`). The headline change replaces the per-key incrementing use counter with a chain-derived one, behind the CLI flag `-blockaskeyuses` → `GeneralParams.USE_BLOCK_AS_KEYUSES`:

- **Tree shape.** With the flag on, `TreeKey.createDefault` builds 128 keys/level × 4 levels = 268,435,456 one-time signatures per key, instead of the legacy 64 × 3 = 262,144. Because the shape feeds key derivation, **the same seed produces different public keys and therefore different addresses**. Upstream's own comment: "INCOMPATIBLE with the OLD SEED".
- **Key use selection.** Every signature's leaf index is `max(chain-tip block number, lastused + 1)`, floor-checked against the wallet DB's stored `uses`, persisted immediately as `last_block_as_keysuses` in the UserDB (`saveUserDB()` right after, commit `a1753ef`). Signing throws if the node has no chain tip yet.
- **Command surface.** `txnsign keyuses:` becomes optional with the flag on (the higher value wins). The wallet DB `uses` column is updated to the block-derived value, so `keys` reports uses ≈ block height (~2.35M on mainnet today).
- **Wallet sizing.** `Wallet()` sets `NUMBER_GETADDRESS_KEYS = 32` when the flag is on (64 legacy) — commit `bb99865`.
- **Delivery.** Upstream's 1.7 Android app forces the flag on for everyone and became a *new application* (`org.minimarex.minimacore` → `org.minima.core`), so their existing users keep the old legacy app and migrate funds by hand. There is **no migration code anywhere** — no wallet record of which mode created it, no legacy option in the new app.

Besides key uses, the 44-commit delta since our divergence point `7188e68f68c9c3b5cfe4ff22869bd07a27b5c49f` ("1.1.2.3") contains: the SQL CoinDB/TxBlockDB work (coin set and TxBlocks move from RAM into H2 tables, enabled by `-lowram`, 18 commits), a new `IBDStore`, a new `rescue` command, a tokenid→name map in `history`, and `Wallet.signData` made `synchronized` (`63b82477f` — upstream independently took half of our vc35 security fix).

## 2 · The hazard upstream left open

> **Leaf-0 Winternitz reuse.** A legacy 64×3 wallet signing under `-blockaskeyuses` gets uses ≈ block height (2,350,487 measured at sync) against a 262,144-leaf capacity. Upstream's `TreeKey.sign` handles overflow by logging `SERIOUS ERROR : MAX TREEKEYS USED` and wrapping `mUses` back to 0 — after which **every signature reuses leaf 0**, the classic one-time-signature break that leaks the leaf's private key.
>
> Corollary: a legacy wallet cannot even sweep its own funds out while in block mode, because the sweep transaction itself needs a legacy-shape signature. Mode migration therefore always means: sweep from a legacy-mode node, create a fresh wallet on the block-mode one. This shaped every decision below.

## 3 · Decisions taken (confirmed with the owner)

1. **Mirror upstream:** ship our own build that forces the new key system, keeping every fix we have shipped since forking (heap, IPC, signing, DB).
2. **New SKU, fresh installs only:** the block-mode build is a separate app (`org.minimarex.minimablock`, "MinimaBlock"), never an update to the existing `org.minimarex.minimacore` app. The classic app stays published so existing wallets can keep running and sweep funds across.
3. **`-lowram` on,** matching upstream 1.7 exactly.
4. **PandaPools fixed in this effort** (its signing guards hard-break against any block-mode node).
5. **Scope:** core fork + apks/base + PandaPools. The maxima/node and desktop jars (built from the same fork) are deferred.

## 4 · The core branch, commit by commit

Branch `merge/upstream-1.1.2.31` on `eurobuddha/minima-core`, created at upstream head `c2a54a2e46b2c3a4fd541b80e7d7f5e7afe7d4da`. Our `main` was deliberately *not* rebased (it is the provenance record of previously shipped jars) and nothing was based on the unrelated `MinimaCoreC++` migration branch. Version string: `1.1.2.31-rex2` so `status` distinguishes this build from stock.

Everything our fork carries on top of upstream, oldest first:

| Commit | Type | What it does |
|---|---|---|
| `07feb65c0` | FIX | MegaMMR import survives OOM, fails loudly on truncated files, halves peak RAM (`MegaMMR.java`, `megammr.java`, `MiniFile.java`). Applied clean — upstream never touched these files. |
| `a80dcfc4c` | PERF | MegaMMR import streams the IBD in 256-block batches. |
| `0d466fa52` | PERF | MegaMMR in-flight heap watermark + megaprune at read time. |
| `1024beecb` | PERF | MMR tree load in-flight heap watermark (the proven killer on small Android heaps). |
| `26cc39834` | SEC | Synchronized `Wallet.signData` *and* `updateUses` (upstream `63b82477f` synchronized only signData; `updateUses` drives a shared `PreparedStatement`), plus the **sendsign leaf-burn dedupe**: N inputs at one address burn 1 leaf, not N, with a separate dedupe list for the burn transaction. Conflict resolution: upstream's block-keyuses rewrite of the signData body was kept wholesale; our synchronization and rationale comment layered on top. |
| `fa7dec56a` | FIX | `Wallet.removeScript` clears `mAllTrackedAddress`/`mAllSimpleAddress` (the vc39 fix — a demoted shared covenant script otherwise kept adopting every stranger's coin until restart). Still missing upstream. |
| `1fde291b2` | PERF | `TxPoWSqlDB` indexes on `timemilli` and `(isrelevant, timemilli)` plus the `WHERE timemilli > 0` clause H2 needs to use them (the vc45 fix; measured 25,535 → 3,755 page reads). |
| `2e568763c` | FIX | Relevant txpow rows age out (`-txpowdbrelevantstore`, default 30 days) and `coins` accepts `max:` for bounded reads (the vc63 fix; without it rows marked relevant were never deleted and the store grew until GC starvation). The `TxPoWSearcher` half was re-ported by hand onto upstream's lowram rewrite: a delegating overload chain ending in a `zMax` parameter, a `lowMemory()` watermark (max(32MB, maxMemory/20)) checked every 256 coins, a shared `finalise()` for the capped and normal exits, **and MEGAMMR read-lock release on both early exits** — an exit path neither parent had, which would otherwise leak `MinimaDB.readLock(true)`. |
| `477db5483` | SEC | **Guard A + Guard B** (new, ours — see §5). |
| `010d45211` | — | Full jar rebuild for rex1 (superseded by rex2 below; kept for provenance). |
| `326e60e6c` | SEC+FIX | **rex2: lowram state-filter fix + pre-persist capacity refusal** (see §5), plus the rebuilt jars. |

Cherry-pick conflict dispositions worth knowing: `sendsign.java` applied clean (zero upstream commits touched it since 1.1.2.3); patch `fa7dec56a` was picked with `-n` and its bundled jar/doc files dropped; `GeneralParams.java`/`ParamConfigurer.java` conflicts in the vc63 patch were pure textual adjacency (upstream added `-lowram`/`-blockaskeyuses` params in the same region) and both sides were kept; `coins.java` kept upstream's one-line change from `031172f` (`tip.getBlockNumber()`).

## 5 · Our fixes on top of upstream (the "-rex" delta)

### Guard A — capacity refusal instead of leaf-0 reuse (SEC)

In `Wallet.signData` and `txnsign publickey:custom`: if the uses about to be signed with reach the key's capacity (`TreeKey.getMaxUses()` = size^depth, 262,144 for legacy rows), the call **throws** — `Key exhausted : uses X >= capacity Y - refusing to sign (would reuse a one-time leaf key)` — instead of letting `TreeKey.sign` wrap to leaf 0. This also upgrades legacy-mode behaviour: a genuinely exhausted 64×3 key now refuses instead of silently reusing. This guard is what makes a forced-block-mode build shippable at all.

### Guard B — static leak (FIX)

Upstream's `Wallet()` constructor sets `NUMBER_GETADDRESS_KEYS = 32` only when the flag is on, and `GeneralParams.resetDefaults()` never restores it, so a flag-on boot leaked 32 into later flag-off boots of the same Android process. The assignment is now unconditional: `NUMBER_GETADDRESS_KEYS = USE_BLOCK_AS_KEYUSES ? 32 : 64`.

### rex2 fix 1 — lowram dropped the `state:` filter (SEC)

Found during adversarial code review and **proven live before fixing**: on a `-lowram` node, `coins state:0xDEADBEEF` (a value no coin carried) returned exactly the same rows as a matching query — upstream only runs the state check in full-RAM mode (`TxPoWSearcher`) and the `coins` command never re-filters. Every state-based coin search on a lowram node silently returns unfiltered results. **Upstream's released 1.7 app has this today.** Our fix: in SQL-CoinDB mode the loop fetches the full row (`CoinDB.getCoin`, a leaf SQL op taking no RW lock — no lock-order inversion) for the check; MEGAMMR coins (absent from the tree CoinDB) carry state inline and are checked as-is. Filtering happens at collection time, so `max:` counts *matching* coins. After the fix, matching state returns the coin and non-matching returns zero rows (both verified live). This should be reported upstream.

### rex2 fix 2 — refuse before persisting (FIX)

`block.getCurrentBlockAsKeyUses` gained a `zMaxUses` overload that performs Guard A's refusal *before* `last_block_as_keysuses` is advanced and `saveUserDB()` runs — a retry loop against an exhausted key no longer burns a counter increment and a disk write per refusal. Verified live: a refused attempt leaves no `BLOCK AS KEYUSES` log line. The one-argument form remains, delegating with 0 (no capacity check), and both signing callers use the two-argument form.

## 6 · Jar build system

The previous process (hand-compiling single classes with `javac --release 11` and zipping them into a 1.1.2.3-era jar, per `apks/base/JAR_PATCH_PROCEDURE.md`) cannot absorb a 44-commit delta. The jars are now **fully rebuilt**: the repo's Gradle 6.7.1 wrapper on Homebrew OpenJDK 11 (`JAVA_HOME=/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home ./buildjars.sh`). Naming inversion preserved: Gradle's `minima.jar` output is the *nolibs* jar (what the APK bundles, H2 supplied by the app at 2.1.214), `minima-all.jar` is the fat jar (H2 2.4.240). Bytecode targets major 52 (≤ Java 11, Android-safe).

**Toolchain proof:** classes we did not patch (`TreeKey.class` CRC `854f22af`, `block.class` CRC `8d1da12c` at rex1) are byte-identical to the jar inside upstream's shipped 1.7 APK; patched classes differ. This establishes that our Gradle/JDK combination reproduces upstream's bytes exactly, so every difference in the shipped jar is an intended patch.

## 7 · MinimaBlock, the new Android SKU (apks/base)

One codebase, two product flavors on branch `ui/full-redesign` of `eurobuddha/minima-core-android`:

- **classic** — `org.minimarex.minimacore`, "MinimaCore", legacy key system, the app existing wallets run. Behaviour unchanged, but note: it now bundles the rex2 jar, so its *next published release* carries the full 1.1.2.3 → 1.1.2.31 core jump (flag off) and needs its own device soak — recorded in the version history as a hard note.
- **block** — `org.minimarex.minimablock`, "MinimaBlock", fresh installs only. `MinimaService` always passes `-blockaskeyuses -lowram` (bare flags; `ParamConfigurer` resolves a bare flag to `"true"` via `lookAheadToNonParamKeyArg(...).orElse("true")` — verified, this was the one silent-failure candidate).

Changes across vc65 → vc67 (versionName `1.7.0` → `1.7.2-ui-h2`):

| Area | Change |
|---|---|
| `ParamsActivity` | `-blockaskeyuses` added to `BLOCKED_FLAGS` — the key mode is the flavor's decision, never free text. Evasion checked: flag matching is exact-token and `ParamKeys.toParamKey` is case-sensitive, so a case-variant throws `UnknownArgumentException` rather than sneaking through. |
| `MinimaReceiver` / manifest **(vc66, found on-device)** | The IPC file-hand-off FileProvider declared the fixed authority `org.minimarex.minimacore.ipcresponses`, which made the two SKUs **uninstallable side by side** (`INSTALL_FAILED_CONFLICTING_PROVIDER`). Now `${applicationId}.ipcresponses` in the manifest and `BuildConfig.APPLICATION_ID + ".ipcresponses"` in code. Companions unaffected — they open the granted `content://` URI from the response intent, never the constant. |
| Broadcast IPC | **Action prefix deliberately unchanged** (`MinimaAPIMessages.MINIMA_BASE_CLASS` stays `org.minimarex.minimacore`) so every companion APK works against either SKU with zero changes. Caveat: with both apps *running*, both nodes would answer the same broadcasts — and both bind port 11001, so they cannot run together anyway. |
| `MinimaService` (vc67) | Port pre-flight: a background-thread socket probe of `127.0.0.1:11001` before `mainStarter`; a hit toasts a plain-language warning. Informational only; start proceeds. Known benign false positive: our own node still shutting down during a service re-create. |
| `ResyncJob` | `MAX_KEY_USES = BuildConfig.BLOCK_KEYUSES ? 268435456 : 262144`. |
| Restore flows (vc67) | Block flavor pre-fills the key-uses field with the floor (1000) and relabels it "Key uses (safety floor — usually leave as is)"; the restore intro carries a warning that a classic seed produces *different addresses* here and classic funds will not appear — sweep from the classic app instead. |
| Displays | "Key uses" relabelled "Last key block" in the block flavor (`keys.maxuses` ≈ last signed block height there, not progress toward exhaustion). |

## 8 · PandaPools (hard-broken by block mode; fixed)

PandaPools' signing guards hard-coded the legacy capacity 262,144 in roughly fifteen places across both implementations. Against any block-mode node — **including upstream's own released 1.7 app** — every healthy key reports uses ≈ 2.35M, so every guard read it as exhausted/unreadable and **all signing stopped**, including plain swaps funded from ordinary wallet coins.

**Fix (native 0.9.62/0.9.63, MDS 0.6.29–0.6.31):** capacity now comes from the key's own row — `KeyUses.capacityOf` / `capacityOfRow` compute `size^depth` from the same `keys` reply (128^4 clamp; legacy 262,144 fallback when the row carries no shape, which only pre-1.1.2.31 nodes produce). Threaded explicitly through `classify`, `baseSigningAllowed`, `signingAllowed` and `RestoreExit.refusal`; stored-counter sanity bounds widened to the 128^4 maximum; the counter-regression floor and signing quarantine are untouched (block-derived uses are monotonic, so they can no longer fire spuriously). The production-dead `ExitTicket.eligible` mirror — which had already drifted (it never learned the lifetime signature cap) — was deleted in 0.9.63; the live gate `RestoreExit.refusal` plus `RestoreExitRefusalTest` pin every condition.

> **Standing constraint:** a legacy pool's owner key (`$OPK`, minted with `newaddress`) cannot be re-derived from seed on a block-mode node — the new tree shape yields a different public key. Existing pools keep working while the node retains the key rows; **close or migrate legacy pools from a legacy-mode node**. The recipe-plus-seed recovery promise does not survive a mode switch.

## 9 · AtomiX and the rest of the family

**AtomiX needs no code change.** It signs via `txnsign publickey:auto` or explicit state keys, never passes `keyuses:`, never reads `uses`. The HTLC script holds no keys (owner/counterparty come from `PREVSTATE(0)`/`PREVSTATE(4)`); the HTLC address is identical under both modes:

```
MxG080CRJB1D4NHGRYGNF7Q52FK7023UM3FUUPVD1W1WCQZSA8MDQ25982N842G
0x0CDCD61692F186EB0BBCFA289F438043F586FF7B3F6864193358E29166E8454A
```

Two operational rules are recorded in `apks/atomix/CLAUDE.md` (commit `8e209f1`): **drain all in-flight swaps before any wallet/mode migration** (a party that loses its state-key mid-swap loses the leg or locks the coin), and the persisted swap identity `swap_pk`/`swap_addr` does not survive a mode switch (IdentityWatch halts trading until re-picked; counterparties need the new maker key).

**Deferred, recorded in the plan:** maxima/node and desktop jar rebuilds; privatekey+keyuses signers that build legacy-shape keys via `txnsign publickey:custom` against a block-mode node (minimacore-desktop webwallet, mds/AnyPhraseRecovery, tools/minimask, minima-core-meg walletapi); the 64×3-assuming scanners (mds/keyuses, apks/keyreuses, tools/dexHistory `keyaudit/harvest.py` leaf-index math, tools/WOTS); the vendored 64×3 Winternitz signers in apks/wallet, support/freezepeach and the maxima app are safe (they sign locally with their own counters; signature verification is shape-agnostic) but their "import node seed" flows break against block-mode nodes. dexHistory's reconciliation ledgers parse coins and balances only — unaffected.

## 10 · Verification evidence (all by execution, not inspection)

- **Toolchain reproduction:** unpatched class CRCs byte-identical to upstream's shipped 1.7 jar; patched classes differ (§6).
- **Unit:** `TxPoWSqlDBCleanCheck` 10/10 against the APK's H2 2.1.214 (both rex1 and rex2 jars). App: 103 tests green per flavor, lintVital green. PandaPools native: 299 green (307 before the duplicate-test deletion). MDS: 29 + 18 green including new block-mode classify cases.
- **Block-mode signing, test chain:** fresh wallet mints 128×4 keys; signing at block 4 logs `BLOCK AS KEYUSES TreeKeyDB:0 topblock:4 lastused:0 higher:4`; uses persist as 5.
- **Guard A live:** `txnsign ... keyuses:999999999` → `Key exhausted : uses 1000000000 >= capacity 268435456 - refusing to sign (would reuse a one-time leaf key)`, and (rex2) the refused attempt leaves no counter log line.
- **State filter live (rex2):** on a `-lowram` chain, before: non-matching state returned every coin; after: matching returns the coin, non-matching returns 0 rows.
- **Mainnet:** the fat jar synced the real chain to block 2,350,487 under `-lowram -blockaskeyuses` with zero exceptions (1,719,466 coins through the SQL CoinDB).
- **H2 2.1.214 + lowram:** the nolibs jar on the APK's exact H2 created both new SQL stores (`coindb`, `txblockdb`), signed at the tip, served `coins max:` bounded, zero exceptions — closing the gap that the mainnet test ran on the fat jar's H2 2.4.240.
- **Device (`R58M307HEEN`, Galaxy S10+):** the vc66 provider-authority defect was *caught* by a real install (`INSTALL_FAILED_CONFLICTING_PROVIDER`); after the fix both SKUs coexist, re-verified at vc67. The shipped vc67 APK's dex carries both rex2 markers, and `app/libs/minima.jar` is byte-identical to the rex2 build (sha256 `7e1134de1a3c1f9ffe3f5e159a2a0d0e056e99723bec145813a2475d19c38492`).
- **Two adversarial review passes** ran over everything; all findings fixed with runtime evidence. Remaining accepted items: state searches on lowram nodes are N serial SQL lookups (batch by 256 if ever measured hot); txnsign's capacity refusal carries a `java.lang.IllegalArgumentException:` prefix in the RPC error (upstream's "NO BLOCKS FOUND" does the same); the port probe can blame "the other app" while our own node is mid-shutdown.

## 11 · What the reviewer should probe

1. **The TxPoWSearcher hand-port** (`2e568763c` + `326e60e6c`) is the highest-risk merge artifact: the cap/heap checks sit inside a loop that serves three regimes (RAM tree, SQL CoinDB, MEGAMMR under a read lock). We verified lock release on both early exits and state-correctness live, but a fresh reading of the full function is the single most valuable check.
2. **Guard A completeness:** we guard `Wallet.signData` and `txnsign publickey:custom`. Is there any other path that calls `TreeKey.sign` with attacker-influenceable or chain-derived uses? (Our audit says no — all wallet signing funnels through signData — but this is the claim whose failure is catastrophic.)
3. **The mode-migration story:** fresh-SKU-only avoids every in-place hazard we identified, at the cost of users running two apps during migration. Check the restore-screen warning and the port-collision handling cover the realistic foot-guns.
4. **PandaPools capacity threading:** every former `262144` literal is either per-key capacity or an explicit max-shape sanity bound now. Grep both repos for `262144` and confirm each survivor is intentional (native keeps it only as `LEGACY_TREE_USES` and in comments/messages; sha3.js's CSHAKE constant is unrelated).
5. **What we did not test:** block-mode behaviour under real multi-day uptime (Doze, OEM battery managers), a lowram node on a phone-sized heap at mainnet scale, and any interaction with upstream 1.7 peers beyond plain sync. The owner's policy is "we test in prod" — flag anything you believe cannot wait for that.

## 12 · Inventory: repos, commits, artifacts

| Repo / branch | State |
|---|---|
| `core/minima-core` · `merge/upstream-1.1.2.31` | Pushed to `eurobuddha/minima-core`, head `326e60e6c` (= upstream `c2a54a2e4` + 11 commits). `main` deliberately NOT fast-forwarded until live soak. |
| `apks/base` · `ui/full-redesign` | Pushed to `eurobuddha/minima-core-android` (push to `fork` only — `origin` is upstream). Head `d49c076` (vc67). Commits: `9c62816` vc65, `0b950d7` vc66, `e901dba` sidecar, `d49c076` vc67. |
| `apks/pandapools` · `review/0.8.9` | Pushed. `3b44726` 0.9.62, `03abcd1` 0.9.63. |
| `mds/pandapools-mds` · `master` | Pushed. `1d4dd70` 0.6.29, `cfa0a4b` 0.6.30 (version-string sync; 0.6.29 never published), `0ee26d0` 0.6.31. |
| `apks/atomix` · `master` | Pushed. `8e209f1` (docs: operational rules). A later commit `f83c558` (0.1.70, regression test) is not part of this session's work. |

**Release artifacts, built and hash-verified, not yet published:**

```
582ffe9fd922d11a38970bf92e02ceb1636eec5c86ed255a624c66729bad61b4  minima-block-1.7.2.apk
0a26f8e0251770b2e0d1dcb16217848b29eb322edf8fe06a989d2df499971773  pandapools-0.9.63.apk
c7b7ca7db49eb32aec84068f9d22f4287d7b412e24366e9440e3ad2fad450bee  PandaPools_0.6.31.mds.zip
7e1134de1a3c1f9ffe3f5e159a2a0d0e056e99723bec145813a2475d19c38492  minima.jar (nolibs, rex2, bundled in the APK)
```

All APKs signed with the family release key (cert SHA-256 `eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f`). Sidecars committed in each repo's `dist/` as full `shasum -a 256` lines so `shasum -a 256 -c` works.

## 13 · Pending publish actions (owner-run; blocked for the agent by the deploy classifier)

```bash
# 1. MinimaBlock release
cd ~/Projects/minima/apks/base
gh release create v1.7.2-block dist/minima-block-1.7.2.apk \
  --repo eurobuddha/minima-core-android \
  --title "MinimaBlock 1.7.2-ui-h2-block (vc67)" --notes-file <relnotes>

# 2. PandaPools native release
cd ~/Projects/minima/apks/pandapools
gh release create v0.9.63 dist/pandapools-0.9.63.apk \
  --repo eurobuddha/minima-core-android-pandapools --title "PandaPools 0.9.63"

# 3. PandaPools MiniDapp release
cd ~/Projects/minima/mds/pandapools-mds
gh release create v0.6.31 PandaPools_0.6.31.mds.zip \
  --repo eurobuddha/pandapools-mds --title "PandaPools MiniDapp 0.6.31"

# 4. Catalog (AFTER the releases exist - publish-app.py downloads the asset as its check)
cd ~/Projects/minima/desktop/minima-core-apks
python3 scripts/publish-app.py com.eurobuddha.pandapools 0.9.63
#   then prepend the release notes to that row's description by hand
# MinimaBlock is a NEW row - publish-app.py only updates rows; hand-add next to PandaBear:
#   name "MinimaBlock" · packageId org.minimarex.minimablock
#   version "1.7.2-ui-h2-block" · versionCode 67 · category Core · source PandaApps
#   file  https://github.com/eurobuddha/minima-core-android/releases/download/v1.7.2-block/minima-block-1.7.2.apk
#   sha256 582ffe9fd922d11a38970bf92e02ceb1636eec5c86ed255a624c66729bad61b4
./check.py && git commit -am "MinimaBlock 1.7.2 + PandaPools 0.9.63" && git push
# verify: git show origin/main:apks.json | grep -A3 MinimaBlock
```

Also pending, non-blocking: report the lowram `state:` regression upstream; a distinct MinimaBlock launcher icon; the classic-flavor soak decision before its next publish; the deferred items in §9. The full working plan lives at `~/.claude/plans/spartacus-rex-has-released-composed-bird.md`; durable facts are in the project memory (`block-as-keyuses-minimablock`).
