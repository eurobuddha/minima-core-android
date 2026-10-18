package com.eurobuddha.merchinbox;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.HashSet;

/** Exact NFT Wallet coin-list checks; a failed read cannot establish absence or delivery. */
final class DeliveryCoinList {
    static final String INVALID = "Incomplete coin-list evidence. Delivery remains unconfirmed.";
    private DeliveryCoinList() {}
    static IllegalArgumentException invalid() { return new IllegalArgumentException(INVALID); }
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
}
