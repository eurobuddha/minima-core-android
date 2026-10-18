package com.eurobuddha.salon;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Encrypted hosting profiles. After a full restore they share the account's
 * preference file so the identity, draft and destinations commit together. */
final class HostingStore {

    private static final String KEY = "hosting_profiles";
    /** Secret field names per backend — wrapped at rest, redacted in logs. */
    static final String[] SECRET_FIELDS = { "password", "privateKey", "jwt", "token" };

    private HostingStore() {}

    /** Legacy location remains readable until the first successful full restore. */
    static final String PREFS = "pandamonium_salon_hosting";

    private static SharedPreferences prefs(Context c) {
        SharedPreferences account = SalonStore.prefs(c);
        return account.contains(SalonStore.BACKUP_HOSTS) ? account : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized List<Hosting.Profile> list(Context c) {
        List<Hosting.Profile> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(new Hosting.Profile(o));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** A corrupt store must not become an empty backup or be overwritten by restore. */
    static synchronized List<Hosting.Profile> listForBackup(Context c) {
        try {
            String raw = prefs(c).getString(KEY, "[]");
            if (raw == null || raw.indexOf(0) >= 0) throw new SalonStore.BackupStorageUnavailable();
            // Reuse Atelier LocalStore's trailing-data guard for the stored array.
            org.json.JSONTokener input = new org.json.JSONTokener(raw);
            Object value = input.nextValue();
            while (input.more()) {
                char ch = input.next();
                if (ch != ' ' && ch != '\t' && ch != '\r' && ch != '\n') throw new SalonStore.BackupStorageUnavailable();
            }
            if (!(value instanceof JSONArray)) throw new SalonStore.BackupStorageUnavailable();
            JSONArray rows = (JSONArray) value;
            List<Hosting.Profile> result = new ArrayList<>();
            java.util.HashSet<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                Object id = row.opt("id");
                if (!(id instanceof String) || ((String) id).isEmpty() || !ids.add((String) id))
                    throw new SalonStore.BackupStorageUnavailable();
                result.add(new Hosting.Profile(row));
            }
            return result;
        } catch (Exception unavailable) { throw new SalonStore.BackupStorageUnavailable(); }
    }

    /** Preserve the existing upsert-by-ID behavior without writing intermediate lists. */
    static synchronized JSONArray mergeForRestore(Context c, JSONArray incoming) throws org.json.JSONException {
        List<Hosting.Profile> all = listForBackup(c);
        for (int i = 0; i < incoming.length(); i++) {
            Hosting.Profile profile = new Hosting.Profile(incoming.getJSONObject(i));
            boolean replaced = false;
            for (int n = 0; n < all.size(); n++) {
                if (all.get(n).id().equals(profile.id())) { all.set(n, profile); replaced = true; break; }
            }
            if (!replaced) all.add(profile);
        }
        JSONArray result = new JSONArray();
        for (Hosting.Profile profile : all) result.put(profile.j);
        return result;
    }

    static synchronized void upsert(Context c, Hosting.Profile p) {
        List<Hosting.Profile> all = list(c);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(p.id())) { all.set(i, p); replaced = true; break; }
        }
        if (!replaced) all.add(p);
        save(c, all);
    }

    static synchronized void remove(Context c, String id) {
        List<Hosting.Profile> all = list(c);
        List<Hosting.Profile> next = new ArrayList<>();
        for (Hosting.Profile p : all) if (!p.id().equals(id)) next.add(p);
        save(c, next);
    }

    static synchronized void setDefault(Context c, String id) {
        List<Hosting.Profile> all = list(c);
        for (Hosting.Profile p : all) Hosting.put(p.j, "def", p.id().equals(id));
        save(c, all);
    }

    static synchronized Hosting.Profile getDefault(Context c) {
        List<Hosting.Profile> all = list(c);
        for (Hosting.Profile p : all) if (p.isDefault()) return p;
        return all.isEmpty() ? null : all.get(0);
    }

    private static void save(Context c, List<Hosting.Profile> all) {
        JSONArray arr = new JSONArray();
        for (Hosting.Profile p : all) arr.put(p.j);
        prefs(c).edit().putString(KEY, arr.toString()).apply();
    }
}
