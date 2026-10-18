package com.eurobuddha.merchinbox;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.eurobuddha.merchinbox.integratedcomms.MerchDb;
import org.json.*;
import java.util.*;

/** Public intent/receipt journal. Reservations and phase changes use NFT Wallet's SQLite transaction pattern. */
public final class DeliveryJournal {
    private static final Set<String> LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final String owner = UUID.randomUUID().toString();
    private final MerchDb store;
    static final String PREPARED="PREPARED", POSTING="POSTING", UNKNOWN="UNKNOWN", POSTED="POSTED", CONFIRMED="CONFIRMED", NOT_SUBMITTED="NOT_SUBMITTED";
    public DeliveryJournal(MerchDb store) { this.store=store; LIVE.add(owner); }
    public void close() { LIVE.remove(owner); }
    public static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS nft_delivery (linekey TEXT PRIMARY KEY NOT NULL, data TEXT NOT NULL)");
    }
    static String binding(MerchDb.Order o,String[] line) {
        if(o==null||o.ref==null||!Util.isValidAddress(o.payaddr)||line==null||line.length!=5||!Util.isValidHexId(line[1]))throw DeliveryEvidence.invalid();
        try { return new JSONArray().put(o.ref).put(o.counterparty==null?"":o.counterparty)
            .put(o.payaddr.startsWith("0x")?o.payaddr.toLowerCase(Locale.ROOT):o.payaddr)
            .put(o.items==null?"":o.items).put(line[1].toLowerCase(Locale.ROOT)).put(line[2]).put(line[3]).put(line[4]).toString(); }
        catch(RuntimeException e){throw DeliveryEvidence.invalid();}
    }
    void fresh(MerchDb.Order expected,String[] line) {
        MerchDb.Order current=store.order(expected.ref);
        if(!binding(expected,line).equals(binding(current,line))||!current.paid
            ||!Arrays.asList(MerchDb.PAID,MerchDb.CONFIRMED,MerchDb.SHIPPED,MerchDb.DELIVERED).contains(current.status))throw DeliveryEvidence.invalid();
        // Reuse MerchDb.recordPayment's token/amount gate even if the lifecycle status was edited.
        try {
            if(!DeliveryEvidence.same(current.tokenid,current.paidTokenid)
                ||new java.math.BigDecimal(current.amount).signum()<=0
                ||new java.math.BigDecimal(current.paidAmount).compareTo(new java.math.BigDecimal(current.amount))<0)throw DeliveryEvidence.invalid();
        }catch(RuntimeException invalid){throw DeliveryEvidence.invalid();}
    }
    static JSONObject read(String raw) {
        JSONObject row=DeliveryEvidence.storedObject(raw);
        if(!(row.opt("binding") instanceof String))throw DeliveryEvidence.invalid();
        String token=DeliveryEvidence.id(row,"tokenid");JSONArray entries=row.optJSONArray("entries");
        if(entries==null||entries.length()==0)throw DeliveryEvidence.invalid();
        DeliveryEvidence.journal(entries,token);
        for(int i=0;i<entries.length();i++) {
            JSONObject e=entries.optJSONObject(i);String phase=e.optString("phase");
            if(!Arrays.asList(PREPARED,POSTING,UNKNOWN,POSTED,CONFIRMED,NOT_SUBMITTED).contains(phase)
                ||!(e.opt("owner") instanceof String))throw DeliveryEvidence.invalid();
            boolean receipt=POSTED.equals(phase)||CONFIRMED.equals(phase);
            if(receipt!=!e.optString("output").isEmpty())throw DeliveryEvidence.invalid();
            if(receipt) {
                DeliveryEvidence.id(e,"address");DeliveryEvidence.nonzeroId(e,"txpowid");
                if(DeliveryEvidence.same(e.optString("input"),DeliveryEvidence.nonzeroId(e,"output")))throw DeliveryEvidence.invalid();
            }
            if(CONFIRMED.equals(phase)) {
                try{DeliveryEvidence.inclusion(new JSONObject().put("status",true).put("response",e.getJSONObject("inclusion")));}
                catch(JSONException invalid){throw DeliveryEvidence.invalid();}
            }
        }
        return row;
    }
    private JSONObject row(SQLiteDatabase db,String key) {
        try(Cursor c=db.rawQuery("SELECT data FROM nft_delivery WHERE linekey=?",new String[]{key})) {return c.moveToFirst()?read(c.getString(0)):null;}
    }
    private void save(SQLiteDatabase db,String key,JSONObject data,boolean insert) {
        read(data.toString());ContentValues values=new ContentValues();values.put("data",data.toString());
        if(insert){values.put("linekey",key);db.insertOrThrow("nft_delivery",null,values);}
        else if(db.update("nft_delivery",values,"linekey=?",new String[]{key})!=1)throw DeliveryEvidence.invalid();
    }
    private JSONObject bound(SQLiteDatabase db,String key,MerchDb.Order o,String[] line) {
        fresh(o,line);JSONObject row=row(db,key);
        if(row!=null&&!binding(o,line).equals(row.optString("binding")))throw DeliveryEvidence.invalid();
        return row;
    }
    JSONObject load(String key,MerchDb.Order o,String[] line) {return bound(store.getReadableDatabase(),key,o,line);}
    static JSONObject entry(JSONObject row,String coinid) {
        JSONArray entries=row.optJSONArray("entries");
        for(int i=0;i<entries.length();i++)if(DeliveryEvidence.same(coinid,entries.optJSONObject(i).optString("input")))return entries.optJSONObject(i);
        throw DeliveryEvidence.invalid();
    }
    static boolean complete(JSONObject row) {
        if(row==null)return false;
        JSONArray entries=row.optJSONArray("entries");if(entries==null||entries.length()==0)return false;
        for(int i=0;i<entries.length();i++)if(!CONFIRMED.equals(entries.optJSONObject(i).optString("phase")))return false;
        return true;
    }
    /** Freeze the whole collection and reserve all inputs before any command may sign. */
    JSONObject prepare(String key,MerchDb.Order o,String[] line,List<JSONObject> coins) {
        if(!LIVE.contains(owner))throw DeliveryEvidence.invalid();
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            if(!LIVE.contains(owner))throw DeliveryEvidence.invalid();
            JSONObject row=bound(db,key,o,line);boolean insert=row==null;
            if(insert) {
                if(!store.getMeta("nftposted:"+key,"").isEmpty()||!store.getMeta("nftsent:"+key,"").isEmpty()||coins.isEmpty())throw DeliveryEvidence.invalid();
                JSONArray entries=new JSONArray();
                for(JSONObject coin:coins)entries.put(DeliveryEvidence.intent(coin,line[1]).put("phase",NOT_SUBMITTED).put("owner",""));
                row=new JSONObject().put("binding",binding(o,line)).put("tokenid",line[1]).put("entries",entries);
            }
            JSONArray entries=row.getJSONArray("entries");
            Set<String> claiming=new HashSet<>();
            for(int i=0;i<entries.length();i++) {
                JSONObject e=entries.getJSONObject(i);String phase=e.getString("phase");
                if(PREPARED.equals(phase)&&!LIVE.contains(e.getString("owner")))e.put("phase",NOT_SUBMITTED);
                if(!NOT_SUBMITTED.equals(e.getString("phase")))continue;
                for(JSONObject coin:coins)if(DeliveryEvidence.same(e.getString("input"),coin.optString("coinid"))) {
                    JSONObject intent=DeliveryEvidence.intent(coin,line[1]);
                    if(!DeliveryEvidence.sameJournal(new JSONArray().put(e),new JSONArray().put(intent),line[1]))throw DeliveryEvidence.invalid();
                    e.put("phase",PREPARED).put("owner",owner);claiming.add(e.getString("input").toLowerCase(Locale.ROOT));break;
                }
            }
            try(Cursor c=db.rawQuery("SELECT linekey,data FROM nft_delivery",null)) {
                while(c.moveToNext()) {
                    JSONObject other=read(c.getString(1));if(c.getString(0).equals(key))continue;
                    JSONArray held=other.getJSONArray("entries");boolean abandoned=false;
                    for(int i=0;i<held.length();i++) {
                        JSONObject e=held.getJSONObject(i);
                        if(PREPARED.equals(e.getString("phase"))&&!LIVE.contains(e.getString("owner"))) {
                            e.put("phase",NOT_SUBMITTED);abandoned=true;
                        }
                        if(!NOT_SUBMITTED.equals(e.getString("phase"))&&claiming.contains(e.getString("input").toLowerCase(Locale.ROOT)))throw DeliveryEvidence.invalid();
                    }
                    if(abandoned)save(db,c.getString(0),other,false);
                }
            }
            PlainDeliveryJournal.assertAvailable(db,claiming,null);
            save(db,key,row,insert);db.setTransactionSuccessful();return row;
        }catch(JSONException e){throw DeliveryEvidence.invalid();}finally{db.endTransaction();}
    }
    static boolean reserved(JSONObject e) {
        return !NOT_SUBMITTED.equals(e.optString("phase"))
                && !(PREPARED.equals(e.optString("phase"))&&!LIVE.contains(e.optString("owner")));
    }
    boolean owned(JSONObject e) {return LIVE.contains(owner)&&PREPARED.equals(e.optString("phase"))&&owner.equals(e.optString("owner"));}
    /** PREPARED may be retried only before POSTING was durably recorded. */
    void phase(String key,MerchDb.Order o,String[] line,String input,String next) {
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            JSONObject row=bound(db,key,o,line);if(row==null)throw DeliveryEvidence.invalid();
            JSONObject e=entry(row,input);String phase=e.optString("phase");
            boolean valid=owned(e)&&(POSTING.equals(next)||NOT_SUBMITTED.equals(next))
                ||POSTING.equals(phase)&&UNKNOWN.equals(next);
            if(!valid)throw DeliveryEvidence.invalid();
            e.put("phase",next);save(db,key,row,false);db.setTransactionSuccessful();
        }catch(JSONException e){throw DeliveryEvidence.invalid();}finally{db.endTransaction();}
    }
    /** Store only public identifiers, amount and state digest; no signatures or embedded artwork. */
    void receipt(String key,MerchDb.Order o,String[] line,String input,JSONObject reply,JSONObject chain) {
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            JSONObject row=bound(db,key,o,line);if(row==null)throw DeliveryEvidence.invalid();
            JSONObject e=entry(row,input);String phase=e.optString("phase");
            if(!Arrays.asList(POSTING,UNKNOWN,POSTED,CONFIRMED).contains(phase))throw DeliveryEvidence.invalid();
            JSONObject receipt=DeliveryEvidence.receipt(reply,e,o.payaddr);
            receipt.put("txpowid",DeliveryEvidence.txpow(reply).getString("txpowid"));
            receipt.put("owner",e.getString("owner")).put("phase",chain==null?POSTED:CONFIRMED);
            if(CONFIRMED.equals(phase)&&chain==null)throw DeliveryEvidence.invalid();
            if(chain!=null)receipt.put("inclusion",DeliveryEvidence.inclusion(chain));
            JSONArray entries=row.getJSONArray("entries");
            for(int i=0;i<entries.length();i++)if(entries.getJSONObject(i)==e){entries.put(i,receipt);break;}
            save(db,key,row,false);db.setTransactionSuccessful();
        }catch(JSONException e){throw DeliveryEvidence.invalid();}finally{db.endTransaction();}
    }
    void confirmSaved(String key,MerchDb.Order o,String[] line,String input,String txpowid,JSONObject chain) {
        JSONObject inclusion=DeliveryEvidence.inclusion(chain);
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            JSONObject row=bound(db,key,o,line);if(row==null)throw DeliveryEvidence.invalid();
            JSONObject e=entry(row,input);
            if(!POSTED.equals(e.optString("phase"))||!DeliveryEvidence.same(txpowid,e.optString("txpowid")))throw DeliveryEvidence.invalid();
            e.put("phase",CONFIRMED).put("inclusion",inclusion);save(db,key,row,false);db.setTransactionSuccessful();
        }catch(JSONException e){throw DeliveryEvidence.invalid();}finally{db.endTransaction();}
    }

}
