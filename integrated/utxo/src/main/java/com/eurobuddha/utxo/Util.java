package com.eurobuddha.utxo;

import org.json.JSONObject;

import java.math.BigDecimal;

public final class Util {

    public static final String MINIMA_TOKENID = "0x00";

    private Util() {}

    public static boolean isValidHexId(String id) {
        return id != null && (MINIMA_TOKENID.equals(id) || id.matches("^0x[0-9a-fA-F]{2,64}$"));
    }

    /**
     * A plain decimal amount as the node emits them. Amounts are interpolated into commands too,
     * so a chain-supplied "1 burn:9999" must never reach one.
     */
    public static boolean isValidAmount(String amt) {
        return amt != null && amt.matches("^[0-9]+(\\.[0-9]+)?$");
    }



    /** A Minima address: 0x + exactly 64 hex (32-byte script hash), or Mx + 40–118 alnum.
     *  Matches the dapp's validateAddress; a pre-filter before the node's checkaddress. */
    public static boolean isValidAddress(String a) {
        return a != null && a.matches("^(0x[0-9a-fA-F]{64}|Mx[A-Za-z0-9]{40,118})$");
    }

    /** Number of significant decimal places in an amount (0 for integers). */
    public static int decimalPlaces(BigDecimal bd) {
        return Math.max(0, bd.stripTrailingZeros().scale());
    }

    /** Shorten a long hex id/address for display: 0x1234…ABCD */
    public static String shorten(String s) {
        if (s == null) return "";
        if (s.length() <= 16) return s;
        return s.substring(0, 8) + "…" + s.substring(s.length() - 6);
    }

    public static boolean isMinima(String tokenid) {
        return tokenid == null || MINIMA_TOKENID.equals(tokenid);
    }

    /**
     * Minima "token name" can be a plain string, or a JSON object {name:..,url:..},
     * or (for raw coin entries) nested. Pull a human-readable name out of whatever we get.
     */
    public static String tokenName(Object token, String tokenid) {
        JSONObject object = TokenMeta.tokenObject(token);
        if (object != null) token = object;
        if (isMinima(tokenid)) return "Minima";
        if (token instanceof String) return (String) token;
        if (token instanceof JSONObject) {
            JSONObject t = (JSONObject) token;
            Object name = t.opt("name");
            JSONObject nameObject = TokenMeta.asObject(name);
            if (nameObject != null) name = nameObject;
            if (name instanceof JSONObject) {
                return ((JSONObject) name).optString("name", "Token");
            }
            if (name instanceof String && !((String) name).isEmpty()) {
                return (String) name;
            }
        }
        return "Token";
    }

    /** Same provenance rule as core TokenMeta: literal Token is distinct from absent metadata. */
    public static boolean tokenNameProvided(Object token, String tokenid) {
        JSONObject object = TokenMeta.tokenObject(token);
        if (object != null) token = object;
        if (isMinima(tokenid)) return true;
        if (token instanceof String) return !((String)token).isEmpty();
        if (token instanceof JSONObject) {
            Object name = ((JSONObject)token).opt("name");
            JSONObject nameObject = TokenMeta.asObject(name);
            if (nameObject != null) name = nameObject;
            if (name instanceof JSONObject) {
                JSONObject meta = (JSONObject)name;
                return meta.has("name") && !meta.isNull("name") && !meta.optString("name", "").isEmpty();
            }
            return name instanceof String && !((String)name).isEmpty();
        }
        return false;
    }

    /** Trim trailing zeros from a decimal amount string for tidy display. */
    public static String tidyAmount(String amt) {
        if (amt == null || amt.isEmpty()) return "0";
        if (!amt.contains(".")) return amt;
        String s = amt.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "0" : s;
    }
}
