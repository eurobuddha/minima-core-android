package com.eurobuddha.futurecashnext;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Combine the node and minimaWallet public snapshots without altering either counter store. */
final class AuditKeySet {
    private AuditKeySet() {}
    static JSONArray combine(JSONArray node, JSONArray wallet) {
        if (node == null || wallet == null) throw new IllegalStateException("Missing public key snapshot");
        try {
            JSONArray all = new JSONArray(node.toString());
            Map<String,JSONObject> known = new LinkedHashMap<>();
            for (int i=0;i<all.length();i++) {
                JSONObject row = all.getJSONObject(i);
                String pk = row.getString("publickey").toUpperCase(Locale.ROOT);
                if (known.put(pk,row) != null) throw new IllegalArgumentException("Duplicate node key");
            }
            for (int i=0;i<wallet.length();i++) {
                JSONObject row = new JSONObject(wallet.getJSONObject(i).toString());
                String pk = row.getString("publickey").toUpperCase(Locale.ROOT);
                JSONObject prior = known.get(pk);
                if (prior == null) { all.put(row); known.put(pk,row); continue; }
                KeyAudit.LocalKey a = key(prior), b = key(row);
                if (a.valid && b.valid && a.family != b.family)
                    throw new IllegalArgumentException("Conflicting key families for one public key");
                if (a.valid && b.valid && a.family == KeyAudit.Family.CLASSIC && b.family == KeyAudit.Family.CLASSIC) {
                    // NodeKeyUses reserves from this same MAX when both stores own the classic key.
                    prior.put("uses", Math.max(a.uses,b.uses));
                } else {
                    // Preserve invalid/untrusted evidence; it must not disappear through deduplication.
                    all.put(row);
                }
            }
            return all;
        } catch (Exception invalid) { throw new IllegalStateException("Invalid public key snapshot",invalid); }
    }
    private static KeyAudit.LocalKey key(JSONObject row) {
        return new KeyAudit.LocalKey(row.optString("publickey"), integer(row,"uses",-1),
                integer(row,"maxuses",0), integer(row,"size",0), integer(row,"depth",0));
    }
    private static int integer(JSONObject row,String name,int absent) {
        if (!row.has(name)) return absent;
        try { return new java.math.BigDecimal(row.get(name).toString()).intValueExact(); }
        catch (Exception invalid) { return -1; }
    }
}
