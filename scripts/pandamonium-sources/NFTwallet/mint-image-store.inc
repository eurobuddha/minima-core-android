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

