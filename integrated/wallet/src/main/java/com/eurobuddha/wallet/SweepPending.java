package com.eurobuddha.wallet;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.json.JSONObject;
import org.json.JSONArray;

/** App-private pending sweep records. Reuses the vault's acknowledged BlobStore boundary.
 * Signed bytes contain public transaction data only. A timeout never releases inputs. */
final class SweepPending {
    private final SeedVault.BlobStore store;
    SweepPending(SeedVault.BlobStore store){this.store=store;}
    SweepPending(Context context){this(new SeedVault.BlobStore(){
        final SharedPreferences prefs=context.getApplicationContext().getSharedPreferences("wallet_sweep_pending",Context.MODE_PRIVATE);
        public boolean contains(){return prefs.contains("records");}
        public String read(){return prefs.getString("records",null);}
        public void write(String value){
            String previous=read();
            if(!prefs.edit().putString("records",value).commit()){
                // SharedPreferences may update memory even if disk commit fails (SalonStore rule).
                prefs.edit().putString("records",previous).commit();
                throw new IllegalStateException("Could not save pending sweep");
            }
        }
    });}
    private static List<String> keys(JSONObject object){
        List<String> keys=new ArrayList<>();java.util.Iterator<String> it=object.keys();while(it.hasNext())keys.add(it.next());return keys;
    }
    List<TxnFactory.BuiltTxn> savedTransactions(){synchronized(SweepPending.class){
        List<TxnFactory.BuiltTxn> result=new ArrayList<>();JSONObject root=read();
        for(String id:keys(root))try{
            JSONObject saved=root.getJSONObject(id);
            org.minima.objects.base.MiniData data=new org.minima.objects.base.MiniData(saved.getString("signed"));
            org.minima.database.userprefs.txndb.TxnRow row=org.minima.database.userprefs.txndb.TxnRow.convertMiniDataVersion(data);
            if(row==null||!data.isEqual(org.minima.objects.base.MiniData.getMiniDataVersion(row))
                ||!row.getID().matches("[A-Za-z0-9_]+")||!id.equalsIgnoreCase(row.getTransaction().getTransactionID().to0xString()))throw new IllegalStateException();
            Set<String> expected=new HashSet<>(),actual=new HashSet<>();JSONArray ids=saved.getJSONArray("coins");
            for(int i=0;i<ids.length();i++)expected.add(ids.getString(i).toLowerCase(Locale.ROOT));
            for(org.minima.objects.Coin coin:row.getTransaction().getAllInputs())if(!actual.add(coin.getCoinID().to0xString().toLowerCase(Locale.ROOT)))throw new IllegalStateException();
            if(!actual.equals(expected)||row.getWitness().getAllSignatures().isEmpty())throw new IllegalStateException();
            result.add(new TxnFactory.BuiltTxn(row.getID(),row.getTransaction(),row.getWitness(),data,row.getWitness().getAllSignatures().size()));
        }catch(Exception error){throw new IllegalStateException("Saved sweep transaction is unreadable",error);}
        return result;
    }}
    private JSONObject read(){
        try {
            String raw=store.read();if(raw==null){if(store.contains())throw new IllegalStateException();return new JSONObject();}
            org.json.JSONTokener input=new org.json.JSONTokener(raw);Object value=input.nextValue();
            while(input.more())if(!Character.isWhitespace(input.next()))throw new IllegalStateException();
            if(!(value instanceof JSONObject))throw new IllegalStateException();
            JSONObject root=(JSONObject)value;
            for(String id:keys(root)){
                if(!validId(id))throw new IllegalStateException();JSONObject row=root.getJSONObject(id);
                JSONArray coins=row.getJSONArray("coins");if(coins.length()==0)throw new IllegalStateException();
                Set<String> seen=new HashSet<>();
                for(int i=0;i<coins.length();i++){String coin=coins.getString(i);if(!validId(coin)||!seen.add(coin.toLowerCase(Locale.ROOT)))throw new IllegalStateException();}
                if(!(row.get("signed") instanceof String)||!row.getString("signed").matches("0x[0-9a-fA-F]+"))throw new IllegalStateException();
                String proof=row.getString("txpow");if(!proof.isEmpty()&&!validId(proof))throw new IllegalStateException();
            }
            return root;
        }catch(Exception error){throw new IllegalStateException("Pending sweep record is unreadable",error);}
    }
    Set<String> coins(){synchronized(SweepPending.class){
        Set<String> result=new HashSet<>();JSONObject root=read();
        for(String id:keys(root)){JSONArray a=root.optJSONObject(id).optJSONArray("coins");for(int i=0;i<a.length();i++)result.add(a.optString(i).toLowerCase(Locale.ROOT));}
        return result;
    }}
    Map<String,String> transactions(){synchronized(SweepPending.class){
        Map<String,String> result=new LinkedHashMap<>();JSONObject root=read();
        for(String id:keys(root))result.put(id,root.optJSONObject(id).optString("txpow"));return result;
    }}
    void reserve(TxnFactory.BuiltTxn built){synchronized(SweepPending.class){try{
        JSONObject root=read();String id=built.getTransactionID().to0xString();JSONArray coins=new JSONArray();Set<String> pending=coins();
        for(org.minima.objects.Coin coin:built.getTransaction().getAllInputs()){
            String coinId=coin.getCoinID().to0xString();if(pending.contains(coinId.toLowerCase(Locale.ROOT)))throw new IllegalStateException("Coin already has a pending sweep");coins.put(coinId);
        }
        if(coins.length()==0||root.has(id))throw new IllegalStateException("Invalid sweep transaction");
        root.put(id,new JSONObject().put("coins",coins).put("signed",built.getImportHex()).put("txpow",""));
        String saved=root.toString();
        if(saved.length()>8*1024*1024)throw new IllegalStateException("Confirm the pending batches before preparing more");
        store.write(saved);
    }catch(org.json.JSONException error){throw new IllegalStateException(error);}}}
    void receipt(String transaction,JSONObject reply){synchronized(SweepPending.class){try{
        String proof=receiptId(transaction,reply);JSONObject root=read();JSONObject row=root.getJSONObject(transaction);
        row.put("txpow",proof);store.write(root.toString());
    }catch(org.json.JSONException error){throw new IllegalStateException(error);}}}
    void confirmed(String transaction,String proof,JSONObject reply){synchronized(SweepPending.class){
        if(!included(reply))throw new IllegalStateException("Sweep inclusion not confirmed");
        JSONObject root=read();JSONObject row=root.optJSONObject(transaction);
        if(row==null||!proof.equalsIgnoreCase(row.optString("txpow"))||!validId(proof))throw new IllegalStateException("Sweep receipt changed");
        root.remove(transaction);store.write(root.toString());
    }}
    static boolean validId(String id){return id!=null&&id.matches("0x[0-9a-fA-F]{64}")&&new java.math.BigInteger(id.substring(2),16).signum()>0;}
    /** Reuse NFT wallet's exact transaction identity and strict current-chain depth checks. */
    static String receiptId(String transaction,JSONObject reply){
        if(reply==null||!Boolean.TRUE.equals(reply.opt("status")))throw new IllegalStateException("Invalid sweep receipt");
        JSONObject value=reply.optJSONObject("response");
        if(value==null||(value.has("txnpost")&&!Boolean.TRUE.equals(value.opt("txnpost"))))throw new IllegalStateException("Invalid sweep receipt");
        JSONObject proof=value.optJSONObject("txpow");if(proof==null)proof=value;
        JSONObject body=proof.optJSONObject("body"),txn=body==null?null:body.optJSONObject("txn");
        String id=proof.optString("txpowid");
        if(!validId(transaction)||!validId(id)||txn==null||!transaction.equalsIgnoreCase(txn.optString("transactionid")))throw new IllegalStateException("Sweep receipt does not match signed transaction");
        return id;
    }
    static boolean included(JSONObject reply){try{
        if(reply==null||!Boolean.TRUE.equals(reply.opt("status")))return false;
        JSONObject value=reply.optJSONObject("response");if(value==null||!Boolean.TRUE.equals(value.opt("found"))||!validId(value.optString("blockid")))return false;
        java.math.BigInteger[] n=new java.math.BigInteger[3];String[] names={"block","tip","confirmations"};
        for(int i=0;i<3;i++){Object raw=value.opt(names[i]);if(!(raw instanceof String)||!((String)raw).matches("[0-9]{1,30}"))return false;n[i]=new java.math.BigInteger((String)raw);}
        return n[0].signum()>0&&n[2].compareTo(java.math.BigInteger.valueOf(3))>=0&&n[1].subtract(n[0]).equals(n[2]);
    }catch(RuntimeException invalid){return false;}}
}
