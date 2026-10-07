package com.eurobuddha.futurecashnext;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the key audit and turns it into the verdict the guardian acts on.
 *
 * <p>The classification is {@link KeyAudit} verbatim from the KeyUses app (with its unit tests).
 * What this adds is the two things the guardian needs and a pure auditor doesn't:
 *
 * <ol>
 *   <li>the set of AT-RISK PAYOUT ADDRESSES, stored where the engine reads it, and</li>
 *   <li>a check on <b>where we send rescued money</b>. Sweeping onto another exposed key defeats the
 *       whole exercise, so our own destination goes into the same /reuse batch at no extra cost.</li>
 * </ol>
 */
public final class AuditRunner {

    public interface Cb {
        void onDone(KeyAudit.Result result, long archiveTip);
        void onError(String message);
    }

    private final Node node;
    private final Cfg cfg;
    private final Audit audit;
    private final AuditApi api;

    /** @param activity may be null — the guardian runs this headless. */
    public AuditRunner(Node node, Cfg cfg, Activity activity) {
        this.node = node;
        this.cfg = cfg;
        this.audit = new Audit(cfg, node);
        this.api = activity != null ? new AuditApi(activity) : new AuditApi();
    }

    public void onDestroy() { api.onDestroy(); }

    /** Worker-thread entry: reads keys over IPC (blocking), then runs the HTTP audit. */
    public void run(final Cb cb) {
        final JSONObject r = node.cmd("keys action:list");
        final JSONObject resp = Node.obj(r);
        final JSONArray keys = resp == null ? null : resp.optJSONArray("keys");
        if (keys == null || keys.length() == 0) {
            // A LOCAL failure, and it must not be reported as a network one — the fix is enabling the
            // app in Minima Core, not checking the internet.
            final String m = "Couldn't read keys from the embedded node. Check Minima Core’s status.";
            audit.recordFailure(m);
            cb.onError(m);
            return;
        }

        final List<KeyAudit.LocalKey> local = new ArrayList<>();
        final List<MinimaAddress.Result> derived = new ArrayList<>();
        final List<String> pubs = new ArrayList<>();
        final List<String> addrs = new ArrayList<>();

        for (int i = 0; i < keys.length(); i++) {
            final JSONObject k = keys.optJSONObject(i);
            if (k == null) continue;
            final String pk = k.optString("publickey", "");
            local.add(new KeyAudit.LocalKey(pk, k.optInt("uses", 0), k.optInt("maxuses", 0)));
            final MinimaAddress.Result d = MinimaAddress.fromPublicKey(pk);
            derived.add(d);
            if (!pk.isEmpty()) pubs.add(pk);
            if (d != null) addrs.add(d.hex);
        }
        if (local.isEmpty()) {
            audit.recordFailure("This node reported no keys.");
            cb.onError("This node reported no keys.");
            return;
        }

        // The addresses we SEND rescued money to ride along in the same /reuse batch — no extra
        // round trip, and it means a destination that was clean when chosen gets re-checked forever.
        final List<String> dests = destinations();
        final List<String> reuseTargets = new ArrayList<>(addrs);
        reuseTargets.addAll(dests);

        api.audit(pubs, reuseTargets, new AuditApi.Cb() {
            @Override
            public void onResult(List<KeyAudit.Usage> usage, List<KeyAudit.Reuse> reuse, long tip) {
                final KeyAudit.Result result = KeyAudit.join(local, usage, reuse, derived);

                // At-risk payout addresses, in BOTH spellings so a hex or an Mx payout matches.
                final List<String> atRisk = new ArrayList<>();
                for (int i = 0; i < result.rows.size(); i++) {
                    final KeyAudit.Row row = result.rows.get(i);
                    if (!row.risk && !row.reused) continue;
                    final MinimaAddress.Result d = i < derived.size() ? derived.get(i) : null;
                    if (d != null) {
                        atRisk.add(d.hex);
                        atRisk.add(d.mx);
                    }
                }

                // Is a destination we send rescued money to itself known-reused?
                String badAddr = null;
                long badCount = 0;
                for (String dest : dests) {
                    for (KeyAudit.Reuse ru : reuse) {
                        if (ru.reused && ru.matches(dest)) { badAddr = dest; badCount = ru.reuseCount; break; }
                    }
                    if (badAddr != null) break;
                }

                // A COMPLETE audit means both endpoints answered. A partial one can only
                // UNDER-report reuse, so it must never license bare-collecting a "safe" coin.
                final boolean complete = !usage.isEmpty() && !reuse.isEmpty();

                audit.store(atRisk, keys, complete, badAddr, badCount);
                cfg.set(Cfg.AUDIT_LAST_ERR, "");
                cb.onDone(result, tip);
            }

            @Override public void onError(String message) {
                // Deliberately does NOT clear the stored verdict: it expires on its own clock, backed
                // by the offline uses-drift re-confirmation. A single unreachable request must not be
                // able to stop the guardian collecting.
                final String m = "Couldn't reach the audit service (" + message + ").";
                audit.recordFailure(m);
                cb.onError(m);
            }
        });
    }

    /** The addresses this app sends money TO: the configured safe and the minted clean address. */
    private List<String> destinations() {
        final List<String> out = new ArrayList<>();
        final String safe = cfg.get(Cfg.SAFE_ADDRESS, "");
        if (safe != null && !safe.isEmpty()) out.add(safe);
        final String[] clean = cfg.cleanAddr();
        if (clean != null && !clean[0].equals(safe)) out.add(clean[1]);   // hex form for the lookup
        return out;
    }

    /**
     * One-off reuse lookup for a single address the user is about to trust with money.
     *
     * @return reuse count, 0 for clean, or -1 when the database couldn't be reached. -1 is NOT a
     *         refusal: blocking someone from configuring a rescue because the network is down is
     *         exactly the failure this design avoids elsewhere.
     */
    public void checkOne(final String addr, final java.util.function.LongConsumer cb) {
        final List<String> one = new ArrayList<>();
        one.add(addr);
        api.audit(new ArrayList<>(), one, new AuditApi.Cb() {
            @Override public void onResult(List<KeyAudit.Usage> u, List<KeyAudit.Reuse> reuse, long tip) {
                for (KeyAudit.Reuse ru : reuse) {
                    if (ru.matches(addr)) { cb.accept(ru.reused ? Math.max(1, ru.reuseCount) : 0); return; }
                }
                cb.accept(0);
            }
            @Override public void onError(String message) { cb.accept(-1); }
        });
    }
}
