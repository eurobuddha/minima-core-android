    static JSONObject createIdentity(JSONObject row) {
        JSONObject meta=StateNft.tokenMetadata(metaFromRow(row));
        JSONObject traits=row.optJSONObject("itemtraits");
        if(traits!=null && traits.length()>0) put(meta,"traits",traits);
        put(meta,"webvalidate",row.optString("webvalidate"));
        put(meta,"creatorpk",row.optString("creatorpk"));
        return meta;
    }
    static boolean sameCreateJson(Object a,Object b) {
        if(a instanceof JSONObject && b instanceof JSONObject) {
            JSONObject x=(JSONObject)a,y=(JSONObject)b;
            if(x.length()!=y.length()) return false;
            java.util.Iterator<String> keys=x.keys();
            while(keys.hasNext()) { String k=keys.next(); if(!y.has(k)||!sameCreateJson(x.opt(k),y.opt(k))) return false; }
            return true;
        }
        if(a instanceof JSONArray && b instanceof JSONArray) {
            JSONArray x=(JSONArray)a,y=(JSONArray)b;
            if(x.length()!=y.length()) return false;
            for(int i=0;i<x.length();i++) if(!sameCreateJson(x.opt(i),y.opt(i))) return false;
            return true;
        }
        return java.util.Objects.equals(a,b);
    }
    private static boolean createMetadataMatches(JSONObject row, JSONObject token) {
        JSONObject nested=Util.asObject(token.opt("name"));
        JSONObject actual=nested==null?token:nested, expected=createIdentity(row);
        java.util.Iterator<String> keys=expected.keys();
        while(keys.hasNext()) {
            String k=keys.next(); if("creatorpk".equals(k)) continue;
            Object a=actual.opt(k); if(a==null && "webvalidate".equals(k)) a=token.opt(k);
            if(a==null && "".equals(expected.opt(k))) a="";
            if(!sameCreateJson(expected.opt(k),a)) return false;
        }
        return true;
    }
    private static void phaseCreate(Context ctx, NodeApi node, JSONObject row, int tip, Done done) {
        cmd(node, "balance", new Cb() {
            @Override public void ok(JSONObject res) {
                try {
                    JSONArray bal=res.optJSONArray("response");
                    if(bal==null) throw new IllegalArgumentException();
                    HashSet<String> bound=new HashSet<>();
                    JSONArray all=LocalStore.createRows(ctx);
                    for(int i=0;i<all.length();i++) {
                        JSONObject r=all.getJSONObject(i); String tid=r.optString("tokenid");
                        if(!tid.isEmpty() && (r.optInt("created",1)!=0 || !"DONE".equals(r.optString("phase")))) bound.add(tid.toLowerCase(java.util.Locale.ROOT));
                    }
                    List<String> candidates=new ArrayList<>();
                    HashSet<String> seen=new HashSet<>();
                    for(int i=0;i<bal.length();i++) {
                        JSONObject b=bal.getJSONObject(i); String tid=b.optString("tokenid");
                        if(!Util.isValidHexId(tid) || !seen.add(tid.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException();
                        if(Util.MINIMA_TOKENID.equals(tid)) continue;
                        Object raw=b.opt("token"); JSONObject t=Util.tokenObject(raw);
                        if(t==null) {
                            if(!(raw instanceof String) || ((String)raw).trim().startsWith("{")) throw new IllegalArgumentException();
                            continue;
                        }
                        if(createMetadataMatches(row,t) && !bound.contains(tid.toLowerCase(java.util.Locale.ROOT))) candidates.add(tid);
                    }
                    findMatchingToken(ctx,node,row,candidates,0,new ArrayList<>(),tip,done);
                } catch(Exception invalid) { setError(ctx,row,"Could not verify wallet metadata. No new collection was submitted.",done); }
            }
            @Override public void fail(String e) { setError(ctx,row,e,done); }
        });
    }
    private static void findMatchingToken(Context ctx, NodeApi node, JSONObject row, List<String> cands,
                                          int i, List<String> matches, int tip, Done done) {
        if(i>=cands.size()) {
            String choice=row.optString("createchoice");
            if(matches.size()>1 || !choice.isEmpty()) {
                put(row,"creatematches",new JSONArray(matches));
                if(choice.isEmpty() || !matches.contains(choice)) {
                    setError(ctx,row,matches.size()+" matching collections found. Choose the token to finish below; no new collection will be created.",done); return;
                }
            }
            String selected=choice.isEmpty() && matches.size()==1?matches.get(0):choice;
            if(!selected.isEmpty()) {
                if(LocalStore.bindCreate(ctx,row,selected)) done.done("Existing collection found — continuing its mint");
                else done.done("Collection changed while checking. Refresh and try again.");
                return;
            }
            postTokenCreate(ctx,node,row,tip,done); return;
        }
        String tid=cands.get(i);
        cmd(node,"tokens tokenid:"+tid,new Cb() {
            @Override public void ok(JSONObject res) {
                JSONObject t=res.optJSONObject("response");
                if(t==null || !t.has("script")) { setError(ctx,row,"Could not verify the existing token contract. No new collection was submitted.",done); return; }
                if(StateNft.script(row.optString("creatorpk"),row.optString("mode")).equals(t.optString("script"))) matches.add(tid);
                findMatchingToken(ctx,node,row,cands,i+1,matches,tip,done);
            }
            @Override public void fail(String e) { setError(ctx,row,"Could not check an existing token: "+e,done); }
        });
    }
