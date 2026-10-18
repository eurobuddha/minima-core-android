package com.eurobuddha.salon;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Validate backup structure before restoring keys, drafts or destinations. */
final class BackupValidation {
    private BackupValidation() {}

    static void check(JSONObject backup) {
        Object version = backup.opt("salonBackup");
        if (!(version instanceof Integer || version instanceof Long) || ((Number) version).longValue() != 1)
            throw new IllegalArgumentException("Invalid backup version");
        string(backup, "msgseed", false);
        if (backup.has("me")) {
            Object value = backup.opt("me");
            if (!(value instanceof JSONObject)) invalid();
            JSONObject me = (JSONObject) value;
            // Same typed fields as SalonStore.restoreHosted, plus local identity fields.
            for (String key : new String[]{"handle", "tokenid", "profileUrl", "msgseed", "msgpk",
                    "name", "bio", "about", "avatar", "banner", "webvalidate", "tipaddr"})
                string(me, key, false);
            for (String key : new String[]{"links", "gallery", "posts", "nfts", "follows"}) {
                if (!me.has(key)) continue;
                Object rows = me.opt(key);
                if (!(rows instanceof JSONArray)) invalid();
                objects((JSONArray) rows);
            }
        }
        if (!backup.has("hosting")) return;
        Object value = backup.opt("hosting");
        if (!(value instanceof JSONArray)) invalid();
        JSONArray hosts = (JSONArray) value;
        objects(hosts);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < hosts.length(); i++) {
            JSONObject host = hosts.optJSONObject(i);
            string(host, "id", true); string(host, "type", true);
            if (!ids.add(host.optString("id"))) invalid();
            for (String key : new String[]{"name", "pathTemplate", "dirTemplate"}) string(host, key, false);
            if (host.has("def") && !(host.opt("def") instanceof Boolean)) invalid();
            // Inactive configurations are also exported, so validate them too.
            for (String key : new String[]{host.optString("type"), "sftp", "webdav", "kubo", "pinata",
                    "github", "blossom", "maxima", "relay", "arweave"})
                if (host.has(key) && !(host.opt(key) instanceof JSONObject)) invalid();
        }
    }

    private static void objects(JSONArray array) {
        for (int i = 0; i < array.length(); i++) if (!(array.opt(i) instanceof JSONObject)) invalid();
    }
    private static void string(JSONObject object, String key, boolean required) {
        if (!object.has(key) && !required) return;
        Object value = object.opt(key);
        if (!(value instanceof String) || (required && ((String) value).trim().isEmpty())) invalid();
    }
    private static void invalid() { throw new IllegalArgumentException("Invalid backup fields"); }
}
