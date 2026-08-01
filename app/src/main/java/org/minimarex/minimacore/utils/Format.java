package org.minimarex.minimacore.utils;

import org.minima.utils.json.JSONObject;

import java.math.BigDecimal;

/**
 * Display formatting for wallet amounts, hashes and timestamps.
 *
 * Ported from the AtomiX companion app (apks/atomix Util.tidyAmount / MainActivity.shortAddr)
 * so the two apps read the same. Pure statics, no Android dependencies.
 */
public class Format {

    private Format(){}

    /** Trim trailing zeros from a decimal amount string for tidy display. */
    public static String tidyAmount(String zAmount){
        if(zAmount == null || zAmount.isEmpty()){
            return "0";
        }
        if(!zAmount.contains(".")){
            return zAmount;
        }

        String s = zAmount.replaceAll("0+$", "");
        if(s.endsWith(".")){
            s = s.substring(0, s.length()-1);
        }

        return s.isEmpty() ? "0" : s;
    }

    /**
     * First 8 + ellipsis + last 6, for a hash shown in a scannable list row.
     *
     * NEVER use this in a detail field or anywhere the value is meant to be copied -
     * the whole point of the coin detail modal is that ids arrive byte-exact.
     */
    public static String shortHash(String zHash){
        if(zHash == null || zHash.length() < 12){
            return zHash;
        }
        return zHash.substring(0,8) + "…" + zHash.substring(zHash.length()-6);
    }

    /**
     * Coarse "how stale is this" bucket. Coarse on purpose - the wallet re-renders this
     * every 10s and fine-grained seconds would visibly jitter.
     */
    public static String ago(long zWhen){
        if(zWhen <= 0){
            return "never";
        }

        long secs = (System.currentTimeMillis() - zWhen) / 1000;
        if(secs < 0){
            secs = 0;
        }

        if(secs < 5){       return "just now";          }
        if(secs < 60){      return secs+"s ago";        }
        if(secs < 3600){    return (secs/60)+"m ago";   }

        return (secs/3600)+"h ago";
    }

    /**
     * The amount of a coin in the units the balance is denominated in.
     *
     * A token coin carries both the raw Minima-scaled `amount` and the human `tokenamount`;
     * `balance.confirmed` is in token units, so summing raw `amount` would make the coin
     * modal's cross-check look broken for every non-Minima token.
     */
    public static String coinAmount(JSONObject zCoin){
        if(zCoin == null){
            return "0";
        }

        Object tokenamount = zCoin.get("tokenamount");
        if(tokenamount != null){
            return String.valueOf(tokenamount);
        }

        Object amount = zCoin.get("amount");
        return amount == null ? "0" : String.valueOf(amount);
    }

    /**
     * a - b as an exact decimal, clamped at zero. Returns an em dash if either side
     * is missing or not a number - this feeds the "locked" figure on the wallet card,
     * where a wrong number is worse than no number.
     */
    public static String subtract(String zA, String zB){
        try{
            BigDecimal diff = new BigDecimal(zA).subtract(new BigDecimal(zB));
            if(diff.signum() < 0){
                diff = BigDecimal.ZERO;
            }
            return tidyAmount(diff.toPlainString());

        }catch(Exception exc){
            return "—";
        }
    }
}
