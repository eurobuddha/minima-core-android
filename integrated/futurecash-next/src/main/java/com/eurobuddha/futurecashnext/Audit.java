package com.eurobuddha.futurecashnext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The key-reuse verdict: how it is produced, how long it may be trusted, and how it is stored.
 *
 * <p>The classification itself is {@link KeyAudit} (ported from the KeyUses app, with its unit
 * tests). This class owns the part the guardian depends on — <b>may we act on the verdict we have
 * right now?</b> — which is the rule that decides whether a matured stake gets collected onto its
 * payout address or left safely locked in the contract.
 */
public final class Audit {

    /** A bare collect onto a "safe" payout needs a successful audit no older than this. */
    public static final long FRESH_MS = 30 * 60_000L;

    /**
     * ...unless the verdict can be re-confirmed offline (see {@link #usable}), in which case it
     * stands until this hard cap. Bounds how long we can run on a local re-confirmation while the
     * audit service is unreachable.
     */
    public static final long MAX_MS = 24 * 3600_000L;

    public enum Mode { FRESH, RECONFIRMED, STALE, PARTIAL, NONE }

    public static final class Verdict {
        public final boolean ok;
        public final long at;
        public final Mode mode;
        Verdict(boolean ok, long at, Mode mode) { this.ok = ok; this.at = at; this.mode = mode; }
    }

    private final Cfg cfg;
    private final Node node;

    public Audit(Cfg cfg, Node node) { this.cfg = cfg; this.node = node; }

    /**
     * A canonical {@code publickey:uses} string for the node's whole key set. Sorted, so it is
     * stable, and any change — a key signing again, a key appearing, the set being rebuilt —
     * changes the string.
     */
    public static String usesFingerprint(JSONArray keys) {
        final List<String> parts = new ArrayList<>();
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                final JSONObject k = keys.optJSONObject(i);
                if (k == null) continue;
                final String pk = k.optString("publickey", "");
                if (pk.isEmpty()) continue;
                parts.add(pk.toUpperCase() + ":" + k.optInt("uses", 0));
            }
        }
        Collections.sort(parts);
        return String.join("|", parts);
    }

    /**
     * May the guardian act on the stored verdict?
     *
     * <p>Inside {@link #FRESH_MS} it simply stands. Past that we do NOT immediately refuse: re-read
     * this node's key counters (local, free, no network) and compare them to the fingerprint taken
     * when the audit ran. If nothing moved, the node has signed nothing since, so the verdict still
     * describes reality and stands until {@link #MAX_MS}. That is what keeps collecting working when
     * the audit service is unreachable — which, in the MiniDapp, used to stop it dead.
     *
     * <p>LIMIT, and it is a real one: this proves THIS NODE did not sign again. It cannot see brand
     * new on-chain reuse by someone else holding a copy of the seed; only the remote comparison
     * detects that. A fallback that buys time for the network to come back, never a replacement.
     */
    public Verdict usable() {
        final boolean aok = cfg.is(Cfg.AUDIT_OK, false);
        final long at = cfg.getLong(Cfg.AUDIT_AT);
        final long age = System.currentTimeMillis() - at;

        if (!aok || at == 0) return new Verdict(false, at, at != 0 ? Mode.PARTIAL : Mode.NONE);
        if (age < FRESH_MS) return new Verdict(true, at, Mode.FRESH);
        if (age >= MAX_MS)  return new Verdict(false, at, Mode.STALE);

        final String fp = cfg.get(Cfg.AUDIT_USES, "");
        if (fp == null || fp.isEmpty()) return new Verdict(false, at, Mode.STALE);

        final JSONObject r = node.cmd("keys action:list");
        if (!Node.ok(r)) return new Verdict(false, at, Mode.STALE);
        final JSONObject resp = Node.obj(r);
        final JSONArray keys = resp == null ? null : resp.optJSONArray("keys");
        if (keys == null) return new Verdict(false, at, Mode.STALE);

        return usesFingerprint(keys).equals(fp)
                ? new Verdict(true, at, Mode.RECONFIRMED)
                : new Verdict(false, at, Mode.STALE);
    }

    /**
     * Persist a completed audit.
     *
     * <p>WRITE ORDER MATTERS and is not arbitrary: at-risk set → fingerprint → destination verdict
     * → audit_ok → audit_at. {@code audit_at} goes LAST so freshness can never outrun the verdict it
     * describes, and the fingerprint goes BEFORE {@code audit_ok} so the offline re-confirm can never
     * test a fresh verdict against a stale snapshot. {@code audit_ok=1} ONLY for a COMPLETE audit
     * (both endpoints answered) — a partial one can only under-report reuse, so it must not license
     * bare-collecting a coin we are calling "safe".
     */
    public void store(List<String> atRiskAddrs, JSONArray localKeys, boolean complete,
                      String badDestAddr, long badDestCount) {
        final JSONArray arr = new JSONArray();
        for (String a : atRiskAddrs) {
            // Shape-check on the way in: these come from the audit service and end up in
            // `scripts address:…` lookups. See Node.validAddr.
            if (Node.validAddr(a)) arr.put(a);
        }
        cfg.set(Cfg.ATRISK_ADDRS, arr.toString());
        cfg.set(Cfg.AUDIT_USES, usesFingerprint(localKeys));
        setSafeReused(badDestAddr, badDestCount);
        cfg.setBool(Cfg.AUDIT_OK, complete);
        cfg.set(Cfg.AUDIT_AT, String.valueOf(System.currentTimeMillis()));

        // Log only when the count changes — the audit re-runs on a timer, and logging every run
        // floods the Activity pane and buries the real actions.
        final String prev = cfg.get(Cfg.AUDIT_LOGGED_N, "-1");
        if (!String.valueOf(arr.length()).equals(prev)) {
            cfg.set(Cfg.AUDIT_LOGGED_N, String.valueOf(arr.length()));
            cfg.log(Cfg.LVL_INFO, "Audit: " + arr.length() + " at-risk address(es)");
        }
    }

    /**
     * Record whether the destination we sweep rescued money TO is itself a known-reused address.
     *
     * <p>Rescuing onto another exposed key defeats the entire point, so this is ranked above ordinary
     * key-reuse risk in the UI: it breaks the escape route, not just the front door.
     */
    public void setSafeReused(String addr, long count) {
        final String prev = cfg.get(Cfg.SAFE_REUSED, "");
        String next = "";
        if (addr != null && !addr.isEmpty()) {
            try {
                final JSONObject o = new JSONObject();
                o.put("addr", addr);
                o.put("count", count);
                next = o.toString();
            } catch (Exception ignored) {}
        }
        if (next.equals(prev)) return;
        cfg.set(Cfg.SAFE_REUSED, next);
        if (next.isEmpty()) {
            cfg.log(Cfg.LVL_INFO, "Safe destination re-checked and clean");
        } else {
            cfg.log(Cfg.LVL_ERROR, "Your SAFE destination is a REUSED address (signed " + count
                    + "x) — rescuing there would move your money onto another exposed key. "
                    + "Set a fresh safe address. | address:" + addr);
        }
    }

    /** The flagged destination, or null. */
    public String[] safeReused() {
        final String raw = cfg.get(Cfg.SAFE_REUSED, "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            final JSONObject o = new JSONObject(raw);
            return new String[]{o.optString("addr", ""), String.valueOf(o.optLong("count", 0))};
        } catch (Exception e) { return null; }
    }

    /** Is our configured safe destination itself flagged reused? */
    public boolean safeIsReused(String safe) {
        final String[] bad = safeReused();
        return bad != null && safe != null && !bad[0].isEmpty()
                && Wallet.canonAddr(bad[0]).equals(Wallet.canonAddr(safe));
    }

    public void recordFailure(String message) {
        cfg.set(Cfg.AUDIT_LAST_ERR, message);
        // Deliberately does NOT touch audit_ok / audit_at. A failed audit must never throw away a
        // verdict that is still inside its freshness window — a single unreachable request used to
        // gate every collect behind "couldn't reach the audit service" even though the user's keys
        // were demonstrably fine.
    }
}
