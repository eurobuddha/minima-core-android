package com.eurobuddha.merchstudio;
import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.TreeMap;
/** Validate a complete node snapshot before mutating the editable catalog.
 * Coin shape/identity gates are reused from NFT Wallet BurialEvidence. */
final class StudioData {
    private StudioData() {}
    static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid node result. Outcome remains unknown."); }
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

    static void state(JSONObject coin) {
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
        // Empty/missing edition state is an unstamped coin. The existing collection
        // loop skips it, and replayableState separately gates listable pieces.
    }
    private static void add(TreeMap<Integer,String> ports, Object rawPort, Object rawValue) {
        if (!(rawPort instanceof String || rawPort instanceof Number)
                || !(rawValue instanceof String || rawValue instanceof Number)) throw invalid();
        String p = String.valueOf(rawPort), value = String.valueOf(rawValue);
        if (!p.matches("[0-9]{1,3}")) throw invalid();
        int port = Integer.parseInt(p);
        if (port > 255 || ports.put(port, value) != null) throw invalid();
    }

    static JSONArray balances(JSONObject reply) {
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))) throw invalid();
        JSONArray rows=reply.optJSONArray("response");
        if (rows==null) throw invalid();
        HashSet<String> ids=new HashSet<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);
            if(!ids.add(id(row,"tokenid").toLowerCase(java.util.Locale.ROOT))) throw invalid();
            Object token=row.opt("token");
            if(!(token instanceof String) && !(token instanceof JSONObject)) throw invalid();
            // The bundled node's balance command emits all four as plain decimal strings.
            for(String field:new String[]{"confirmed","unconfirmed","sendable","total"}) {
                Object value=row.opt(field);
                if(!(value instanceof String)||!Util.isValidAmount((String)value)) throw invalid();
            }
        }
        return rows;
    }
    static int pieceIndex(JSONObject coin,int collectionSize) {
        String value=StateNft.stamped(coin);
        if(value==null||!value.matches("[0-9]+"))return -1;
        try {
            int index=new BigDecimal(value).intValueExact();
            return index>0 && (collectionSize<=0||index<=collectionSize) ? index : -1;
        } catch(ArithmeticException outsideRange){return -1;}
    }
    static JSONArray collection(JSONObject reply,String tokenid,int size) {
        JSONArray rows=coins(reply,tokenid);
        HashSet<Integer> indices=new HashSet<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject coin=rows.optJSONObject(i);
            if(StateNft.isBuried(coin))continue;
            state(coin);
            int index=pieceIndex(coin,size);
            if(index>0&&StateNft.replayableState(coin)&&!indices.add(index))throw invalid();
        }
        return rows;
    }
}
