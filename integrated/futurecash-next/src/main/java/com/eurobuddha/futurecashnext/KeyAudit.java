package com.eurobuddha.futurecashnext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The audit itself: joins the node's local key counters against the archive spend index and the
 * known-reuse database, and decides the verdict.
 *
 * <p>Pure Java by design — no Android, no JSON, no network — so every branch is unit-testable on
 * the JVM. Port of {@code joinAndRender} and {@code renderVerdict}
 * ({@code mds/keyuses/minidapp/index.html:219-327}).
 *
 * <p>The two detection signals are deliberately kept apart because they carry different confidence:
 * <ul>
 *   <li><b>heuristic</b> — {@code sigs > local}: the chain proves more signatures than the node's
 *       counter, i.e. the node is re-issuing leaves it already spent. Derived from spend-blocks,
 *       which under-counts (two txns from one key in one block collapse to one), so it errs toward
 *       false negatives.</li>
 *   <li><b>definitive</b> — the address appears in the witness-confirmed reuse database, where one
 *       leaf public key was seen signing more than one distinct transaction.</li>
 * </ul>
 * Do not collapse them into a single boolean.
 */
public final class KeyAudit {

    /** Safety margin added to the recommended keyuses (index.html:155). */
    public static final int KEYUSES_MARGIN = 256;

    /** Reuse depth above which the verdict escalates from amber to red (index.html:270, :333). */
    public static final int REUSE_RED_ABOVE = 3;

    /** How close to maxuses a key may get before we warn about exhaustion. See Result.exhaustion. */
    public static final int EXHAUSTION_SLACK = 1000;

    private KeyAudit() {}

    // Shapes and capacities match PandaPools KeyUses.capacityOf and the node's
    // TreeKey.createDefault. Inspect each row; never choose one family for the wallet.
    public enum Family { CLASSIC, BLOCK, UNKNOWN }

    /* ---------- inputs ---------- */

    /** One row of {@code keys action:list}. */
    public static final class LocalKey {
        public final String publicKey;   // "0x…"
        public final int uses;
        public final int maxUses;
        public final Family family;
        public final boolean valid;
        public final boolean shapeValid;

        public LocalKey(String publicKey, int uses, int maxUses) {
            this(publicKey, uses, maxUses, 0, 0);
        }

        public LocalKey(String publicKey, int uses, int maxUses, int size, int depth) {
            this.publicKey = publicKey;
            this.uses = uses;
            // Older replies omit shape: accept only the explicit legacy capacity.
            if ((size == 64 && depth == 3) || (size == 0 && depth == 0 && maxUses == 262144)) {
                family = Family.CLASSIC;
                this.maxUses = 262144;
            } else if (size == 128 && depth == 4) {
                family = Family.BLOCK;
                this.maxUses = 268435456;
            } else {
                family = Family.UNKNOWN;
                this.maxUses = 0;
            }
            shapeValid = family != Family.UNKNOWN && (maxUses == 0 || maxUses == this.maxUses);
            valid = shapeValid && uses >= 0;
        }
    }

    /** One row from GET /keyaudit — the archive spend index. */
    public static final class Usage {
        public final String publicKey;    // "0x…" as echoed back
        public final String address;      // 0x address the SERVER derived (used to cross-check ours)
        public final long spendBlocks;    // ~= key uses (one signature per spend-block)
        public final long spentCoins;     // upper bound: one signature can spend many coins

        public Usage(String publicKey, String address, long spendBlocks, long spentCoins) {
            this.publicKey = publicKey;
            this.address = address;
            this.spendBlocks = spendBlocks;
            this.spentCoins = spentCoins;
        }
    }

    /** One row from GET /reuse — the witness-confirmed known-reuse database. */
    public static final class Reuse {
        public final String address;     // 0x
        /** The same address in Mx form, when the server echoed it. Needed because this app also asks
         *  about its RESCUE DESTINATION, which the user supplies as an Mx and whose hex we never see
         *  (it belongs to another wallet) — so an Mx-keyed match is the only way to find its verdict. */
        public final String miniaddress;
        public final boolean reused;
        public final long reuseCount;    // worst-leaf reuse depth

        public Reuse(String address, boolean reused, long reuseCount) {
            this(address, "", reused, reuseCount);
        }

        public Reuse(String address, String miniaddress, boolean reused, long reuseCount) {
            this.address = address;
            this.miniaddress = miniaddress == null ? "" : miniaddress;
            this.reused = reused;
            this.reuseCount = reuseCount;
        }

        /** Does this verdict describe the given address, in either spelling? */
        public boolean matches(String addr) {
            if (addr == null || addr.isEmpty()) return false;
            return addr.equalsIgnoreCase(address) || addr.equals(miniaddress);
        }
    }

    /* ---------- outputs ---------- */

    public enum Verdict {
        /** Node counter is at or ahead of the chain for every key. */
        OK,
        /** Chain shows more signatures than the node's counter on at least one key. */
        RISK_HEURISTIC,
        /** Witness-confirmed reuse, worst depth <= REUSE_RED_ABOVE. */
        REUSE_WARN,
        /** Witness-confirmed reuse, worst depth > REUSE_RED_ABOVE. */
        REUSE_RISK
    }

    public enum Status { OK, AT_RISK, REUSED, UNKNOWN }

    /** One table row. */
    public static final class Row {
        public final int index;          // 1-based, matching the node's key order
        public final String address;     // Mx (locally derived), or a fallback string
        public final int local;          // classic counter or block-tree leaf position
        public final Family family;
        public final boolean checked;
        /** Both public endpoints returned matching evidence, independent of a local counter. */
        public final boolean publicChecked;
        public final long sigs;          // on-chain signatures (spend blocks)
        public final long coins;         // spent coins (upper bound)
        public final boolean risk;       // heuristic: sigs > local
        public final boolean reused;     // definitive: in the known-reuse database
        public final long reuseCount;

        Row(int index, String address, int local, long sigs, long coins,
            boolean risk, boolean reused, long reuseCount, Family family, boolean checked, boolean publicChecked) {
            this.family = family;
            this.checked = checked;
            this.publicChecked = publicChecked;
            this.index = index;
            this.address = address;
            this.local = local;
            this.sigs = sigs;
            this.coins = coins;
            this.risk = risk;
            this.reused = reused;
            this.reuseCount = reuseCount;
        }

        public Status status() {
            if (reused) return Status.REUSED;
            if (risk) return Status.AT_RISK;
            return checked ? Status.OK : Status.UNKNOWN;
        }
    }

    public static final class Result {
        public final List<Row> rows;
        public final Verdict verdict;
        public final long worstReuse;        // worst reuse depth seen, for the verdict copy
        /** Classic-only recovery estimate including the margin; zero if no classic keys were audited. */
        public final long recommendedKeyUses;
        /** A key whose locally derived address disagreed with the server's — results untrustworthy. */
        public final boolean derivationMismatch;
        /** A key within EXHAUSTION_SLACK of maxuses; on exhaustion the node silently resets to 0. */
        public final boolean exhaustion;
        /** Every requested key has a usable shape and explicit results from both endpoints. */
        public final boolean complete;
        public final boolean publicComplete;
        public final int classicKeys, blockKeys, unknownKeys;

        Result(List<Row> rows, Verdict verdict, long worstReuse, long recommendedKeyUses,
               boolean derivationMismatch, boolean exhaustion) {
            int classic = 0, block = 0, unknown = 0;
            boolean all = !rows.isEmpty() && !derivationMismatch;
            boolean allPublic = all;
            for (Row row : rows) {
                if (row.family == Family.CLASSIC) classic++;
                else if (row.family == Family.BLOCK) block++;
                else unknown++;
                all &= row.checked;
                allPublic &= row.publicChecked;
            }
            classicKeys = classic; blockKeys = block; unknownKeys = unknown;
            complete = all;
            publicComplete = allPublic;
            this.rows = rows;
            this.verdict = verdict;
            this.worstReuse = worstReuse;
            this.recommendedKeyUses = recommendedKeyUses;
            this.derivationMismatch = derivationMismatch;
            this.exhaustion = exhaustion;
        }
    }

    /* ---------- the join ---------- */

    /**
     * Join the node's keys against the backend results.
     *
     * @param localKeys  from {@code keys action:list}, in node order
     * @param usage      /keyaudit rows (missing entries make the audit incomplete)
     * @param reuse      /reuse rows (may be empty)
     * @param derived    locally derived addresses, parallel to localKeys (entry may be null if the
     *                   public key was malformed)
     */
    public static Result join(List<LocalKey> localKeys,
                              List<Usage> usage,
                              List<Reuse> reuse,
                              List<MinimaAddress.Result> derived) {

        final Map<String, Usage> byPk = new HashMap<>();
        if (usage != null) {
            for (Usage u : usage) {
                if (u != null && u.publicKey != null) byPk.put(upper(u.publicKey), u);
            }
        }
        final Map<String, Reuse> byAddr = new HashMap<>();
        if (reuse != null) {
            for (Reuse r : reuse) {
                if (r != null && r.address != null) byAddr.put(upper(r.address), r);
            }
        }

        final List<Row> rows = new ArrayList<>(localKeys.size());
        boolean anyRisk = false, anyReuse = false, mismatch = false, exhaustion = false;
        long recommended = 0, worstReuse = 0;

        for (int i = 0; i < localKeys.size(); i++) {
            final LocalKey k = localKeys.get(i);
            final int local = k.uses;
            final Usage u = k.publicKey == null ? null : byPk.get(upper(k.publicKey));
            final MinimaAddress.Result d = (derived != null && i < derived.size()) ? derived.get(i) : null;

            // Cross-check our derivation against the server's. A silent disagreement here would
            // mean we queried /reuse for the wrong address and got back a reassuring "not reused".
            if (d != null && u != null && u.address != null
                    && !upper(d.hex).equals(upper(u.address))) {
                mismatch = true;
            }

            if (k.maxUses > 0 && local > k.maxUses - EXHAUSTION_SLACK) exhaustion = true;

            final long sigs = u == null ? 0 : u.spendBlocks;
            final long coins = u == null ? 0 : u.spentCoins;

            // The /reuse lookup keys on the address we actually sent — our own derivation, falling
            // back to the server's echo if the key was malformed and we could not derive one.
            final String lookup = d != null ? d.hex : (u != null ? u.address : null);
            final Reuse r = lookup == null ? null : byAddr.get(upper(lookup));
            final boolean confirmedReuse = r != null && r.reused;
            if (confirmedReuse) {
                anyReuse = true;
                worstReuse = Math.max(worstReuse, r.reuseCount);
            }

            // Spend counts estimate a classic counter. They are not comparable
            // to a block-tree leaf position; witness-confirmed reuse applies to both.
            final boolean risk = k.valid && k.family == Family.CLASSIC && sigs > local;
            if (risk) anyRisk = true;

            if (k.family == Family.CLASSIC && k.valid)
                recommended = Math.max(recommended, Math.max(sigs, local));

            final String display = d != null ? d.mx : "(address unavailable)";
            rows.add(new Row(i + 1, display, local, sigs, coins,
                    risk, confirmedReuse, r == null ? 0 : r.reuseCount, k.family,
                    k.valid && d != null && u != null && r != null
                            && d.hex.equalsIgnoreCase(u.address),
                    k.shapeValid && d != null && u != null && r != null
                            && d.hex.equalsIgnoreCase(u.address)));
        }

        // Precedence: confirmed reuse beats the heuristic (index.html:269-326).
        final Verdict verdict;
        if (anyReuse) {
            verdict = worstReuse > REUSE_RED_ABOVE ? Verdict.REUSE_RISK : Verdict.REUSE_WARN;
        } else if (anyRisk) {
            verdict = Verdict.RISK_HEURISTIC;
        } else {
            verdict = Verdict.OK;
        }

        boolean hasClassic = false;
        for (LocalKey key : localKeys) hasClassic |= key.family == Family.CLASSIC;
        return new Result(rows, verdict, worstReuse, hasClassic ? recommended + KEYUSES_MARGIN : 0,
                mismatch, exhaustion);
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(java.util.Locale.ROOT);
    }
}
