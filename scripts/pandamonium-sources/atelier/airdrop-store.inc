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
