package com.eurobuddha.futurecashnext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The guardian engine: detect matured stakes, collect them WOTS-safely, and rescue the ones landing
 * on a reused key.
 *
 * <h3>The threat model, in one paragraph</h3>
 * A collect is pinned by the covenant — VERIFYOUT forces the funds to the payout address committed
 * at lock time — so a collect can only ever pay that address and is always WOTS-safe in itself. The
 * danger is only that the funds then SIT on a payout address whose one-time key was reused. So:
 * <ul>
 *   <li><b>Safe payout</b> (audit says clean) → just collect, when the user has opted in.</li>
 *   <li><b>At-risk payout</b> (reused) → collect and immediately sweep to a safe destination, one
 *       signature, racing any attacker, then verify it landed.</li>
 *   <li><b>Can't tell yet</b> → leave it. An uncollected coin sits in the covenant where it is not at
 *       risk at all; collecting is what moves funds ONTO the exposed address. Waiting is strictly
 *       safer than acting.</li>
 * </ul>
 *
 * <p>Runs on ONE worker thread (see GuardianService) and blocks on node calls, so it reads as the
 * straight sequence it always was — the MiniDapp's nested-callback shape is an artifact of MDS
 * having no synchronous command, not of the logic.
 */
public final class Guardian {

    /** Sweep the instant the collected coin is spendable — minimal time on the exposed key. */
    public static final int SWEEP_CONFIRM_DEPTH = 0;
    /** A *_POSTED coin still unspent this long => re-try. */
    public static final int RETRY_BLOCKS = 8;
    /** After this many failed posts, raise a one-time alarm (never give up). */
    public static final int MAX_ATTEMPTS = 5;
    /** After the collected coin is spent, blocks to keep looking for OUR output at the safe. */
    public static final int VERIFY_GRACE = 3;
    /** If the safe lookup stays unreliable this long, record swept-but-unverified. */
    public static final int VERIFY_MAX = 30;

    private final Node node;
    private final Cfg cfg;
    private final Store store;
    private final Wallet wallet;
    private final Audit audit;
    private final Notifier notifier;

    /** Canonical at-risk payout addresses, refreshed at the top of every pass. */
    private final Set<String> atRisk = new HashSet<>();
    /** Stake coins seen in this pass, by coinid — saves re-fetching in collectMatured. */
    private final java.util.HashMap<String, JSONObject> seenStakes = new java.util.HashMap<>();

    private boolean vaultLocked = false;
    private boolean lockNotified = false;
    private boolean cannotRescueNotified = false;

    public interface Notifier {
        void notify(String title, String body);
    }

    public Guardian(Node node, Cfg cfg, Store store, Notifier notifier) {
        this.node = node;
        this.cfg = cfg;
        this.store = store;
        this.wallet = new Wallet(node, cfg);
        this.audit = new Audit(cfg, node);
        this.notifier = notifier;
    }

    public Wallet wallet() { return wallet; }
    public Audit audit() { return audit; }

    /* ================= detection ================= */

    /** Shape test: a payout in port 2 and a plain decimal block number in port 1. */
    public static boolean looksLikeFutureCash(JSONObject coin) {
        final String payout = Node.state(coin, FutureCashContract.ST_RECIPIENT);
        final String s1 = Node.state(coin, FutureCashContract.ST_FUTUREBLOCK);
        if (payout == null || payout.isEmpty() || s1 == null) return false;
        return s1.matches("^[0-9]+$");
    }

    /**
     * Find this node's stakes.
     *
     * <p>Scans {@code coins relevant:true} ONLY, which is both cheaper and safer than it looks. We
     * register the covenant with trackall:false, so core keeps a stake coin only when one of its
     * state variables is an address this node tracks — i.e. only when port 2 pays out to US. Core's
     * relevance filter IS the ownership filter, and it runs before the coin ever reaches this app.
     * (Measured against the alternative on a live node: all 33 of that node's stakes were in the
     * relevant set; the extra contract-address scan added only 14 stakes belonging to strangers.)
     *
     * <p>ownsAddress is still applied, and is still load-bearing: relevance is stamped when a block
     * is PROCESSED and never re-evaluated, so any stake that arrived while a node had the covenant
     * registered trackall:true stays in its relevant set for good.
     */
    private void detect(long tip, Set<String> ownDefault) {
        seenStakes.clear();
        final JSONObject r = node.cmd("coins relevant:true");
        final JSONArray arr = Node.arr(r);
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            final String coinid = c.optString("coinid", "");
            if (coinid.isEmpty() || c.optBoolean("spent", false)) continue;
            if (!looksLikeFutureCash(c)) continue;

            final String payout = Node.state(c, FutureCashContract.ST_RECIPIENT);
            if (!Wallet.owns(ownDefault, payout)) continue;   // only stakes paying out to US

            seenStakes.put(coinid, c);
            final long matureBlock = parseLong(Node.state(c, FutureCashContract.ST_FUTUREBLOCK));
            final boolean risk = atRisk.contains(Wallet.canonAddr(payout));

            if (store.collectExists(coinid)) {
                store.refreshCollect(coinid, matureBlock, risk);
                // This stake coin is unspent again (spent ones are filtered out above), so if we had
                // recorded it collected, a reorg reversed that — re-arm it.
                store.reArmAfterReorg(coinid);
            } else {
                final String amount = Node.outAmount(c);
                store.insertCollect(coinid, payout, c.optString("tokenid", "0x00"), amount, matureBlock, risk);
                cfg.log(Cfg.LVL_INFO, "Detected FutureCash (" + amount + ")" + (risk ? " [AT RISK]" : "")
                        + " | stake:" + coinid + " | to:" + payout);
            }
        }
    }

    /* ================= collect ================= */

    private void collectMatured(long tip, boolean autoCollectSafe, boolean rescueEnabled,
                                boolean auditFresh, boolean canSweep, Set<String> ownDefault) {
        for (Store.CollectRow row : store.collectsWithStatus(Store.DETECTED)) {
            // Ownership is re-checked HERE, not just at detect time: a row already in the table would
            // otherwise stay actionable forever. Never post for a payout we don't own.
            if (!Wallet.owns(ownDefault, row.payout)) {
                store.deleteCollect(row.coinid);
                continue;
            }
            final JSONObject coin = seenStakes.get(row.coinid);
            if (coin == null) {
                // Not in this pass's relevant set. If it's spent, someone (probably us) collected it.
                if (!node.coinIsUnspent(row.coinid)) store.setCollectStatus(row.coinid, Store.COLLECTED);
                continue;
            }
            if (!matured(coin, tip)) continue;

            final boolean bareCollectSafe = !row.atRisk && auditFresh && autoCollectSafe;
            final boolean collectToSweep = row.atRisk && rescueEnabled && canSweep;
            if (!bareCollectSafe && !collectToSweep) continue;   // can't act safely yet — leave DETECTED

            final String posted = bareCollectSafe ? Store.COLLECT_POSTED_SAFE : Store.COLLECT_POSTED;
            // Atomic claim: if another pass got here first, this returns false and we do not post.
            if (!store.claimForPost(row.coinid, posted, tip)) continue;

            final Tx.Result res = Tx.collect(node, row.coinid, row.payout, row.amount, row.tokenid);
            if (res.ok) {
                notifier.notify("Future Cash", "Collecting matured " + row.amount
                        + (bareCollectSafe ? "" : " — will sweep to safe"));
                cfg.log(Cfg.LVL_INFO, "Collecting " + row.amount + (bareCollectSafe ? " [safe]" : " [rescue]")
                        + " | stake:" + row.coinid + " | to:" + row.payout);
            } else {
                cfg.log(Cfg.LVL_ERROR, "Collect post failed | stake:" + row.coinid
                        + " | to:" + row.payout + " — " + res.error);
            }
        }
    }

    /** Exactly the on-chain unlock, so our "ready" can never disagree with what the script accepts. */
    private static boolean matured(JSONObject coin, long tip) {
        final long s1 = parseLong(Node.state(coin, FutureCashContract.ST_FUTUREBLOCK));
        final long s4 = parseLong(Node.state(coin, FutureCashContract.ST_COINAGE));
        final long created = parseLong(coin.optString("created", "0"));
        if (s1 > 0 && tip >= s1) return true;
        return s4 > 0 && created > 0 && (tip - created) >= s4;
    }

    private void reconcileCollects(long tip) {
        for (Store.CollectRow row : store.collectsWithStatus(Store.COLLECT_POSTED, Store.COLLECT_POSTED_SAFE)) {
            // Terminal state comes from the LIVE at_risk flag, not just the posted status: a coin
            // bare-collected as SAFE that a fresh audit has since flipped to at-risk must NOT finish
            // as terminal-safe — route it to COLLECTED so it gets swept off the exposed address.
            final boolean safe = Store.COLLECT_POSTED_SAFE.equals(row.status) && !row.atRisk;
            final Node.CoinState st = node.coinState(row.coinid);

            if (st == Node.CoinState.SPENT) {
                store.setCollectStatus(row.coinid, safe ? Store.COLLECTED_SAFE : Store.COLLECTED);
                if (safe) notifier.notify("Future Cash", "Collected " + row.amount + " safely");
                cfg.log(Cfg.LVL_INFO, "Collected " + row.amount
                        + (safe ? " [safe]" : " [rescue → sweeping next]")
                        + " | stake:" + row.coinid + " | on:" + row.payout);
                continue;
            }
            if (tip - row.collectBlock >= RETRY_BLOCKS) {
                store.setCollectStatus(row.coinid, Store.DETECTED);
                if (row.attempts == MAX_ATTEMPTS) {
                    notifier.notify("Future Cash", "A collection keeps failing — check this node");
                    cfg.log(Cfg.LVL_ERROR, "Collect still failing after " + row.attempts
                            + " tries | stake:" + row.coinid + " | to:" + row.payout);
                } else {
                    cfg.log(Cfg.LVL_WARN, "Collect retry scheduled | stake:" + row.coinid
                            + " | to:" + row.payout);
                }
            }
        }
    }

    /* ================= sweep ================= */

    /** Find the coin each at-risk collect produced, and queue it to be moved to safety. */
    private void detectCollectedForSweep(long tip) {
        final Set<String> claimed = new HashSet<>(store.allSweepCoinIds());
        for (Store.CollectRow row : store.collectsWithStatus(Store.COLLECTED)) {
            final JSONObject r = node.cmd("coins relevant:true sendable:true address:" + row.payout);
            final JSONArray arr = Node.arr(r);
            JSONObject found = null;
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject c = arr.optJSONObject(i);
                if (c == null || c.optBoolean("spent", false)) continue;
                final String cid = c.optString("coinid", "");
                if (cid.isEmpty() || claimed.contains(cid)) continue;
                if (!row.tokenid.equals(c.optString("tokenid", ""))) continue;
                if (!row.amount.equals(Node.outAmount(c))) continue;
                final long created = parseLong(c.optString("created", "0"));
                if (created > 0 && created < row.collectBlock) continue;   // predates our collect
                found = c;
                break;
            }
            if (found == null) continue;
            final String cid = found.optString("coinid", "");
            claimed.add(cid);
            store.insertSweep(cid, found.optString("address", row.payout),
                    found.optString("tokenid", "0x00"), Node.outAmount(found));
            store.setCollectStatus(row.coinid, Store.SWEEP_QUEUED);
            cfg.log(Cfg.LVL_INFO, "Queued " + Node.outAmount(found) + " for sweep to safe | coin:" + cid
                    + " | on:" + found.optString("address", ""));
        }
    }

    /**
     * Coins stranded on our RETIRED addresses — bare-collected there before we flagged them at-risk,
     * or any deposit onto a nulled-key address. Queued for the normal sweep, which signs with the
     * explicit key from each address's SIGNEDBY script (auto can't: the row key is nulled).
     */
    private void detectStrandedForSweep() {
        final List<String> retired = wallet.retiredAddresses();
        if (retired.isEmpty()) return;
        final Set<String> claimed = new HashSet<>(store.allSweepCoinIds());
        for (String addr : retired) {
            for (JSONObject c : wallet.coinsAt(addr)) {
                final String cid = c.optString("coinid", "");
                if (cid.isEmpty() || !claimed.add(cid)) continue;
                store.insertSweep(cid, addr, c.optString("tokenid", "0x00"), Node.outAmount(c));
                cfg.log(Cfg.LVL_INFO, "Stranded " + Node.outAmount(c) + " | coin:" + cid
                        + " | on retired:" + addr + " — queued to sweep to safe");
            }
        }
    }

    private void sweepPending(long tip, String safe) {
        for (Store.SweepRow row : store.sweepsWithStatus(Store.PENDING)) {
            final String signKey = wallet.signKeyFor(row.address);
            if (signKey == null) {
                // No signable key for this address. Leave the row PENDING rather than burning an
                // attempt on a transaction that cannot be signed — and say so once per pass, because
                // this needs a human (usually: the address was retired on a node since reseeded, so
                // the key is genuinely gone).
                cfg.log(Cfg.LVL_ERROR, "Can't sweep — no signing key for this address on this node "
                        + "| coin:" + row.coinid + " | on:" + row.address);
                continue;
            }
            if (!store.claimSweepForPost(row.coinid, tip)) continue;   // another pass owns it

            final Tx.Result res = Tx.sweep(node, row.coinid, safe, row.amount, row.tokenid, signKey);
            if (res.ok) {
                cfg.log(Cfg.LVL_INFO, "Sweeping " + row.amount + " | coin:" + row.coinid
                        + " | from:" + row.address + " | to:" + safe);
            } else {
                if (res.error != null && res.error.matches("(?i).*(password|vault|unlock).*")) vaultLocked = true;
                cfg.log(Cfg.LVL_ERROR, "Sweep post failed | coin:" + row.coinid
                        + " | from:" + row.address + " — " + res.error);
            }
        }
    }

    /**
     * Did our swept output actually arrive?
     *
     * @return {reliable, coinid} — reliable=false means the lookup itself was unusable, which is NOT
     *         the same as evidence the money never arrived.
     */
    private Object[] outputArrivedAtSafe(String safe, Store.SweepRow row) {
        final Set<String> used = new HashSet<>(store.sweepNotes());

        // Look up by the HEX form when we have it. safe_address stores the Mx, but clean_addr holds
        // {mx,hex} for the same address, and the failure here is asymmetric: a query that ERRORS is
        // inconclusive and benign, while a query that succeeds and returns nothing is read as theft.
        // An address-form mismatch must never be what separates those two.
        String lookup = safe;
        if (wallet.sameNodeMode()) {
            final String hex = wallet.cleanAddressHex();
            if (hex != null) lookup = hex;
        }

        final JSONObject r = node.cmd("coins address:" + lookup + " tokenid:" + row.tokenid);
        if (!Node.ok(r) || r.optJSONArray("response") == null) return new Object[]{false, null};
        final JSONArray arr = r.optJSONArray("response");
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            final String cid = c.optString("coinid", "");
            if (cid.isEmpty() || used.contains(cid)) continue;
            // In samenode mode the safe is OUR address, so a matching coin that has since been SPENT
            // still proves it arrived (we then spent it). At an external address only unspent counts.
            if (c.optBoolean("spent", false) && !wallet.sameNodeMode()) continue;
            if (!row.tokenid.equals(c.optString("tokenid", ""))) continue;
            if (!row.amount.equals(Node.outAmount(c))) continue;
            final long created = parseLong(c.optString("created", "0"));
            if (created > 0 && created < row.sweepBlock) continue;
            return new Object[]{true, cid};
        }
        return new Object[]{true, null};
    }

    private void reconcileSweeps(long tip, String safe) {
        // Only a SAMENODE safe can be verified: an external safe is required to be an address this
        // node does NOT track, so the lookup is always empty — which the check below would read as
        // "verified absent" and report as theft. No ability to verify is not evidence of loss.
        final boolean canVerify = Node.validMx(safe) && wallet.sameNodeMode();

        for (Store.SweepRow row : store.sweepsWithStatus(Store.SWEEP_POSTED)) {
            final Node.CoinState st = node.coinState(row.coinid);

            // REORG: the collected coin we were sweeping no longer exists, so the collect that made
            // it was rolled back. Nothing was swept and nothing was stolen — drop the sweep row and
            // let detection re-arm the stake. Marking this SWEPT (what treating "gone" as "spent"
            // did) both lost the stake and raised the compromise alarm on an ordinary shallow reorg.
            if (st == Node.CoinState.GONE) {
                store.deleteSweep(row.coinid);
                cfg.log(Cfg.LVL_WARN, "Sweep abandoned — the collected coin was rolled back by a chain "
                        + "reorg; the stake will be collected again | coin:" + row.coinid
                        + " | on:" + row.address);
                continue;
            }

            if (st == Node.CoinState.SPENT) {
                if (!canVerify) { markSwept(row, null, safe, tip); continue; }
                final Object[] res = outputArrivedAtSafe(safe, row);
                final boolean reliable = (Boolean) res[0];
                final String outCoin = (String) res[1];

                if (outCoin != null) { markSwept(row, outCoin, safe, tip); continue; }
                if (reliable && (tip - row.sweepBlock) >= VERIFY_GRACE) {
                    store.setSweepStatus(row.coinid, Store.TAKEN, null);
                    notifier.notify("Future Cash ⚠️", "A collected stake (" + row.amount + ") was spent but "
                            + "did NOT reach your safe address. Your old key may be compromised — check now.");
                    cfg.log(Cfg.LVL_ERROR, "⚠️ ALERT: a collected stake was spent but NOT seen at your safe "
                            + "— check for key-reuse loss | coin:" + row.coinid + " | from:" + row.address);
                    continue;
                }
                if (!reliable && (tip - row.sweepBlock) >= VERIFY_MAX) {
                    store.setSweepStatus(row.coinid, Store.SWEPT, "unverified");
                    notifier.notify("Future Cash", "A stake was spent but the node couldn't confirm it "
                            + "reached your safe address — please verify it arrived.");
                    cfg.log(Cfg.LVL_WARN, "Sweep spent but UNVERIFIED — please check it arrived | coin:"
                            + row.coinid + " | from:" + row.address);
                }
                continue;
            }

            if (tip - row.sweepBlock >= RETRY_BLOCKS) {
                store.setSweepStatus(row.coinid, Store.PENDING, null);
                if (row.attempts == MAX_ATTEMPTS) {
                    notifier.notify("Future Cash", "A sweep keeps failing — funds still on an exposed "
                            + "address. Is your vault unlocked?");
                    cfg.log(Cfg.LVL_ERROR, "Sweep still failing after " + row.attempts + " tries | coin:"
                            + row.coinid + " | from:" + row.address);
                } else {
                    cfg.log(Cfg.LVL_WARN, "Sweep retry scheduled | coin:" + row.coinid
                            + " | from:" + row.address);
                }
            }
        }
    }

    private void markSwept(Store.SweepRow row, String outCoinid, String safe, long tip) {
        store.setSweepStatus(row.coinid, Store.SWEPT, outCoinid);
        cfg.set(Cfg.LAST_ACTION, "Secured " + row.amount + " to safe @block " + tip);
        notifier.notify("Future Cash", "Secured " + row.amount + " to your safe address");
        cfg.log(Cfg.LVL_INFO, "Secured " + row.amount + " | coin:" + row.coinid + " | to:" + safe);
    }

    /* ================= the pass ================= */

    /**
     * One reconcile pass. Called on every new block (and on demand).
     *
     * <p>Serialised by the single worker thread in GuardianService, and every state transition that
     * leads to a post is an atomic claim in the database, so even a second pass could not double-post.
     */
    public synchronized void reconcile(long tip) {
        vaultLocked = false;
        cfg.set(Cfg.LAST_TIP, String.valueOf(tip));

        // The at-risk set: the audited addresses, PLUS our own retired ones. The audit only covers
        // the node's default addresses, so a retired (non-default) reused address is invisible to it
        // — and a stake paying out there would be bare-collected onto a reused address the guardian
        // then couldn't auto-sweep. Flagging them forces the rescue path.
        atRisk.clear();
        final JSONArray flagged = cfg.atRisk();
        for (int i = 0; i < flagged.length(); i++) {
            final String a = flagged.optString(i, "");
            if (Node.validAddr(a)) atRisk.add(Wallet.canonAddr(a));
        }
        for (String a : wallet.retiredAddresses()) atRisk.add(Wallet.canonAddr(a));

        final boolean autoCollectSafe = cfg.is(Cfg.AUTO_COLLECT_SAFE, false);
        final boolean rescueEnabled = cfg.is(Cfg.ENABLED, false);
        final String safe = cfg.get(Cfg.SAFE_ADDRESS, null);

        final Wallet.AddressSets sets = wallet.addressSets();
        detect(tip, sets.ownDefault);

        final Audit.Verdict verdict = audit.usable();

        // Rescue needs a usable safe AND an unlocked vault, both checked BEFORE collecting, so an
        // at-risk coin is only pulled onto its exposed address when a sweep can immediately follow.
        boolean canSweep = false;
        if (rescueEnabled && safe != null && wallet.safeUsable(safe, sets.allTracked, atRisk)) {
            vaultLocked = wallet.vaultLocked();
            if (vaultLocked) {
                if (!lockNotified) {
                    lockNotified = true;
                    cfg.log(Cfg.LVL_WARN, "Vault locked — rescue paused until you unlock it");
                }
            } else {
                lockNotified = false;
                canSweep = true;
            }
        }

        // The reuse database has flagged our own destination. Sweeping a coin that is ALREADY on an
        // exposed address still goes ahead (leaving it there is worse), but we stop pulling FRESH
        // at-risk stakes out of the covenant for it.
        final boolean badSafe = audit.safeIsReused(safe);

        // A RUNNING guardian that has lost the ability to rescue must say so, once. Everything below
        // still fails safe — an at-risk stake simply won't be collected — but silence here is the
        // same false assurance as running with no destination at all: the daemon appears healthy
        // while the protection it advertises is off. The vault-locked case above already works this
        // way; this covers the destination being cleared or flagged reused underneath us.
        // Condition mirrors what collectMatured is actually given below (canSweep && !badSafe), so the
        // warning can't disagree with the engine. vaultLocked is excluded — it has its own message.
        if (!(canSweep && !badSafe) && !vaultLocked) {
            if (!cannotRescueNotified) {
                cannotRescueNotified = true;
                final String why = badSafe
                        ? "your safe destination is flagged as a REUSED address"
                        : (safe == null || safe.isEmpty()
                                ? "no safe destination is set"
                                : "your safe destination is no longer usable");
                cfg.log(Cfg.LVL_ERROR, "Rescue is OFF — " + why + ". At-risk stakes will be left "
                        + "locked in the contract (safe, but not collected) until you fix it.");
                notifier.notify("Future Cash: rescue is off",
                        "The guardian can't move an at-risk stake to safety — " + why + ".");
            }
        } else {
            cannotRescueNotified = false;
        }

        collectMatured(tip, autoCollectSafe, rescueEnabled, verdict.ok, canSweep && !badSafe, sets.ownDefault);
        reconcileCollects(tip);
        detectCollectedForSweep(tip);
        if (canSweep) {
            detectStrandedForSweep();
            sweepPending(tip, safe);
        }
        reconcileSweeps(tip, safe);
    }

    /* ================= status snapshot for the UI ================= */

    public static final class Status {
        public long tip;
        public int watching, collecting, collectedSafe, collected, sweeping, swept, taken;
        public double readySafeN, readySafeA, readyRiskN, readyRiskA;
        public double pendRiskN, pendRiskSoonest, pendRiskA;
        public boolean vaultLocked;
        public int atRiskKnown;
    }

    public Status status(long tip) {
        final Status s = new Status();
        s.tip = tip;
        s.watching = store.countCollect(Store.DETECTED);
        s.collecting = store.countCollect(Store.COLLECT_POSTED) + store.countCollect(Store.COLLECT_POSTED_SAFE);
        s.collectedSafe = store.countCollect(Store.COLLECTED_SAFE);
        s.collected = store.countCollect(Store.COLLECTED) + store.countCollect(Store.SWEEP_QUEUED);
        s.sweeping = store.countSweep(Store.PENDING) + store.countSweep(Store.SWEEP_POSTED);
        s.swept = store.countSweep(Store.SWEPT);
        s.taken = store.countSweep(Store.TAKEN);
        final double[] safe = store.readyTotals(tip, false), risk = store.readyTotals(tip, true);
        s.readySafeN = safe[0]; s.readySafeA = safe[1];
        s.readyRiskN = risk[0]; s.readyRiskA = risk[1];
        final double[] pend = store.pendingAtRisk(tip);
        s.pendRiskN = pend[0]; s.pendRiskSoonest = pend[1]; s.pendRiskA = pend[2];
        s.vaultLocked = vaultLocked;
        s.atRiskKnown = atRisk.size();
        return s;
    }

    /* ================= UI-facing actions ================= */

    /** This node's stakes, for the Stakes tab. Relevant-only, same reasoning as detect(). */
    public List<Stake> listStakes() {
        final List<Stake> out = new ArrayList<>();
        final long tip = tip();
        final Wallet.AddressSets sets = wallet.addressSets();
        final JSONArray arr = Node.arr(node.cmd("coins relevant:true"));
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject c = arr.optJSONObject(i);
            if (c == null || c.optBoolean("spent", false)) continue;
            final String cid = c.optString("coinid", "");
            if (cid.isEmpty() || !seen.add(cid)) continue;
            if (!looksLikeFutureCash(c)) continue;
            final String payout = Node.state(c, FutureCashContract.ST_RECIPIENT);
            if (!Wallet.owns(sets.ownDefault, payout)) continue;

            final Stake st = new Stake();
            st.coinid = cid;
            st.payout = payout;
            st.amount = Node.outAmount(c);
            st.tokenid = c.optString("tokenid", "0x00");
            st.matureBlock = parseLong(Node.state(c, FutureCashContract.ST_FUTUREBLOCK));
            st.ready = st.matureBlock <= tip;
            st.atRisk = atRisk.contains(Wallet.canonAddr(payout));
            out.add(st);
        }
        // Lowest maturity block first, so anything ready to collect is at the top and the rest read
        // as a countdown. `coins relevant:true` returns them in the node's own storage order, which
        // is effectively arbitrary — a list you have to scan to find what needs attention.
        java.util.Collections.sort(out, (a, b) -> Long.compare(a.matureBlock, b.matureBlock));
        return out;
    }

    public static final class Stake {
        public String coinid, payout, amount, tokenid;
        public long matureBlock;
        public boolean ready, atRisk;
    }

    public long tip() {
        final JSONObject resp = Node.obj(node.cmd("block"));
        return resp == null ? 0 : parseLong(resp.optString("block", "0"));
    }

    /**
     * Lock funds until a future block, payable only to {@code payoutHex}.
     *
     * <p>The amount is shape-checked and then sent VERBATIM — the same decimal pattern classic 2.7.1
     * uses. Parsing it into a number instead would mangle a large amount into exponential notation,
     * which the node rejects.
     */
    public Wallet.Res createFutureCash(String amount, String tokenid, long blocks, String payoutHex) {
        final String amt = amount == null ? "" : amount.trim();
        if (!amt.matches("^[0-9]+(\\.[0-9]+)?$")) return Wallet.Res.err("Enter a positive amount.");
        try { if (Double.parseDouble(amt) <= 0) return Wallet.Res.err("Enter a positive amount."); }
        catch (Exception e) { return Wallet.Res.err("Enter a positive amount."); }
        if (blocks <= 0) return Wallet.Res.err("Pick a maturity time in the future.");
        if (!Node.validHexAddr(payoutHex)) return Wallet.Res.err("Payout address unresolved.");

        final String contract = wallet.contractAddress();
        if (contract == null) return Wallet.Res.err("Could not register the FutureCash contract.");
        final long tip = tip();
        if (tip == 0) return Wallet.Res.err("Could not read the chain tip.");

        final long matBlock = tip + blocks;
        final String tok = (tokenid == null || tokenid.isEmpty()) ? "0x00" : tokenid;
        final String state = "{\"0\":\"0xFF\",\"1\":\"" + matBlock + "\",\"2\":\"" + payoutHex
                + "\",\"3\":\"" + System.currentTimeMillis() + "\",\"4\":\"" + blocks + "\"}";
        final String cmd = "send amount:" + amt + " address:" + contract
                + ("0x00".equals(tok) ? "" : " tokenid:" + tok) + " state:" + state;

        final JSONObject r = node.cmd(cmd);
        if (Node.pending(r)) {
            return Wallet.Res.err("The embedded node did not authorize the request. Check its status before locking again.");
        }
        if (!Node.ok(r)) return Wallet.Res.err(Node.error(r));
        cfg.log(Cfg.LVL_INFO, "Locked " + amt + " · matures block " + matBlock + " | to:" + payoutHex);
        return Wallet.Res.ok(String.valueOf(matBlock));
    }

    /**
     * Collect ONE matured stake now. Same safety gates as the engine: refuses an at-risk payout
     * (the rescue path owns those) and refuses without a usable audit.
     */
    public Wallet.Res collectNow(String coinid) {
        if (!Node.validHexAddr(coinid)) return Wallet.Res.err("Not a valid coin id.");
        final long tip = tip();
        final JSONArray arr = Node.arr(node.cmd("coins coinid:" + coinid));
        JSONObject coin = null;
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject c = arr.optJSONObject(i);
            if (c != null && coinid.equals(c.optString("coinid", "")) && !c.optBoolean("spent", false)) {
                coin = c; break;
            }
        }
        if (coin == null) return Wallet.Res.err("Stake not found — it may already be collected.");
        if (!looksLikeFutureCash(coin)) return Wallet.Res.err("Not a FutureCash stake.");
        if (!matured(coin, tip)) {
            return Wallet.Res.err("Not matured yet — unlocks at block "
                    + Node.state(coin, FutureCashContract.ST_FUTUREBLOCK) + " (now " + tip + ").");
        }
        final String payout = Node.state(coin, FutureCashContract.ST_RECIPIENT);
        final Wallet.AddressSets sets = wallet.addressSets();
        if (!Wallet.owns(sets.ownDefault, payout)) {
            return Wallet.Res.err("That stake doesn't pay out to this node — it isn't yours to collect.");
        }
        if (atRisk.contains(Wallet.canonAddr(payout))) {
            return Wallet.Res.err("This stake pays out to an at-risk address — the Guardian rescues it "
                    + "with collect + sweep instead. Set up rescue on the Guardian tab.");
        }
        if (!audit.usable().ok) return Wallet.Res.err("Run a key audit first, then collect.");

        final Tx.Result res = Tx.collect(node, coinid, payout, Node.outAmount(coin),
                coin.optString("tokenid", "0x00"));
        if (!res.ok) return Wallet.Res.err(res.error);
        cfg.log(Cfg.LVL_INFO, "Manual collect posted: " + Node.outAmount(coin)
                + " | stake:" + coinid + " | to:" + payout);
        return Wallet.Res.ok(Node.outAmount(coin));
    }

    /** Refresh the in-memory at-risk set (after an audit completes on another thread). */
    public synchronized void reloadAtRisk() {
        atRisk.clear();
        final JSONArray flagged = cfg.atRisk();
        for (int i = 0; i < flagged.length(); i++) {
            final String a = flagged.optString(i, "");
            if (Node.validAddr(a)) atRisk.add(Wallet.canonAddr(a));
        }
    }

    public Set<String> atRiskSet() { return new HashSet<>(atRisk); }

    static long parseLong(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }
}
