package com.eurobuddha.statenft;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

public final class LocalStore {
    private static final String PREF = "statenft_suite";
    private static final String KEY_COLLECTIONS = "collections";
    private static final String KEY_PENDING = "pending";

    private LocalStore() {}
    // A separate, monotonic submission record survives stale row callbacks and recovery.
    static synchronized boolean createAttempted(Context ctx, JSONObject row) {
        return row.optInt("posted") != 0 || row.optInt("postedat") > 0
                || prefs(ctx).contains("create:" + row.optLong("id"));
    }
    static synchronized boolean reserveCreate(Context ctx, JSONObject row, int tip) {
        try {
            JSONObject current = null;
            JSONArray rows = burialCollections(ctx);
            for (int i=0;i<rows.length();i++) if (rows.getJSONObject(i).optLong("id")==row.optLong("id")) current=rows.getJSONObject(i);
            if (tip<=0 || row.optLong("id")<=0 || current==null || !"CREATE".equals(current.optString("phase"))
                    || !current.optString("tokenid").isEmpty() || createAttempted(ctx,current)
                    || !MintEngine.sameCreateJson(MintEngine.createIdentity(current),MintEngine.createIdentity(row))) return false;
            return prefs(ctx).edit().putString("create:"+row.optLong("id"),
                    new JSONObject().put("tip",tip).put("identity",MintEngine.createIdentity(row)).toString()).commit();
        } catch(Exception e) { return false; }
    }
    static synchronized boolean chooseCreate(Context ctx, long id, String tokenid) {
        try {
            JSONArray rows=burialCollections(ctx);
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);
                if(row.optLong("id")!=id || !"CREATE".equals(row.optString("phase")) || !row.optString("tokenid").isEmpty()) continue;
                JSONArray matches=row.optJSONArray("creatematches"); boolean found=false;
                if(matches!=null) for(int j=0;j<matches.length();j++) if(tokenid.equals(matches.optString(j))) found=true;
                if(!found) return false;
                row.put("createchoice",tokenid);
                boolean saved=prefs(ctx).edit().putString(KEY_COLLECTIONS,rows.toString()).commit();
                if(saved) sCollections=rows;
                return saved;
            }
        } catch(Exception ignored) { }
        return false;
    }
    static synchronized JSONArray createRows(Context ctx) { return burialCollections(ctx); }
    static synchronized boolean bindCreate(Context ctx, JSONObject expected, String tokenid) {
        try {
            JSONArray rows=burialCollections(ctx), next=new JSONArray(); JSONObject original=null;
            for(int i=0;i<rows.length();i++) {
                JSONObject r=rows.getJSONObject(i);
                if(r.optLong("id")==expected.optLong("id")) original=r;
            }
            if(original==null || !"CREATE".equals(original.optString("phase")) || !original.optString("tokenid").isEmpty()
                    || !original.optString("createchoice").equals(expected.optString("createchoice"))
                    || !MintEngine.sameCreateJson(MintEngine.createIdentity(original),MintEngine.createIdentity(expected))) return false;
            for(int i=0;i<rows.length();i++) {
                JSONObject r=rows.getJSONObject(i);
                if(r!=original && BurialEvidence.same(tokenid,r.optString("tokenid"))) {
                    // Gallery discovery is a view of the same token, not another mint job.
                    if(r.optInt("created",1)!=0 || !"DONE".equals(r.optString("phase")) || burialJournal(ctx,tokenid).length()>0) return false;
                    continue;
                }
                next.put(r);
            }
            original.put("tokenid",tokenid).put("phase","MOVE").put("error","");
            original.remove("creatematches"); original.remove("createchoice");
            boolean saved=prefs(ctx).edit().putString(KEY_COLLECTIONS,next.toString()).commit();
            if(saved) sCollections=next;
            return saved;
        } catch(Exception ignored) { return false; }
    }

    /** Strict saved-job reads, using the same trailing-data guard as TransactionEvidence. */
    static JSONObject airdropObject(String raw) {
        if (raw == null || raw.indexOf(0) >= 0) throw BurialEvidence.invalid();
        try {
            org.json.JSONTokener input = new org.json.JSONTokener(raw); Object value = input.nextValue();
            while (input.more()) {
                char c = input.next();
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') throw BurialEvidence.invalid();
            }
            if (!(value instanceof JSONObject)) throw BurialEvidence.invalid();
            return (JSONObject)value;
        } catch (org.json.JSONException e) { throw BurialEvidence.invalid(); }
    }
    static synchronized JSONObject airdropRecord(Context c, String key) {
        String raw = prefs(c).getString("draft_" + key, "");
        return raw == null || raw.isEmpty() ? null : airdropObject(raw);
    }
    /** Job and index commit together. A stale callback cannot resurrect or replace a job. */
    static synchronized boolean writeAirdrop(Context c, long id, JSONObject job, String expected, boolean clear) {
        if (id <= 0) return false;
        SharedPreferences p = prefs(c);
        String key = "draft_airdrop_" + id, indexKey = "draft_airdrop_index";
        String previous, previousIndex;
        try { previous = p.getString(key, ""); previousIndex = p.getString(indexKey, ""); }
        catch (RuntimeException invalid) { return false; }
        if (previous == null || previousIndex == null) return false;
        try {
            if (!clear) {
                JSONObject current = previous.isEmpty() ? null : airdropObject(previous);
                if (!java.util.Objects.equals(expected, current == null ? null : current.toString())) return false;
            }
            JSONObject index = previousIndex.isEmpty() ? new JSONObject() : airdropObject(previousIndex);
            SharedPreferences.Editor edit = p.edit();
            if (clear) { index.remove(String.valueOf(id)); edit.remove(key); }
            else { index.put(String.valueOf(id), 1); edit.putString(key, job.toString()); }
            if (edit.putString(indexKey, index.toString()).commit()) return true;
        } catch (Exception e) { /* Preserve the existing job on malformed storage or failed writes. */ }
        // SalonStore's rollback rule: commit(false) may still have changed Android's memory copy.
        try { p.edit().putString(key, previous).putString(indexKey, previousIndex).commit(); }
        catch (RuntimeException ignored) { }
        return false;
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
            boolean saved = prefs(ctx).edit().putString(KEY_COLLECTIONS, rows.toString()).commit();
            if (saved) sCollections = rows;
            return saved;
        } catch (Exception e) { return false; }
    }

    static synchronized boolean completeBurial(Context ctx, String tokenid, JSONArray expected, int tip) {
        if (tip <= 0) return false;
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
                    row.put("phase", "BURIED"); row.put("error", ""); row.put("buriedat", tip);
                }
            }
            boolean saved = prefs(ctx).edit().putString(KEY_COLLECTIONS, rows.toString())
                    .remove("burial:" + tokenid.toLowerCase(java.util.Locale.ROOT)).commit();
            if (saved) sCollections = rows;
            return saved;
        } catch (Exception e) { return false; }
    }



    private static JSONArray burialCollections(Context ctx) {
        try {
            JSONArray rows = new JSONArray(prefs(ctx).getString(KEY_COLLECTIONS, "[]"));
            for (int i = 0; i < rows.length(); i++) if (rows.optJSONObject(i) == null) throw BurialEvidence.invalid();
            return rows;
        } catch (Exception e) { throw BurialEvidence.invalid(); }
    }

    static synchronized boolean canDepart(Context ctx, String tokenid) {
        if (!Util.isValidHexId(tokenid)) return false;
        try {
            if (burialJournal(ctx, tokenid).length() > 0) return false;
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

    /* The collections blob embeds base64 images (hundreds of KB); parse it
     * once and keep the tree — callers hammer load()/findById() per card
     * and per slot on the UI thread. Single-process app, so the cache is
     * authoritative; save() keeps it in step. */
    private static JSONArray sCollections = null;

    public static synchronized JSONArray load(Context c) {
        if (sCollections != null) return sCollections;
        String raw = prefs(c).getString(KEY_COLLECTIONS, "[]");
        try { sCollections = new JSONArray(raw); } catch (Exception e) { sCollections = new JSONArray(); }
        return sCollections;
    }

    public static synchronized void save(Context c, JSONArray arr) {
        sCollections = arr;
        prefs(c).edit().putString(KEY_COLLECTIONS, arr.toString()).apply();
    }

    public static long nextId(Context c) {
        SharedPreferences p = prefs(c);
        long id = p.getLong("next_id", 1);
        p.edit().putLong("next_id", id + 1).apply();
        return id;
    }

    public static synchronized void upsert(Context c, JSONObject row) {
        // Cached rows are mutable. Read the persisted phase before accepting a late callback.
        JSONArray arr = burialCollections(c);
        long id = row.optLong("id", 0);
        String tokenid = row.optString("tokenid", "");
        boolean done = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject cur = arr.optJSONObject(i);
            if (cur != null && (cur.optLong("id", -1) == id
                    || (!tokenid.isEmpty() && tokenid.equals(cur.optString("tokenid", ""))))) {
                String phase = cur.optString("phase");
                if ("CREATE".equals(row.optString("phase"))) {
                    if (!cur.optString("tokenid").isEmpty()) { sCollections=arr; return; }
                    if (cur.has("createchoice")) {
                        try { row.put("createchoice",cur.get("createchoice")); } catch (Exception ignored) { }
                    }
                }
                if (("BURY".equals(phase) || "BURIED".equals(phase))
                        && !phase.equals(row.optString("phase"))) { sCollections = arr; return; }
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

    /** Drop every pending spend lock — used by mint recovery so a stalled row
     *  can re-attempt immediately instead of waiting out TTLs. */
    public static void clearPending(Context c) {
        prefs(c).edit().putString(KEY_PENDING, "{}").apply();
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

    /* ---- engine heartbeat (trust: a stalled engine must be visible) ---- */

    public static void recordHeartbeat(Context c, String msg) {
        prefs(c).edit()
                .putLong("engine_tick", System.currentTimeMillis())
                .putString("engine_msg", msg == null ? "" : msg)
                .apply();
    }

    public static long lastHeartbeat(Context c) {
        return prefs(c).getLong("engine_tick", 0);
    }

    /* ---- engine activity log ----
     * Ring buffer behind the tappable pane above the mint rail: every phase
     * summary, transition and error lands here so "is anything happening?"
     * is answerable in-app, not just over adb. */

    public static synchronized void logEvent(Context c, String msg) {
        if (msg == null || msg.isEmpty()) return;
        android.util.Log.d("StateNFT", msg);
        try {
            if (msg.equals(prefs(c).getString("engine_log_last", ""))) return;  // no 25s tick spam
            JSONArray log;
            try { log = new JSONArray(prefs(c).getString("engine_log", "[]")); }
            catch (Exception e) { log = new JSONArray(); }
            JSONObject entry = new JSONObject();
            entry.put("t", System.currentTimeMillis());
            entry.put("m", msg);
            log.put(entry);
            while (log.length() > 250) log.remove(0);
            prefs(c).edit().putString("engine_log", log.toString())
                    .putString("engine_log_last", msg).apply();
        } catch (Exception ignored) {}
    }

    public static JSONArray engineLog(Context c) {
        try { return new JSONArray(prefs(c).getString("engine_log", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    public static void clearEngineLog(Context c) {
        prefs(c).edit().remove("engine_log").remove("engine_log_last").apply();
    }

    /* ---- wizard drafts (nft / token / collection) ---- */

    public static void saveDraft(Context c, String key, JSONObject draft) {
        prefs(c).edit().putString("draft_" + key, draft == null ? "" : draft.toString()).apply();
    }

    public static JSONObject loadDraft(Context c, String key) {
        String raw = prefs(c).getString("draft_" + key, "");
        if (raw == null || raw.isEmpty()) return null;
        try { return new JSONObject(raw); } catch (Exception e) { return null; }
    }

    public static void clearDraft(Context c, String key) {
        prefs(c).edit().remove("draft_" + key).apply();
    }

    public static JSONObject findByTokenid(Context c, String tokenid) {
        if (tokenid == null || tokenid.isEmpty()) return null;
        JSONArray arr = load(c);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r != null && tokenid.equals(r.optString("tokenid", ""))) return r;
        }
        return null;
    }

    public static synchronized void removeById(Context c, long id) {
        JSONArray arr = load(c);
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null || r.optLong("id", -1) == id) continue;
            next.put(r);
        }
        save(c, next);
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

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
