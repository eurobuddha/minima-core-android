package com.eurobuddha.merchinbox;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.eurobuddha.merchinbox.integratedcomms.MerchDb;
import com.eurobuddha.nftwallet.OrderPaymentEvidence;
import org.json.*;
import java.util.*;
import static com.eurobuddha.merchinbox.DeliveryJournal.*;

/** Inbox order binding over NFT Wallet's exact native-send intent and public receipts. */
public final class PlainDeliveryJournal {
    private static final Set<String> LIVE=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final String owner=UUID.randomUUID().toString();
    private final MerchDb store;private final DeliveryJournal orders;
    PlainDeliveryJournal(MerchDb store,DeliveryJournal orders){this.store=store;this.orders=orders;LIVE.add(owner);}
    void close(){LIVE.remove(owner);}
    public static void create(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS nft_plain_delivery (linekey TEXT PRIMARY KEY NOT NULL,data TEXT NOT NULL)");}
    static JSONObject read(String raw) {
        JSONObject row=DeliveryEvidence.storedObject(raw);String phase=row.optString("phase");
        if(!(row.opt("binding") instanceof String)||!(row.opt("owner") instanceof String)
                ||!row.optString("handle").matches("mi_send_[0-9a-f]{32}")
                ||!Arrays.asList(PREPARED,POSTING,UNKNOWN,POSTED,CONFIRMED,NOT_SUBMITTED).contains(phase))throw DeliveryEvidence.invalid();
        JSONObject intent=OrderPaymentEvidence.storedIntent(row.optJSONObject("intent")==null?null:row.optJSONObject("intent").toString());
        if((POSTED.equals(phase)||CONFIRMED.equals(phase))!=row.has("receipt"))throw DeliveryEvidence.invalid();
        if(row.has("receipt"))OrderPaymentEvidence.storedReceipt(row.optJSONObject("receipt"),intent);
        if(CONFIRMED.equals(phase)) {
            try{OrderPaymentEvidence.inclusion(new JSONObject().put("status",true).put("response",row.getJSONObject("inclusion")));}
            catch(JSONException e){throw DeliveryEvidence.invalid();}
        }
        return row;
    }
    private static JSONObject row(SQLiteDatabase db,String key) {
        try(Cursor c=db.rawQuery("SELECT data FROM nft_plain_delivery WHERE linekey=?",new String[]{key})){return c.moveToFirst()?read(c.getString(0)):null;}
    }
    private static void save(SQLiteDatabase db,String key,JSONObject row,boolean insert) {
        read(row.toString());ContentValues v=new ContentValues();v.put("data",row.toString());
        if(insert){v.put("linekey",key);db.insertOrThrow("nft_plain_delivery",null,v);}
        else if(db.update("nft_plain_delivery",v,"linekey=?",new String[]{key})!=1)throw DeliveryEvidence.invalid();
    }
    private JSONObject bound(SQLiteDatabase db,String key,MerchDb.Order o,String[] line) {
        orders.fresh(o,line);JSONObject row=row(db,key);
        if(row!=null){if(!binding(o,line).equals(row.optString("binding")))throw DeliveryEvidence.invalid();OrderPaymentEvidence.binds(row.optJSONObject("intent"),o.payaddr,line[2],line[1]);}
        return row;
    }
    JSONObject load(String key,MerchDb.Order o,String[] line){return bound(store.getReadableDatabase(),key,o,line);}
    static boolean complete(JSONObject row){return row!=null&&CONFIRMED.equals(row.optString("phase"));}
    static boolean retryable(JSONObject row){return row==null||NOT_SUBMITTED.equals(row.optString("phase"))||PREPARED.equals(row.optString("phase"))&&!LIVE.contains(row.optString("owner"));}
    /** Called under the same SQLite transaction by both plain and stateful reservers. */
    static void assertAvailable(SQLiteDatabase db,Set<String> inputs,String except) {
        try(Cursor c=db.rawQuery("SELECT linekey,data FROM nft_plain_delivery",null)) {
            while(c.moveToNext()) {
                if(c.getString(0).equals(except))continue;JSONObject other=read(c.getString(1));
                if(!retryable(other)&&!Collections.disjoint(inputs,OrderPaymentEvidence.inputIds(other.optJSONObject("intent"))))throw DeliveryEvidence.invalid();
            }
        }
    }
    JSONObject prepare(String key,MerchDb.Order o,String[] line,String handle,JSONObject intent) {
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            if(!LIVE.contains(owner))throw DeliveryEvidence.invalid();JSONObject old=bound(db,key,o,line);
            if(!retryable(old)||old==null&&(!store.getMeta("nftsent:"+key,"").isEmpty()||!store.getMeta("nftposted:"+key,"").isEmpty()))throw DeliveryEvidence.invalid();
            JSONObject exact=OrderPaymentEvidence.storedIntent(intent.toString());OrderPaymentEvidence.binds(exact,o.payaddr,line[2],line[1]);
            Set<String> inputs=OrderPaymentEvidence.inputIds(exact);assertAvailable(db,inputs,key);
            try(Cursor c=db.rawQuery("SELECT data FROM nft_delivery",null)) {
                while(c.moveToNext()) {
                    JSONArray entries=DeliveryJournal.read(c.getString(0)).optJSONArray("entries");
                    for(int i=0;i<entries.length();i++) {
                        JSONObject entry=entries.optJSONObject(i);
                        if(DeliveryJournal.reserved(entry)&&inputs.contains(entry.optString("input").toLowerCase(Locale.ROOT)))throw DeliveryEvidence.invalid();
                    }
                }
            }
            JSONObject row=new JSONObject().put("binding",binding(o,line)).put("owner",owner).put("handle",handle).put("phase",PREPARED).put("intent",exact);
            save(db,key,row,old==null);db.setTransactionSuccessful();return row;
        }catch(JSONException e){throw DeliveryEvidence.invalid();}finally{db.endTransaction();}
    }
    void phase(String key,MerchDb.Order o,String[] line,String handle,String next) {
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            JSONObject row=bound(db,key,o,line);if(row==null||!handle.equals(row.optString("handle"))||!owner.equals(row.optString("owner")))throw DeliveryEvidence.invalid();
            String old=row.optString("phase");
            if(!(PREPARED.equals(old)&&LIVE.contains(owner)&&(POSTING.equals(next)||NOT_SUBMITTED.equals(next))||POSTING.equals(old)&&UNKNOWN.equals(next)))throw DeliveryEvidence.invalid();
            row.put("phase",next);save(db,key,row,false);db.setTransactionSuccessful();
        }catch(JSONException e){throw DeliveryEvidence.invalid();}finally{db.endTransaction();}
    }
    void receipt(String key,MerchDb.Order o,String[] line,JSONObject reply,JSONObject chain) {
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            JSONObject row=bound(db,key,o,line);
            if(row==null||!Arrays.asList(POSTING,UNKNOWN,POSTED).contains(row.optString("phase")))throw DeliveryEvidence.invalid();
            OrderPaymentEvidence.referenceStamp(reply,o.ref);
            row.put("receipt",OrderPaymentEvidence.publicReceipt(reply,row.getJSONObject("intent"))).put("phase",chain==null?POSTED:CONFIRMED);
            if(chain!=null)row.put("inclusion",OrderPaymentEvidence.inclusion(chain));
            save(db,key,row,false);db.setTransactionSuccessful();
        }catch(JSONException e){throw DeliveryEvidence.invalid();}finally{db.endTransaction();}
    }
    void confirmSaved(String key,MerchDb.Order o,String[] line,String txid,JSONObject chain) {
        JSONObject inclusion=OrderPaymentEvidence.inclusion(chain);SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            JSONObject row=bound(db,key,o,line);
            if(row==null||!POSTED.equals(row.optString("phase"))||!DeliveryEvidence.same(txid,row.optJSONObject("receipt").optString("txpowid")))throw DeliveryEvidence.invalid();
            row.put("phase",CONFIRMED).put("inclusion",inclusion);save(db,key,row,false);db.setTransactionSuccessful();
        }catch(JSONException e){throw DeliveryEvidence.invalid();}finally{db.endTransaction();}
    }
}
