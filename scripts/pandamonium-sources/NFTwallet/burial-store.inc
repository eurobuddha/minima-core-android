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
