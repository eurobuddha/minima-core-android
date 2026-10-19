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
