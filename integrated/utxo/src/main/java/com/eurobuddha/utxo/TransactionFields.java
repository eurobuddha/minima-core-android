package com.eurobuddha.utxo;

import org.json.JSONObject;
import java.math.BigDecimal;

/** The ID, amount and address helpers reused from NFT BurialEvidence. */
final class TransactionFields {
    private TransactionFields() {}
    static IllegalArgumentException invalid() { return TransactionEvidence.invalid(); }
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
    static boolean addressIs(JSONObject coin, String destination) {
        if (coin == null || !Util.isValidAddress(destination)) return false;
        return Util.isValidHexId(destination) ? same(destination, id(coin, "address"))
                : destination.equals(coin.opt("miniaddress"));
    }
}
