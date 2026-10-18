package com.eurobuddha.merchshop;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.eurobuddha.merchshop.integratedcomms.MerchDb;
import com.eurobuddha.nftwallet.OrderPaymentEvidence;
import org.json.*;
import java.util.*;


/** Buyer-order adaptation of Inbox PlainDeliveryJournal; shares NFT Wallet's exact native-send evidence. */
public final class PaymentJournal {
    private static final Set<String> LIVE=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final String owner=UUID.randomUUID().toString();
    static final String PREPARED="PREPARED", POSTING="POSTING", UNKNOWN="UNKNOWN", POSTED="POSTED", CONFIRMED="CONFIRMED", NOT_SUBMITTED="NOT_SUBMITTED";
    private final MerchDb store;
    PaymentJournal(MerchDb store){this.store=store;LIVE.add(owner);}
    void close(){LIVE.remove(owner);}
    public static void create(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS shop_payment (orderref TEXT PRIMARY KEY NOT NULL,data TEXT NOT NULL)");}
    static JSONObject read(String raw) {
        JSONObject row=storedObject(raw);String phase=row.optString("phase");
        if(!(row.opt("binding") instanceof String)||!(row.opt("owner") instanceof String)
                ||!row.optString("handle").matches("ms_pay_[0-9a-f]{32}")
                ||!Arrays.asList(PREPARED,POSTING,UNKNOWN,POSTED,CONFIRMED,NOT_SUBMITTED).contains(phase))throw invalid();
        JSONObject intent=OrderPaymentEvidence.storedIntent(row.optJSONObject("intent")==null?null:row.optJSONObject("intent").toString());
        if((POSTED.equals(phase)||CONFIRMED.equals(phase))!=row.has("receipt"))throw invalid();
        if(row.has("receipt"))OrderPaymentEvidence.storedReceipt(row.optJSONObject("receipt"),intent);
        if(CONFIRMED.equals(phase)) {
            try{OrderPaymentEvidence.inclusion(new JSONObject().put("status",true).put("response",row.getJSONObject("inclusion")));}
            catch(JSONException e){throw invalid();}
        }
        return row;
    }
    private static JSONObject row(SQLiteDatabase db,String key) {
        try(Cursor c=db.rawQuery("SELECT data FROM shop_payment WHERE orderref=?",new String[]{key})){return c.moveToFirst()?read(c.getString(0)):null;}
    }
    private static void save(SQLiteDatabase db,String key,JSONObject row,boolean insert) {
        read(row.toString());ContentValues v=new ContentValues();v.put("data",row.toString());
        if(insert){v.put("orderref",key);db.insertOrThrow("shop_payment",null,v);}
        else if(db.update("shop_payment",v,"orderref=?",new String[]{key})!=1)throw invalid();
    }
    private JSONObject bound(SQLiteDatabase db,String key,MerchDb.Order o) {
        if(!key.equals(o.ref))throw invalid();fresh(o);JSONObject row=row(db,key);
        if(row!=null){if(!binding(o).equals(row.optString("binding")))throw invalid();OrderPaymentEvidence.binds(row.optJSONObject("intent"),o.payaddr,o.amount,o.tokenid);}
        return row;
    }
    JSONObject load(String key,MerchDb.Order o){return bound(store.getReadableDatabase(),key,o);}
    static boolean complete(JSONObject row){return row!=null&&CONFIRMED.equals(row.optString("phase"));}
    static boolean retryable(JSONObject row){return row==null||NOT_SUBMITTED.equals(row.optString("phase"))||PREPARED.equals(row.optString("phase"))&&!LIVE.contains(row.optString("owner"));}
    /** Reservations across buyer orders share the same SQLite transaction. */
    static void assertAvailable(SQLiteDatabase db,Set<String> inputs,String except) {
        try(Cursor c=db.rawQuery("SELECT orderref,data FROM shop_payment",null)) {
            while(c.moveToNext()) {
                if(c.getString(0).equals(except))continue;JSONObject other=read(c.getString(1));
                if(!retryable(other)&&!Collections.disjoint(inputs,OrderPaymentEvidence.inputIds(other.optJSONObject("intent"))))throw invalid();
            }
        }
    }
    JSONObject prepare(String key,MerchDb.Order o,String handle,JSONObject intent) {
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            if(!LIVE.contains(owner))throw invalid();JSONObject old=bound(db,key,o);
            MerchDb.Order current=paymentOrder(o.ref);
            if(current.paid||!MerchDb.PENDING.equals(current.status)||!binding(o).equals(store.getMeta("paymentready:"+o.ref,"")))throw invalid();
            if(!retryable(old)||!key.equals(o.ref))throw invalid();
            JSONObject exact=OrderPaymentEvidence.storedIntent(intent.toString());OrderPaymentEvidence.binds(exact,o.payaddr,o.amount,o.tokenid);
            Set<String> inputs=OrderPaymentEvidence.inputIds(exact);assertAvailable(db,inputs,key);
            JSONObject row=new JSONObject().put("binding",binding(o)).put("owner",owner).put("handle",handle).put("phase",PREPARED).put("intent",exact);
            save(db,key,row,old==null);db.setTransactionSuccessful();return row;
        }catch(JSONException e){throw invalid();}finally{db.endTransaction();}
    }
    void phase(String key,MerchDb.Order o,String handle,String next) {
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            JSONObject row=bound(db,key,o);if(row==null||!handle.equals(row.optString("handle"))||!owner.equals(row.optString("owner")))throw invalid();
            String old=row.optString("phase");
            if(POSTING.equals(next)) {
                MerchDb.Order current=paymentOrder(o.ref);
                if(current.paid||!MerchDb.PENDING.equals(current.status))throw invalid();
            }
            if(!(PREPARED.equals(old)&&LIVE.contains(owner)&&(POSTING.equals(next)||NOT_SUBMITTED.equals(next))||POSTING.equals(old)&&UNKNOWN.equals(next)))throw invalid();
            row.put("phase",next);save(db,key,row,false);db.setTransactionSuccessful();
        }catch(JSONException e){throw invalid();}finally{db.endTransaction();}
    }
    void receipt(String key,MerchDb.Order o,JSONObject reply,JSONObject chain) {
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            JSONObject row=bound(db,key,o);
            if(row==null||!Arrays.asList(POSTING,UNKNOWN,POSTED).contains(row.optString("phase")))throw invalid();
            OrderPaymentEvidence.referenceStamp(reply,o.ref);
            row.put("receipt",OrderPaymentEvidence.publicReceipt(reply,row.getJSONObject("intent"))).put("phase",chain==null?POSTED:CONFIRMED);
            if(chain!=null)row.put("inclusion",OrderPaymentEvidence.inclusion(chain));
            save(db,key,row,false);db.setTransactionSuccessful();
        }catch(JSONException e){throw invalid();}finally{db.endTransaction();}
    }
    void confirmSaved(String key,MerchDb.Order o,String txid,JSONObject chain) {
        JSONObject inclusion=OrderPaymentEvidence.inclusion(chain);SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            JSONObject row=bound(db,key,o);
            if(row==null||!POSTED.equals(row.optString("phase"))||!same(txid,row.optJSONObject("receipt").optString("txpowid")))throw invalid();
            row.put("phase",CONFIRMED).put("inclusion",inclusion);save(db,key,row,false);db.setTransactionSuccessful();
        }catch(JSONException e){throw invalid();}finally{db.endTransaction();}
    }

    static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid or unresolved payment record");}
    static boolean same(String a,String b){return a!=null&&b!=null&&a.equalsIgnoreCase(b);}
    /** Fresh detached payment view; the database buyer delivery address is never overwritten. */
    MerchDb.Order paymentOrder(String ref) {
        MerchDb.Order o=store.order(ref);if(o==null||!"buyer".equals(o.role))throw invalid();
        o.payaddr=store.getMeta("vendoraddr:"+ref,"");
        OrderPaymentEvidence.request(o.payaddr,o.amount,o.tokenid,o.ref);return o;
    }
    static String binding(MerchDb.Order o) {
        if(o==null)throw invalid();OrderPaymentEvidence.request(o.payaddr,o.amount,o.tokenid,o.ref);
        return new JSONArray().put(o.ref).put(o.counterparty).put(o.payaddr).put(o.items)
            .put(o.amount).put(o.tokenid).put(o.currency).toString();
    }
    private void fresh(MerchDb.Order expected) {
        if(!binding(expected).equals(binding(paymentOrder(expected.ref))))throw invalid();
    }
    private void meta(SQLiteDatabase db,String key,String value) {
        ContentValues v=new ContentValues();v.put("k",key);v.put("v",value);
        db.insertWithOnConflict("meta",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        if(!value.equals(store.getMeta(key,null)))throw invalid();
    }
    /** Reuse Inbox's SQLite transaction boundary: order and original address commit together. */
    boolean saveOrder(com.eurobuddha.merchshop.integratedcomms.MerchMessage message,String address) {
        OrderPaymentEvidence.request(address,message.amount,message.tokenid,message.ref);
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            if(!store.upsertOrder(message,"buyer",""))return false;
            meta(db,"vendoraddr:"+message.ref,address);db.setTransactionSuccessful();return true;
        }finally{db.endTransaction();}
    }
    void orderSent(String ref) {
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {meta(db,"paymentready:"+ref,binding(paymentOrder(ref)));db.setTransactionSuccessful();}
        finally{db.endTransaction();}
    }
    boolean available(MerchDb.Order o) {
        JSONObject record=load(o.ref,o);
        return record!=null||binding(o).equals(store.getMeta("paymentready:"+o.ref,""));
    }
    static JSONObject storedObject(String raw) {
        if (raw == null || raw.indexOf(0) >= 0) throw invalid();
        try {
            org.json.JSONTokener input = new org.json.JSONTokener(raw); Object value = input.nextValue();
            while (input.more()) {
                char c = input.next();
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') throw invalid();
            }
            if (!(value instanceof JSONObject)) throw invalid();
            return (JSONObject)value;
        } catch (org.json.JSONException e) { throw invalid(); }
    }
}
