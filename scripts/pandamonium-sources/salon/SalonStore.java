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

    static SharedPreferences prefs(Context c) {
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

    static final String BACKUP_HOSTS = "hosting_profiles";

    static final class BackupStorageUnavailable extends IllegalStateException {
        BackupStorageUnavailable() { super("Salon account storage unavailable"); }
    }

    /** Same multi-key commit/rollback as Atelier LocalStore.writeAirdrop. */
    static synchronized boolean commitBackup(Context c, JSONObject account, JSONArray hosts) {
        SharedPreferences p = prefs(c);
        final String previous, previousHosts;
        try {
            previous = p.getString(KEY, null);
            previousHosts = p.getString(BACKUP_HOSTS, null);
        } catch (RuntimeException unavailable) { return false; }
        try {
            // The hosted credentials remain encrypted; both keys share one disk commit.
            if (p.edit().putString(KEY, account.toString())
                    .putString(BACKUP_HOSTS, hosts.toString()).commit()) return true;
        } catch (RuntimeException unavailable) { /* Restore Android's in-memory copy too. */ }
        try { p.edit().putString(KEY, previous).putString(BACKUP_HOSTS, previousHosts).commit(); }
        catch (RuntimeException unavailable) { /* Never activate the prepared key after a failed save. */ }
        return false;
    }

    /** Reuse publication compare-and-save for setup; preserve unrelated profile/key fields. */
    static synchronized boolean saveClaim(Context c, String expected, JSONObject changes) {
        final SharedPreferences p;
        final String previous;
        try { p = prefs(c); previous = p.getString(KEY, ""); }
        catch (RuntimeException unavailable) { return false; }
        try {
            JSONObject current = identityRecord(c);
            if (!expected.equals(publicationSnapshot(current))) return false;
            for (String key : new String[]{"handle", "name", "bio", "tokenid", "profileUrl", "claimPending"}) {
                if (changes.has(key)) {
                    if (changes.isNull(key)) current.remove(key); else current.put(key, changes.get(key));
                }
            }
            if (p.edit().putString(KEY, current.toString()).commit()) return true;
        } catch (Exception unavailable) { /* Keep the previous complete setup. */ }
        try { p.edit().putString(KEY, previous).commit(); } catch (Exception unavailable) { }
        return false;
    }

    /** Only fields that own or contribute to a publication, in a stable order. */
    static String publicationSnapshot(JSONObject record) {
        JSONArray result = new JSONArray();
        for (String key : new String[]{"tokenid", "handle", "msgpk", "msgseed", "profileUrl",
                "name", "bio", "about", "avatar", "banner", "webvalidate", "tipaddr",
                "links", "gallery", "posts", "nfts", "claimPending"}) result.put(record.opt(key));
        return result.toString();
    }

    /** Compare-and-save under the same lock as identity changes; preserve unrelated edits. */
    static synchronized boolean savePublication(Context c, String expected, String url, JSONArray nfts) {
        SharedPreferences prefs = prefs(c);
        String previous = prefs.getString(KEY, "");
        try {
            JSONObject current = identityRecord(c);
            if (!expected.equals(publicationSnapshot(current))) return false;
            current.put("profileUrl", url);
            if (nfts != null) current.put("nfts", nfts);
            if (prefs.edit().putString(KEY, current.toString()).commit()) return true;
        } catch (Exception unavailable) { /* Keep the prior durable publication. */ }
        try { prefs.edit().putString(KEY, previous).commit(); } catch (Exception unavailable) { }
        return false;
    }

    enum RestoreResult { APPLIED, EMPTY, CHANGED, INVALID, FAILED }

    /** Restore content only, atomically with the owner/draft check and durable save. */
    static synchronized RestoreResult restoreHosted(Context c, String expected, JSONObject hosted) {
        SharedPreferences prefs = prefs(c);
        String previous = prefs.getString(KEY, "");
        try {
            JSONObject current = identityRecord(c);
            if (!expected.equals(publicationSnapshot(current))) return RestoreResult.CHANGED;
            // Initial publication can precede minting, so a missing/empty token id is valid.
            for (String key : new String[]{"handle", "tokenid"}) {
                Object value = hosted.opt(key);
                if (value != null && (!(value instanceof String)
                        || (!((String) value).isEmpty() && !value.equals(current.optString(key)))))
                    return RestoreResult.INVALID;
            }
            boolean got = false;
            for (String key : new String[]{"name", "bio", "about", "avatar", "banner", "webvalidate", "tipaddr"}) {
                Object value = hosted.opt(key);
                if (value == null) continue;
                if (!(value instanceof String)) return RestoreResult.INVALID;
                if (!((String) value).isEmpty()) { current.put(key, value); got = true; }
            }
            for (String key : new String[]{"links", "gallery", "posts", "nfts"}) {
                Object value = hosted.opt(key);
                if (value == null) continue;
                if (!(value instanceof JSONArray)) return RestoreResult.INVALID;
                if (((JSONArray) value).length() > 0) { current.put(key, value); got = true; }
            }
            if (!got) return RestoreResult.EMPTY;
            if (prefs.edit().putString(KEY, current.toString()).commit()) return RestoreResult.APPLIED;
        } catch (Exception unavailable) { /* Do not report an unconfirmed restore. */ }
        // Match writeIdentity/savePublication: failed commit can still change memory.
        try { prefs.edit().putString(KEY, previous).commit(); } catch (Exception unavailable) { }
        return RestoreResult.FAILED;
    }

    static synchronized JSONObject me(Context c) {
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
