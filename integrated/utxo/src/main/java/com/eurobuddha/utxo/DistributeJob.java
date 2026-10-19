package com.eurobuddha.utxo;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.math.BigDecimal;
import java.util.*;

/** Durable public distribution plan; the transaction journal owns submission and confirmation proof. */
public class DistributeJob {
    private static final String PREFS="utxo_distribute",KEY="job";
    public String tokenid,tokenName,per;
    public List<String> remaining=new ArrayList<>();
    public int batchesDone,maxBatches=4,atBlock,retryCount;
    public String expectedChangeCoinId="",nextChangeAddr="",expectedChangeAmt="",currentInternalId="",lastConfirmedId="";
    public boolean uncertain,waiting;
    public int pendingRecipients;
    public JSONObject batchIntent;
    /** Preserve unreadable and pre-journal jobs without silently treating them as idle. */
    public boolean invalid,legacy;

    public JSONObject toJson() {
        if(invalid||legacy)throw TransactionEvidence.invalid();
        try {
            JSONObject o=new JSONObject().put("schema",2).put("tokenid",tokenid).put("tokenName",tokenName).put("per",per)
                .put("remaining",new JSONArray(remaining)).put("batchesDone",batchesDone).put("maxBatches",maxBatches)
                .put("expectedChangeCoinId",expectedChangeCoinId).put("nextChangeAddr",nextChangeAddr).put("expectedChangeAmt",expectedChangeAmt)
                .put("waiting",waiting).put("uncertain",uncertain).put("atBlock",atBlock).put("retryCount",retryCount)
                .put("currentInternalId",currentInternalId).put("lastConfirmedId",lastConfirmedId).put("pendingRecipients",pendingRecipients);
            if(batchIntent!=null)o.put("batchIntent",batchIntent);
            return o;
        }catch(JSONException e){throw TransactionEvidence.invalid();}
    }
    private static String text(JSONObject o,String key) {Object v=o.opt(key);if(!(v instanceof String))throw TransactionEvidence.invalid();return(String)v;}
    private static int count(JSONObject o,String key,int min,int max) {
        Object value=o.opt(key);if(!(value instanceof Integer)&&!(value instanceof Long))throw TransactionEvidence.invalid();
        long n=((Number)value).longValue();if(n<min||n>max)throw TransactionEvidence.invalid();return(int)n;
    }
    private static boolean flag(JSONObject o,String key){Object v=o.opt(key);if(!(v instanceof Boolean))throw TransactionEvidence.invalid();return(Boolean)v;}
    private static void id(String value){if(!value.isEmpty()&&!value.matches("[A-Za-z0-9_-]{1,100}"))throw TransactionEvidence.invalid();}
    public static DistributeJob fromJson(JSONObject o) {
        if(o==null)throw TransactionEvidence.invalid();
        // Older records do not carry the frozen batch intent. Keep them visible and untouched.
        if(!o.has("schema")){DistributeJob held=new DistributeJob();held.legacy=true;held.uncertain=true;return held;}
        if(count(o,"schema",2,2)!=2)throw TransactionEvidence.invalid();
        DistributeJob j=new DistributeJob();j.tokenid=text(o,"tokenid");j.tokenName=text(o,"tokenName");j.per=text(o,"per");
        if(!Util.isValidHexId(j.tokenid)||!Util.isValidAmount(j.per)||new BigDecimal(j.per).signum()<=0)throw TransactionEvidence.invalid();
        j.batchesDone=count(o,"batchesDone",0,DistributeManager.MAX_BATCHES);j.maxBatches=count(o,"maxBatches",1,DistributeManager.MAX_BATCHES);
        if(j.batchesDone>j.maxBatches)throw TransactionEvidence.invalid();
        JSONArray addresses=o.optJSONArray("remaining");if(addresses==null||addresses.length()>DistributeManager.BATCH*(j.maxBatches-j.batchesDone))throw TransactionEvidence.invalid();
        for(int i=0;i<addresses.length();i++){Object address=addresses.opt(i);if(!(address instanceof String)||!Util.isValidAddress((String)address))throw TransactionEvidence.invalid();j.remaining.add((String)address);}
        j.expectedChangeCoinId=text(o,"expectedChangeCoinId");j.nextChangeAddr=text(o,"nextChangeAddr");j.expectedChangeAmt=text(o,"expectedChangeAmt");
        if(!j.expectedChangeCoinId.isEmpty()&&(!Util.isValidHexId(j.expectedChangeCoinId)||new java.math.BigInteger(j.expectedChangeCoinId.substring(2),16).signum()==0))throw TransactionEvidence.invalid();
        if(!j.nextChangeAddr.isEmpty()&&!Util.isValidAddress(j.nextChangeAddr))throw TransactionEvidence.invalid();
        if(!j.expectedChangeAmt.isEmpty()&&(!Util.isValidAmount(j.expectedChangeAmt)||new BigDecimal(j.expectedChangeAmt).signum()<=0))throw TransactionEvidence.invalid();
        j.uncertain=flag(o,"uncertain");j.waiting=flag(o,"waiting");j.atBlock=count(o,"atBlock",0,Integer.MAX_VALUE);j.retryCount=count(o,"retryCount",0,4);
        j.currentInternalId=text(o,"currentInternalId");j.lastConfirmedId=text(o,"lastConfirmedId");id(j.currentInternalId);id(j.lastConfirmedId);
        j.pendingRecipients=count(o,"pendingRecipients",0,DistributeManager.BATCH);
        if(o.has("batchIntent"))j.batchIntent=TransactionEvidence.storedIntent(o.optJSONObject("batchIntent")==null?null:o.optJSONObject("batchIntent").toString());
        if(j.uncertain!=(j.batchIntent!=null)||j.uncertain!=(j.pendingRecipients>0))throw TransactionEvidence.invalid();
        if(j.uncertain) {
            if(!j.waiting||j.currentInternalId.isEmpty()||j.pendingRecipients!=Math.min(DistributeManager.BATCH,j.remaining.size()))throw TransactionEvidence.invalid();
            JSONObject intent=j.batchIntent;
            if(!TransactionFields.same(j.tokenid,intent.optString("tokenid"))||intent.has("companion")||new BigDecimal(intent.optString("burn")).signum()!=0)throw TransactionEvidence.invalid();
            JSONArray out=intent.optJSONArray("outputs");if(out.length()<j.pendingRecipients||out.length()>j.pendingRecipients+1)throw TransactionEvidence.invalid();
            for(int i=0;i<j.pendingRecipients;i++) {
                JSONObject target=out.optJSONObject(i);
                if(!j.remaining.get(i).equals(target.optString("address"))||new BigDecimal(j.per).compareTo(new BigDecimal(target.optString("amount")))!=0)throw TransactionEvidence.invalid();
            }
            BigDecimal funds=BigDecimal.ZERO;JSONArray in=intent.optJSONArray("inputs");
            for(int i=0;i<in.length();i++)funds=funds.add(new BigDecimal(TransactionEvidence.amount(in.optJSONObject(i),j.tokenid)));
            if(funds.compareTo(new BigDecimal(j.per).multiply(BigDecimal.valueOf(j.remaining.size())))<0)throw TransactionEvidence.invalid();
        }else if(j.waiting&&(j.lastConfirmedId.isEmpty()||j.expectedChangeCoinId.isEmpty()||j.nextChangeAddr.isEmpty()||j.expectedChangeAmt.isEmpty()))throw TransactionEvidence.invalid();
        if(j.remaining.isEmpty()&&(j.batchesDone==0||j.lastConfirmedId.isEmpty()||j.uncertain||j.waiting))throw TransactionEvidence.invalid();
        return j;
    }
    public DistributeJob copy(){return fromJson(TransactionEvidence.storedObject(toJson().toString()));}
    public boolean save(Context ctx) {
        try{String raw=toJson().toString();fromJson(TransactionEvidence.storedObject(raw));return ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,raw).commit();}
        catch(RuntimeException unavailable){return false;}
    }
    public static DistributeJob load(Context ctx) {
        try {
            SharedPreferences p=ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE);String raw=p.getString(KEY,"");
            if(raw==null||raw.isEmpty())return null;
            return fromJson(TransactionEvidence.storedObject(raw));
        }catch(RuntimeException invalid){DistributeJob held=new DistributeJob();held.invalid=true;held.uncertain=true;return held;}
    }
    public static boolean clearChecked(Context ctx) {
        try{return ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(KEY).commit();}catch(RuntimeException unavailable){return false;}
    }
    public static void clear(Context ctx){clearChecked(ctx);}
}
