package com.eurobuddha.statenft;

import android.content.Context;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** One state-preserving transfer per lot. Durable receipts, never input absence, prove delivery. */
public final class AirdropEngine {
    public interface Done { void done(String message); }
    private static NodeApi owner;
    private static long generation;
    private AirdropEngine() {}

    public static JSONObject job(Context c, long localId) {
        try { return LocalStore.airdropRecord(c, "airdrop_" + localId); }
        catch (RuntimeException invalid) {
            JSONObject held = new JSONObject(); put(held, "invalid", true); put(held, "localid", localId); return held;
        }
    }
    public static boolean saveJob(Context c, JSONObject j) {
        try { validate(j); return LocalStore.writeAirdrop(c, j.getLong("localid"), j, null, false); }
        catch (Exception invalid) { return false; }
    }
    public static boolean clearJob(Context c, long localId) { return LocalStore.writeAirdrop(c, localId, null, null, true); }

    public static JSONObject createJob(StateNft.Meta meta, List<StateNft.Item> items, List<String> addrs) {
        try {
            JSONObject j = new JSONObject().put("schema", 2).put("jobid", UUID.randomUUID().toString())
                    .put("localid", meta.localId).put("tokenid", meta.tokenid);
            JSONArray entries = new JSONArray();
            for (int i = 0; i < Math.min(items.size(), addrs.size()); i++) {
                StateNft.Item it = items.get(i);
                if (!it.owned || it.coin == null) throw BurialEvidence.invalid();
                JSONObject coin = new JSONObject(it.coin.toString());
                entries.put(new JSONObject().put("idx", it.index).put("coinid", coin.optString("coinid"))
                        .put("addr", addrs.get(i)).put("coin", coin).put("intent", BurialEvidence.intent(coin, meta.tokenid))
                        .put("status", "PENDING").put("postedat", 0).put("checks", 0).put("error", ""));
            }
            j.put("entries", entries); validate(j); return j;
        } catch (Exception invalid) { return null; }
    }
    private static int count(JSONObject j, String key, int min, int max) {
        Object value = j.opt(key);
        if (!(value instanceof Integer || value instanceof Long)) throw BurialEvidence.invalid();
        long n = ((Number)value).longValue(); if (n < min || n > max) throw BurialEvidence.invalid(); return (int)n;
    }
    private static void validate(JSONObject j) {
        try {
            if (j == null || count(j,"schema",2,2) != 2 || !(j.opt("localid") instanceof Integer || j.opt("localid") instanceof Long)
                    || j.getLong("localid") <= 0 || !j.optString("jobid").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw BurialEvidence.invalid();
            String token = BurialEvidence.id(j,"tokenid"); JSONArray es = j.getJSONArray("entries");
            if (es.length() == 0) throw BurialEvidence.invalid();
            Set<String> inputs = new HashSet<>(), outputs = new HashSet<>();
            for (int i = 0; i < es.length(); i++) {
                JSONObject e = es.getJSONObject(i); JSONObject coin = e.getJSONObject("coin");
                int index = count(e,"idx",1,Integer.MAX_VALUE);
                if (!Integer.toString(index).equals(StateNft.stamped(coin)) || !Util.isValidAddress(e.optString("addr"))
                        || !BurialEvidence.same(BurialEvidence.id(e,"coinid"), BurialEvidence.id(coin,"coinid"))
                        || !inputs.add(e.getString("coinid").toLowerCase(Locale.ROOT))) throw BurialEvidence.invalid();
                JSONObject intent = BurialEvidence.intent(coin, token);
                if (!BurialEvidence.sameJournal(new JSONArray().put(intent),new JSONArray().put(e.getJSONObject("intent")),token)) throw BurialEvidence.invalid();
                count(e,"postedat",0,Integer.MAX_VALUE); count(e,"checks",0,20);
                String status=e.getString("status");
                if (!Arrays.asList("PENDING","POSTING","POSTED","UNKNOWN","DONE","FAIL").contains(status)) throw BurialEvidence.invalid();
                if (e.has("receipt")) {
                    JSONObject proof = receipt(e, token);
                    if (!outputs.add(proof.optString("output").toLowerCase(Locale.ROOT))
                            || Arrays.asList("PENDING","POSTING","FAIL").contains(status)) throw BurialEvidence.invalid();
                }
                if (e.has("confirmed") && !("DONE".equals(status) && Boolean.TRUE.equals(e.opt("confirmed")))) throw BurialEvidence.invalid();
                if (("POSTED".equals(status)||"DONE".equals(status))&&!e.has("receipt")) throw BurialEvidence.invalid();
                if ("DONE".equals(status)&&!Boolean.TRUE.equals(e.opt("confirmed"))) throw BurialEvidence.invalid();
            }
        } catch (Exception invalid) { throw BurialEvidence.invalid(); }
    }
    private static JSONObject receipt(JSONObject e, String token) {
        JSONObject r=e.optJSONObject("receipt"), intent=e.optJSONObject("intent");
        if (r==null||intent==null) throw BurialEvidence.invalid();
        BurialEvidence.journal(new JSONArray().put(r),token);
        String output=BurialEvidence.id(r,"output");
        if (new java.math.BigInteger(output.substring(2),16).signum()==0 || BurialEvidence.same(output,r.optString("input"))
                || !BurialEvidence.same(r.optString("input"),intent.optString("input"))
                || !BurialEvidence.amount(r).equals(BurialEvidence.amount(intent))
                || !r.optString("statehash").equals(intent.optString("statehash"))
                || !BurialEvidence.preserves(r) || !e.optString("addr").equals(r.optString("destination"))) throw BurialEvidence.invalid();
        String address=BurialEvidence.id(r,"address");
        if (!Util.isValidAddress(address) || (e.optString("addr").startsWith("0x")&&!BurialEvidence.same(address,e.optString("addr")))) throw BurialEvidence.invalid();
        return r;
    }
    public static boolean verifiedJob(JSONObject j) { try { validate(j); return true; } catch(RuntimeException invalid){return false;} }
    public static String status(JSONObject j, JSONObject e) {
        return status(verifiedJob(j),e);
    }
    public static String status(boolean valid, JSONObject e) {
        if(e==null)return "UNKNOWN";
        String st=e.optString("status");
        if(!valid || "POSTING".equals(st)) return "FAIL".equals(st)?"FAIL":"UNKNOWN";
        return st;
    }
    public static int[] progress(JSONObject j) {
        JSONArray es=j==null?null:j.optJSONArray("entries"); if(es==null)return new int[]{0,0};
        int done=0; boolean valid=verifiedJob(j);
        if(valid)for(int i=0;i<es.length();i++)if("DONE".equals(es.optJSONObject(i).optString("status")))done++;
        return new int[]{done,es.length()};
    }
    public static CharSequence errorText(JSONObject e) {
        String key=e.optString("errorKey"), raw=e.optString("error");
        switch(key){
            case "read": return Language.msg(l10n_atelier_delivery_read_failed,raw);
            case "missing": return Language.msg(l10n_atelier_delivery_missing);
            case "large": return Language.msg(l10n_atelier_delivery_too_large);
            case "invalid": return Language.msg(l10n_atelier_delivery_invalid);
            case "unknown": return Language.msg(l10n_atelier_delivery_unknown);
            default: return raw;
        }
    }
    /** Manual checking never authorizes another signature or changes a recipient. */
    public static synchronized boolean checkJob(Context c, long id) {
        if (owner != null && !owner.isClosed()) return false;
        JSONObject j=job(c,id); if(!verifiedJob(j))return false;
        String expected=j.toString(); JSONArray es=j.optJSONArray("entries"); boolean changed=false;
        for(int i=0;i<es.length();i++){
            JSONObject e=es.optJSONObject(i);
            if(e.has("receipt")&&!"DONE".equals(e.optString("status"))){put(e,"status","POSTED");put(e,"checks",0);put(e,"errorKey","");put(e,"error","");changed=true;}
        }
        return changed && LocalStore.writeAirdrop(c,id,j,expected,false);
    }
    public static boolean hasWork(Context c) {
        try {
            JSONObject index=LocalStore.airdropRecord(c,"airdrop_index"); if(index==null)return false;
            Iterator<String> ids=index.keys();
            while(ids.hasNext()){
                String key=ids.next(); long id;
                try { id=Long.parseLong(key); } catch(NumberFormatException invalid){continue;}
                JSONObject j=job(c,id); if(!verifiedJob(j) || j.optLong("localid")!=id)continue;
                JSONArray es=j.optJSONArray("entries");
                for(int i=0;i<es.length();i++)if(Arrays.asList("PENDING","POSTING","POSTED").contains(es.optJSONObject(i).optString("status")))return true;
            }
        }catch(RuntimeException invalid){ }
        return false;
    }
    public static synchronized void tick(Context ctx, NodeApi node, Done done) {
        if(node==null||node.isClosed())return;
        if(owner!=null&&!owner.isClosed())return;
        owner=node; long current=++generation;
        new Run(ctx,node,done,current).start();
    }
    private static final class Run {
        final Context ctx; final NodeApi node; final Done done; final long current;
        JSONArray ids; JSONObject j; JSONArray es; String expected; long id; int jobIndex; boolean posted;
        Run(Context c,NodeApi n,Done d,long g){ctx=c;node=n;done=d;current=g;}
        boolean live(){synchronized(AirdropEngine.class){return owner==node&&current==generation&&!node.isClosed();}}
        void finish(){synchronized(AirdropEngine.class){if(current==generation)owner=null;}}
        void message(int key,Object...args){if(done!=null)done.done(Language.text(ctx,key,args));}
        void start(){
            try {JSONObject index=LocalStore.airdropRecord(ctx,"airdrop_index");ids=index==null?null:index.names();}
            catch(RuntimeException invalid){message(l10n_atelier_delivery_invalid);finish();return;}
            nextJob();
        }
        void nextJob(){
            if(!live()){finish();return;}
            while(ids!=null && jobIndex<ids.length()) {
                try {id=Long.parseLong(ids.getString(jobIndex++)); j=job(ctx,id); validate(j); if(j.getLong("localid")!=id)throw BurialEvidence.invalid();}
                catch(Exception invalid){continue;}
                expected=j.toString();es=j.optJSONArray("entries");posted=false;
                step(0);return;
            }
            finish();
        }
        boolean currentJob(){
            if(!live())return false;
            JSONObject saved=job(ctx,id);return saved!=null&&expected.equals(saved.toString());
        }
        boolean save(){
            if(!live()||!LocalStore.writeAirdrop(ctx,id,j,expected,false)){message(l10n_atelier_delivery_save_failed);finish();return false;}
            expected=j.toString();return true;
        }
        void step(int k){
            if(!currentJob()){nextJob();return;}
            // Skip settled entries iteratively, so a large saved batch cannot overflow the stack.
            while(k<es.length()) {
                String state=es.optJSONObject(k).optString("status");
                if (Arrays.asList("DONE","FAIL","UNKNOWN").contains(state)
                        || ("PENDING".equals(state) && (posted || !LocalStore.canDepart(ctx,j.optString("tokenid"))))) k++;
                else break;
            }
            if(k>=es.length()){int[] p=progress(j);message(l10n_atelier_gallery_delivered,p[0],p[1]);nextJob();return;}
            final int next=k+1;final JSONObject e=es.optJSONObject(k);String st=e.optString("status");
            if("POSTING".equals(st)){put(e,"status","UNKNOWN");put(e,"errorKey","unknown");if(save())step(next);return;}
            if("POSTED".equals(st)){check(e,()->step(next));return;}
            if(posted||!LocalStore.canDepart(ctx,j.optString("tokenid"))){step(next);return;}
            readInput(e,()->step(next));
        }
        void check(JSONObject e,Runnable next){
            JSONObject proof=receipt(e,j.optString("tokenid"));
            node.cmd("coins coinid:"+proof.optString("output"),new NodeApi.Cb(){
                @Override public void onResult(JSONObject reply){
                    if(!currentJob()){nextJob();return;}
                    try{JSONArray coins=BurialEvidence.coins(reply,j.optString("tokenid"));
                        if(coins.length()==1&&BurialEvidence.confirmed(proof,coins.optJSONObject(0),proof.optString("address"))){put(e,"status","DONE");put(e,"confirmed",true);put(e,"errorKey","");put(e,"error","");if(save())next.run();return;}
                    }catch(RuntimeException invalid){ }
                    pending();
                }
                @Override public void onError(String error){if(currentJob())pending();else nextJob();}
                void pending(){int checks=e.optInt("checks")+1;put(e,"checks",checks);if(checks>=20){put(e,"status","UNKNOWN");put(e,"errorKey","unknown");}if(save())next.run();}
            });
        }
        void readInput(JSONObject e,Runnable next){
            node.cmd("coins relevant:true own:true coinid:"+e.optString("coinid"),new NodeApi.Cb(){
                @Override public void onResult(JSONObject reply){
                    if(!currentJob()){nextJob();return;}
                    try{
                        JSONArray coins=BurialEvidence.coins(reply,j.optString("tokenid"));
                        if(coins.length()==0){put(e,"status","FAIL");put(e,"errorKey","missing");if(save())next.run();return;}
                        if(coins.length()!=1||!Boolean.FALSE.equals(coins.optJSONObject(0).opt("spent"))||!BurialEvidence.sameJournal(new JSONArray().put(e.optJSONObject("intent")),new JSONArray().put(BurialEvidence.intent(coins.optJSONObject(0),j.optString("tokenid"))),j.optString("tokenid")))throw BurialEvidence.invalid();
                    }catch(RuntimeException invalid){put(e,"errorKey","read");put(e,"error","");if(save())next.run();return;}
                    // Existing transfer-size guard, using fresh token metadata before signing.
                    node.cmd("tokens tokenid:"+j.optString("tokenid"),new NodeApi.Cb(){
                        @Override public void onResult(JSONObject reply){
                            if(!currentJob()){nextJob();return;}
                            // Reuse MainActivity.verifyPhantom's token lookup validation.
                            JSONObject metadata=reply==null?null:reply.optJSONObject("response");
                            if(reply==null || !Boolean.TRUE.equals(reply.opt("status")) || metadata==null
                                    || !(metadata.opt("tokenid") instanceof String)
                                    || !j.optString("tokenid").equalsIgnoreCase((String)metadata.opt("tokenid"))
                                    || !(metadata.opt("script") instanceof String) || metadata.optString("script").trim().isEmpty()) {
                                onError("");return;
                            }
                            int size=metadata.toString().length();
                            for(String[] state:StateNft.rawStateEntries(e.optJSONObject("coin")))size+=state[1].length();
                            if(size>MintEngine.TRANSFER_PAIR_BUDGET+1000){put(e,"status","FAIL");put(e,"errorKey","large");if(save())next.run();return;}
                            post(e,next);
                        }
                        @Override public void onError(String error){if(!currentJob()){nextJob();return;}put(e,"errorKey","read");put(e,"error",error);if(save())next.run();}
                    });
                }
                @Override public void onError(String error){if(!currentJob()){nextJob();return;}put(e,"errorKey","read");put(e,"error",error);if(save())next.run();}
            });
        }
        void post(JSONObject e,Runnable next){
            if(!currentJob()){nextJob();return;}
            if(!LocalStore.canDepart(ctx,j.optString("tokenid"))){next.run();return;}
            posted=true;put(e,"status","POSTING");put(e,"errorKey","");put(e,"error","");
            if(!save())return; // Frozen intent is durable before any transaction command.
            String txn="drop"+UUID.randomUUID().toString().replace("-","");
            MintEngine.postTxnWithReceipt(node,txn,StateNft.transferCommands(txn,j.optString("tokenid"),e.optJSONObject("coin"),e.optString("addr")),reply->{
                if(!currentJob()){nextJob();return;}
                try{JSONObject proof=BurialEvidence.receipt(reply,e.optJSONObject("intent"),e.optString("addr"));put(proof,"destination",e.optString("addr"));put(e,"receipt",proof);put(e,"status","POSTED");put(e,"checks",0);}
                catch(RuntimeException invalid){put(e,"status","UNKNOWN");put(e,"errorKey","unknown");}
                if(save())next.run();
            },error->{if(!currentJob()){nextJob();return;}put(e,"status","UNKNOWN");put(e,"errorKey","unknown");put(e,"error",error);if(save())next.run();});
        }
    }
    private static void put(JSONObject o,String key,Object value){try{o.put(key,value);}catch(org.json.JSONException e){throw BurialEvidence.invalid();}}
}
