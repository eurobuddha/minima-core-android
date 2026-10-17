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

