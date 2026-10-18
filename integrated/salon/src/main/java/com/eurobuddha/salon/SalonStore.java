package com.eurobuddha.salon;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Local state for The Salon: the user's own identity + profile draft, kept in a
 * private SharedPreferences file (backup-excluded, like HostingStore). The
 * authoritative identity is the on-chain signed token; the profile CONTENT is the
 * hosted profile.json. This is just the device-side cache/draft of both.
 *
 * identity JSON shape:
 *   { handle, name, bio, avatar, banner, tokenid, profileUrl, webvalidate }
 */
final class SalonStore {

    private static final String PREFS = "salon_identity";
    private static final String KEY = "me";

    private SalonStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Strict read for key operations: corrupt JSON is not an empty identity. */
    static synchronized JSONObject identityRecord(Context c) {
        try {
            String value = prefs(c).getString(KEY, "");
            JSONObject record = value.isEmpty() ? new JSONObject() : new JSONObject(value);
            for (String key : new String[]{"msgseed", "msgpk"})
                if (record.has(key) && !(record.get(key) instanceof String)) throw new SalonComms.IdentityUnavailable();
            return record;
        } catch (Exception unavailable) { throw new SalonComms.IdentityUnavailable(); }
    }

    /** Like KeyVault.saveKey, report success only after synchronous persistence. */
    static synchronized boolean writeIdentity(Context c, String wrapped, String publicId) {
        SharedPreferences prefs = prefs(c);
        JSONObject current = identityRecord(c);
        String previous = prefs.getString(KEY, "");
        try {
            current.put("msgseed", wrapped); current.put("msgpk", publicId);
            if (prefs.edit().putString(KEY, current.toString()).commit()) return true;
        } catch (Exception unavailable) { /* Restore the in-memory prefs snapshot too. */ }
        // SharedPreferences updates memory even when commit reports a disk failure.
        try { prefs.edit().putString(KEY, previous).commit(); }
        catch (Exception unavailable) { /* Do not activate an identity after storage failure. */ }
        return false;
    }

    static JSONObject me(Context c) {
        try {
            String s = prefs(c).getString(KEY, "");
            return s.isEmpty() ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) { return new JSONObject(); }
    }

    static synchronized void save(Context c, JSONObject me) {
        try {
            JSONObject current = identityRecord(c);
            // A stale profile draft or hosted profile must never replace local messaging keys.
            for (String key : new String[]{"msgseed", "msgpk"}) {
                if (current.has(key)) me.put(key, current.get(key));
                else me.remove(key);
            }
            prefs(c).edit().putString(KEY, me.toString()).apply();
        } catch (Exception unavailable) {
            // Preserve malformed storage; identity operations report a recoverable error.
        }
    }

    static boolean hasIdentity(Context c) {
        JSONObject m = me(c);
        return m.optString("tokenid", "").length() > 0 && m.optString("handle", "").length() > 0;
    }

    static String get(Context c, String k) { return me(c).optString(k, ""); }

    static synchronized void put(Context c, String k, String v) {
        if ("msgseed".equals(k) || "msgpk".equals(k)) return;
        JSONObject m = me(c);
        try { m.put(k, v == null ? "" : v); } catch (Exception ignored) {}
        save(c, m);
    }

    /* ---- rich content arrays (links / gallery / posts) live inside "me" ---- */

    static JSONArray arr(Context c, String k) {
        JSONArray a = me(c).optJSONArray(k);
        return a == null ? new JSONArray() : a;
    }

    static synchronized void setArr(Context c, String k, JSONArray a) {
        JSONObject m = me(c);
        try { m.put(k, a == null ? new JSONArray() : a); } catch (Exception ignored) {}
        save(c, m);
    }

    /* ---- follows: a device-side list of {tokenid, handle, url} ---- */

    static JSONArray follows(Context c) {
        return arr(c, "follows");
    }

    static boolean isFollowing(Context c, String tokenid) {
        JSONArray f = follows(c);
        for (int i = 0; i < f.length(); i++) {
            JSONObject o = f.optJSONObject(i);
            if (o != null && tokenid.equals(o.optString("tokenid"))) return true;
        }
        return false;
    }

    static synchronized void follow(Context c, String tokenid, String handle, String url) {
        if (tokenid == null || tokenid.isEmpty() || isFollowing(c, tokenid)) return;
        JSONArray f = follows(c);
        try {
            JSONObject o = new JSONObject();
            o.put("tokenid", tokenid); o.put("handle", handle == null ? "" : handle); o.put("url", url == null ? "" : url);
            f.put(o);
        } catch (Exception ignored) {}
        setArr(c, "follows", f);
    }

    static synchronized void unfollow(Context c, String tokenid) {
        JSONArray f = follows(c), next = new JSONArray();
        for (int i = 0; i < f.length(); i++) {
            JSONObject o = f.optJSONObject(i);
            if (o != null && !tokenid.equals(o.optString("tokenid"))) next.put(o);
        }
        setArr(c, "follows", next);
    }
}
