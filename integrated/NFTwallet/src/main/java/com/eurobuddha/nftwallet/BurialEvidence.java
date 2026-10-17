package com.eurobuddha.nftwallet;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

/** Public chain evidence only. Missing data must never become an empty wallet or a completed burial. */
final class BurialEvidence {
    private BurialEvidence() {}
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Incomplete burial evidence"); }
    static boolean same(String a, String b) { return a != null && b != null && a.equalsIgnoreCase(b); }
    static String id(JSONObject value, String key) {
        Object raw = value == null ? null : value.opt(key);
        if (!(raw instanceof String) || !Util.isValidHexId((String) raw)) throw invalid();
        return (String) raw;
    }
    static String amount(JSONObject coin) {
        Object raw = coin == null ? null : coin.opt("tokenamount");
        if (!(raw instanceof String) || !Util.isValidAmount((String) raw)) throw invalid();
        BigDecimal amount = new BigDecimal((String) raw);
        if (amount.signum() <= 0) throw invalid();
        return amount.stripTrailingZeros().toPlainString();
    }
    static JSONArray coins(JSONObject reply, String tokenid) {
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))) throw invalid();
        JSONArray coins = reply.optJSONArray("response");
        if (coins == null) throw invalid();
        HashSet<String> ids = new HashSet<>();
        for (int i = 0; i < coins.length(); i++) {
            JSONObject coin = coins.optJSONObject(i);
            String key = id(coin, "coinid").toLowerCase(java.util.Locale.ROOT);
            if (!ids.add(key) || !same(tokenid, id(coin, "tokenid"))) throw invalid();
            id(coin, "address"); amount(coin);
        }
        return coins;
    }
    /** Same supported state values as StateNft, with strict shape/duplicate-port checks before hashing. */
    static String stateHash(JSONObject coin) {
        Object state = coin == null ? null : coin.opt("state");
        TreeMap<Integer,String> ports = new TreeMap<>();
        if (state instanceof JSONArray) {
            JSONArray a = (JSONArray) state;
            for (int i = 0; i < a.length(); i++) {
                JSONObject entry = a.optJSONObject(i);
                if (entry == null) throw invalid();
                add(ports, entry.opt("port"), entry.opt("data"));
            }
        } else if (state instanceof JSONObject) {
            JSONObject object = (JSONObject) state;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) { String port = keys.next(); add(ports, port, object.opt(port)); }
        } else throw invalid();
        if (!ports.containsKey(0)) throw invalid();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<Integer,String> entry : ports.entrySet()) {
                // Values cannot contain a newline; the supported state alphabet is checked in add().
                digest.update((entry.getKey()+":"+entry.getValue()+"\n").getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void add(TreeMap<Integer,String> ports, Object rawPort, Object rawValue) {
        if (!(rawPort instanceof String || rawPort instanceof Number)
                || !(rawValue instanceof String || rawValue instanceof Number)) throw invalid();
        String p = String.valueOf(rawPort), value = String.valueOf(rawValue);
        if (!p.matches("[0-9]{1,3}") || !StateNft.safeStateValue(value)) throw invalid();
        int port = Integer.parseInt(p);
        if (port > 255 || ports.put(port, value) != null) throw invalid();
    }
    static JSONObject intent(JSONObject coin, String tokenid) {
        if (!same(tokenid, id(coin,"tokenid"))) throw invalid();
        try {
            return new JSONObject().put("input",id(coin,"coinid")).put("output","")
                    .put("tokenid",tokenid).put("tokenamount",amount(coin)).put("statehash",stateHash(coin));
        } catch (org.json.JSONException e) { throw invalid(); }
    }
    /** txnpost uses response.body.txn; accept the existing TxnUtil wrapper too. No ack alone is proof. */
    static JSONObject receipt(JSONObject reply, JSONObject intent) {
        return receipt(reply, intent, StateNft.GRAVEYARD);
    }
    /** Hex is case-insensitive; Mx addresses are case-sensitive, as in UTXO address matching. */
    static boolean addressIs(JSONObject coin, String destination) {
        if (coin == null || !Util.isValidAddress(destination)) return false;
        return Util.isValidHexId(destination) ? same(destination, id(coin, "address"))
                : destination.equals(coin.opt("miniaddress"));
    }
    static JSONObject receipt(JSONObject reply, JSONObject intent, String destination) {
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))) throw invalid();
        JSONObject response=reply.optJSONObject("response");
        JSONObject txpow=response==null?null:response.optJSONObject("txpow");
        if(txpow==null)txpow=response;
        JSONObject body=txpow==null?null:txpow.optJSONObject("body");
        JSONObject txn=body==null?null:body.optJSONObject("txn");
        JSONArray inputs=txn==null?null:txn.optJSONArray("inputs"), outputs=txn==null?null:txn.optJSONArray("outputs");
        if(inputs==null||inputs.length()!=1||outputs==null||outputs.length()!=1)throw invalid();
        JSONObject input=inputs.optJSONObject(0), output=outputs.optJSONObject(0);
        if(!same(intent.optString("input"),id(input,"coinid"))
                || !same(intent.optString("tokenid"),id(input,"tokenid"))
                || !same(intent.optString("tokenid"),id(output,"tokenid"))
                || !addressIs(output,destination)
                || !intent.optString("tokenamount").equals(amount(input))
                || !intent.optString("tokenamount").equals(amount(output))
                || !Boolean.TRUE.equals(output.opt("storestate"))
                || !intent.optString("statehash").equals(stateHash(txn)))throw invalid();
        String outputid=id(output,"coinid");
        if(same(outputid,intent.optString("input"))||new java.math.BigInteger(outputid.substring(2),16).signum()==0)throw invalid();
        try{return new JSONObject(intent.toString()).put("output",outputid).put("address",id(output,"address"));}catch(org.json.JSONException e){throw invalid();}
    }
    static void journal(JSONArray entries, String tokenid) {
        if(entries==null)throw invalid();
        HashSet<String> inputs=new HashSet<>(),outputs=new HashSet<>();
        for(int i=0;i<entries.length();i++){
            JSONObject e=entries.optJSONObject(i);
            if(!inputs.add(id(e,"input").toLowerCase(java.util.Locale.ROOT))
                    || !same(tokenid,id(e,"tokenid")))throw invalid();
            amount(e);
            Object hash=e.opt("statehash"), out=e.opt("output");
            if(!(hash instanceof String)||!((String)hash).matches("[0-9a-f]{64}")||!(out instanceof String))throw invalid();
            if(!((String)out).isEmpty()&&!outputs.add(id(e,"output").toLowerCase(java.util.Locale.ROOT)))throw invalid();
        }
    }
    static boolean sameJournal(JSONArray a, JSONArray b, String tokenid) {
        try { return journalValues(a, tokenid).equals(journalValues(b, tokenid)); }
        catch (RuntimeException e) { return false; }
    }
    private static java.util.Map<String,String> journalValues(JSONArray entries, String tokenid) {
        journal(entries, tokenid);
        java.util.Map<String,String> values = new TreeMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            values.put(e.optString("input").toLowerCase(java.util.Locale.ROOT),
                    e.optString("output").toLowerCase(java.util.Locale.ROOT) + "\n" + amount(e) + "\n" + e.optString("statehash"));
        }
        return values;
    }

    static boolean confirmed(JSONObject receipt, JSONObject coin) {
        return confirmed(receipt, coin, StateNft.GRAVEYARD);
    }
    static boolean confirmed(JSONObject receipt, JSONObject coin, String destination) {
        try {
            return same(id(receipt,"output"),id(coin,"coinid"))
                    && same(id(receipt,"tokenid"),id(coin,"tokenid"))
                    && addressIs(coin,destination)
                    && amount(receipt).equals(amount(coin))
                    && Boolean.FALSE.equals(coin.opt("spent"))
                    && Boolean.TRUE.equals(coin.opt("storestate"))
                    && receipt.optString("statehash").equals(stateHash(coin));
        } catch(IllegalArgumentException e){return false;}
    }
}
