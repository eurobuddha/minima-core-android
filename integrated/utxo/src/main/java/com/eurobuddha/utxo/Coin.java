package com.eurobuddha.utxo;

import org.json.JSONObject;

/** A single UTXO as returned by the node's "coins relevant:true" command. */
public class Coin {

    public String coinid;
    public String address;
    public String miniaddress;
    public String tokenid;
    public String amount;       // human-readable (Minima "amount" or token "tokenamount")
    public String rawAmount;
    public boolean confirmed = true;
    public boolean spent;
    public String tokenName;
    public boolean tokenNameProvided = true;
    // The node's "coins" command returns confirmed, unspent coins only (verified against the node's
    // command classes) — there is no per-coin confirmation flag. Spendability is gated by the sendable query.
    public boolean sendable = false;   // set from "coins relevant:true sendable:true"

    public static Coin from(JSONObject c) {
        Coin x = new Coin();
        x.coinid      = c.optString("coinid", "");
        x.address     = c.optString("address", "");
        x.miniaddress = c.optString("miniaddress", "");
        x.tokenid     = c.optString("tokenid", "");

        boolean minima = Util.isMinima(x.tokenid);
        x.rawAmount = c.optString("amount", "");
        x.spent = c.has("spent") && !Boolean.FALSE.equals(c.opt("spent"));
        x.amount    = minima ? c.optString("amount", "0")
                             : c.optString("tokenamount", "");
        x.tokenName = Util.tokenName(c.opt("token"), x.tokenid);
        x.tokenNameProvided = Util.tokenNameProvided(c.opt("token"), x.tokenid);
        return x;
    }
    public boolean isWellFormed() {
        return Util.isValidHexId(coinid)
                && Util.isValidHexId(tokenid)
                && Util.isValidAmount(amount)
                && Util.isValidAmount(rawAmount)
                && (address == null || address.isEmpty() || Util.isValidHexId(address));
    }

}
