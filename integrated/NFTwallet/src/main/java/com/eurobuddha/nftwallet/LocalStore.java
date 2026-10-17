package com.eurobuddha.nftwallet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

public final class LocalStore {
    private static final String PREF = "nftwallet_statenft";
    private static final String KEY_COLLECTIONS = "collections";
    private static final String KEY_PENDING = "pending";

    private LocalStore() {}

    public static JSONArray load(Context c) {
        String raw = prefs(c).getString(KEY_COLLECTIONS, "[]");
        try { return new JSONArray(raw); } catch (Exception e) { return new JSONArray(); }
    }

    public static void save(Context c, JSONArray arr) {
        prefs(c).edit().putString(KEY_COLLECTIONS, arr.toString()).apply();
    }

    public static long nextId(Context c) {
        SharedPreferences p = prefs(c);
        long id = p.getLong("next_id", 1);
        p.edit().putLong("next_id", id + 1).apply();
        return id;
    }

    public static void upsert(Context c, JSONObject row) {
        JSONArray arr = load(c);
        long id = row.optLong("id", 0);
        String tokenid = row.optString("tokenid", "");
        boolean done = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject cur = arr.optJSONObject(i);
            if (cur != null && (cur.optLong("id", -1) == id
                    || (!tokenid.isEmpty() && tokenid.equals(cur.optString("tokenid", ""))))) {
                // A delayed mint callback cannot undo a user-confirmed burial pause/completion.
                String phase = cur.optString("phase");
                if (("BURY".equals(phase) || "BURIED".equals(phase))
                        && !phase.equals(row.optString("phase"))) return;
                try { arr.put(i, row); } catch (Exception ignored) {}
                done = true;
                break;
            }
        }
        if (!done) arr.put(row);
        save(c, arr);
    }

    public static void setPending(Context c, String coinid, int tip) {
        try {
            JSONObject p = pending(c);
            p.put(coinid, tip);
            prefs(c).edit().putString(KEY_PENDING, p.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static boolean pendingOk(Context c, String coinid, int tip) {
        JSONObject p = pending(c);
        if (!p.has(coinid)) return true;
        return tip - p.optInt(coinid, 0) >= 6;
    }

    public static void prunePending(Context c, int tip) {
        JSONObject p = pending(c);
        JSONArray names = p.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String k = names.optString(i);
            if (tip - p.optInt(k, 0) >= 6) p.remove(k);
        }
        prefs(c).edit().putString(KEY_PENDING, p.toString()).apply();
    }

    public static JSONObject findById(Context c, long id) {
        JSONArray arr = load(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r != null && r.optLong("id", -1) == id) return r;
        }
        return null;
    }

    private static JSONObject pending(Context c) {
        try { return new JSONObject(prefs(c).getString(KEY_PENDING, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    /** Public input/output identifiers and state hashes; no seed, key, signature or artwork is stored. */
    static synchronized JSONArray burialJournal(Context ctx, String tokenid) {
        if (!Util.isValidHexId(tokenid)) throw BurialEvidence.invalid();
        String raw = prefs(ctx).getString("burial:" + tokenid.toLowerCase(java.util.Locale.ROOT), "[]");
        try {
            JSONArray entries = new JSONArray(raw);
            BurialEvidence.journal(entries, tokenid);
            return entries;
        } catch (Exception e) { throw BurialEvidence.invalid(); }
    }

    static synchronized boolean saveBurialJournal(Context ctx, String tokenid, JSONArray entries) {
        if (!Util.isValidHexId(tokenid)) throw BurialEvidence.invalid();
        BurialEvidence.journal(entries, tokenid);
        return prefs(ctx).edit().putString("burial:" + tokenid.toLowerCase(java.util.Locale.ROOT), entries.toString()).commit();
    }

    static synchronized boolean pauseForBurial(Context ctx, String tokenid) {
        if (!Util.isValidHexId(tokenid)) throw BurialEvidence.invalid();
        JSONArray rows = burialCollections(ctx);
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null && BurialEvidence.same(tokenid, row.optString("tokenid")) && !"BURIED".equals(row.optString("phase"))) {
                    row.put("phase", "BURY");
                }
            }
            return prefs(ctx).edit().putString(KEY_COLLECTIONS, rows.toString()).commit();
        } catch (Exception e) { return false; }
    }

    static synchronized boolean completeBurial(Context ctx, String tokenid, JSONArray expected) {
        if (expected == null || expected.length() == 0 || !BurialEvidence.sameJournal(expected, burialJournal(ctx, tokenid), tokenid)) return false;
        for (int i = 0; i < expected.length(); i++) {
            try { BurialEvidence.id(expected.optJSONObject(i), "output"); }
            catch (RuntimeException e) { return false; }
        }
        JSONArray rows = burialCollections(ctx);
        try {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row != null && BurialEvidence.same(tokenid, row.optString("tokenid")) && "BURY".equals(row.optString("phase"))) {
                    row.put("phase", "BURIED"); row.put("error", "");
                }
            }
            return prefs(ctx).edit().putString(KEY_COLLECTIONS, rows.toString())
                    .remove("burial:" + tokenid.toLowerCase(java.util.Locale.ROOT)).commit();
        } catch (Exception e) { return false; }
    }

    private static JSONArray burialCollections(Context ctx) {
        try {
            JSONArray rows = new JSONArray(prefs(ctx).getString(KEY_COLLECTIONS, "[]"));
            for (int i = 0; i < rows.length(); i++) if (rows.optJSONObject(i) == null) throw BurialEvidence.invalid();
            return rows;
        } catch (Exception e) { throw BurialEvidence.invalid(); }
    }


    /** An unfinished mint may reuse a departed item's index. No rows are changed by this check. */
    static synchronized boolean canDepart(Context ctx, String tokenid) {
        if (!Util.isValidHexId(tokenid)) return false;
        try {
            JSONArray rows = burialCollections(ctx);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (BurialEvidence.same(tokenid, row.optString("tokenid"))) {
                    String phase = row.optString("phase");
                    if (!"DONE".equals(phase) && !"BURIED".equals(phase)) return false;
                }
            }
            return true;
        } catch (Exception e) { return false; }
    }
    /** Called under MintDriver's operation lock: never races a live mint or burial pause. */
    static synchronized boolean saveMintImage(Context ctx, long rowId, int index, String image) {
        if (rowId < 1 || index < 1 || image == null || image.isEmpty()
                || image.length() > ImageTools.STATE_IMG_BUDGET) return false;
        try {
            JSONArray rows = burialCollections(ctx);
            JSONObject row = null;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject candidate = rows.getJSONObject(i);
                if (candidate.optLong("id", -1) == rowId) {
                    if (row != null) return false;
                    row = candidate;
                }
            }
            if (row == null || !"embed".equals(row.optString("mode")) || MintDriver.isStuck(row)) return false;
            String phase = row.optString("phase");
            if (!java.util.Arrays.asList("CREATE", "MOVE", "SPLIT", "STAMP", "NEEDIMAGES").contains(phase)) return false;
            int size = row.optInt("size", 0);
            JSONArray items = row.optJSONArray("items");
            if (size < 2 || size > 20 || index > size || items == null || items.length() != size) return false;
            boolean[] seen = new boolean[size + 1];
            JSONObject target = null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) return false;
                int idx = item.optInt("idx", 0);
                if (idx < 1 || idx > size || seen[idx]) return false;
                seen[idx] = true;
                if (idx == index) target = item;
            }
            // A delayed picker must never replace a newer image or sealed item identity.
            if (target == null || !target.optString("coinid", "").isEmpty()
                    || !target.optString("image", "").isEmpty()) return false;
            target.put("image", image);
            boolean ready = true;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (item.optString("coinid", "").isEmpty() && item.optString("image", "").isEmpty()) ready = false;
            }
            if (ready && "NEEDIMAGES".equals(phase)) { row.put("phase", "STAMP"); row.put("error", ""); }
            return prefs(ctx).edit().putString(KEY_COLLECTIONS, rows.toString()).commit();
        } catch (Exception e) { return false; }
    }

    /** Explicit retry confirmation applies only to the same still-parked create attempt. */
    static synchronized boolean retryMintCreate(Context ctx, long rowId) {
        if (rowId < 1) return false;
        try {
            JSONArray rows = burialCollections(ctx);
            JSONObject row = null;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject candidate = rows.getJSONObject(i);
                if (candidate.optLong("id", -1) == rowId) {
                    if (row != null) return false;
                    row = candidate;
                }
            }
            if (row == null || !"CREATE".equals(row.optString("phase"))
                    || row.optInt("createstuck", 0) != 1 || row.optInt("posted", 0) != 1
                    || !row.optString("tokenid", "").isEmpty() || MintDriver.isStuck(row)) return false;
            row.put("posted", 0); row.put("createstuck", 0); row.put("error", "");
            return prefs(ctx).edit().putString(KEY_COLLECTIONS, rows.toString()).commit();
        } catch (Exception e) { return false; }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
