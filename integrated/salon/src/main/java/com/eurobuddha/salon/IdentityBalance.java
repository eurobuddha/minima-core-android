package com.eurobuddha.salon;

import org.json.JSONArray;
import org.json.JSONObject;

/** Read the complete identity lookup before deciding that a mint is needed.
 * Uses StudioData's status/row checks and the core balance TokenMeta object parser. */
final class IdentityBalance {
    private IdentityBalance() {}
    static JSONObject find(JSONObject reply, String handle) {
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))) throw invalid();
        JSONArray rows = reply.optJSONArray("response");
        if (rows == null) throw invalid();
        JSONObject found = null;
        for (int i=0; i<rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !(row.opt("tokenid") instanceof String)
                    || !Args.isTokenId((String) row.opt("tokenid"))) throw invalid();
            Object token = row.opt("token");
            if (!(token instanceof String) && !(token instanceof JSONObject)) throw invalid();
            JSONObject meta = asObject(token);
            if (meta == null || !"1".equals(meta.optString("salon"))) continue;
            if (!(meta.opt("handle") instanceof String) || meta.optString("handle").isEmpty()) throw invalid();
            if (found == null && (handle == null || handle.equals(meta.optString("handle")))) {
                try {
                    // Normalize serialized metadata for the existing adoption caller without changing the reply.
                    found = new JSONObject(row.toString()).put("token", meta);
                } catch (org.json.JSONException malformed) { throw invalid(); }
            }
        }
        return found;
    }
    private static JSONObject asObject(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return (JSONObject) value;
        String text = value.toString().trim();
        if (text.startsWith("{")) {
            try { return new JSONObject(text); }
            catch (org.json.JSONException ignored) { }
        }
        return null;
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Identity lookup unavailable");
    }
}
