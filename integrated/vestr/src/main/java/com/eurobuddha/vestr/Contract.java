package com.eurobuddha.vestr;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * A vesting contract = a coin at the script address carrying the vesting state variables. Parsed from
 * the node's {@code coins} response (state is a {port,type,data} array, per the dapp's getStateVariable).
 */
public class Contract {

    public String coinid, tokenid, address, tokenName, tokenUrl;
    public BigDecimal amount;      // current remaining (coin amount)
    public BigDecimal total;       // state[1] — total originally locked
    public String unlockAddr;      // state[0]
    public long startBlock, endBlock, graceBlocks;   // state[2,3,4]
    public long createdMs, startMs, endMs;           // state[5,6,8]
    public int graceHours;         // state[7]
    public String uid;             // state[199]
    public long createdBlock;      // coin.created (block) — for coin age
    public JSONObject raw;         // source coin (exact state strings for the collect txn)

    public static Contract from(JSONObject coin) {
        Contract c = new Contract();
        c.raw = coin;
        c.coinid = coin.optString("coinid", "");
        c.tokenid = coin.optString("tokenid", "0x00");
        c.address = coin.optString("address", "");
        boolean minima = Util.isMinima(c.tokenid);
        c.amount = bd(minima ? coin.optString("amount", "0")
                : coin.optString("tokenamount", coin.optString("amount", "0")));
        Object tok = coin.opt("token");
        c.tokenName = minima ? "Minima" : Util.tokenName(tok, c.tokenid);
        if (tok instanceof JSONObject) c.tokenUrl = ((JSONObject) tok).optString("url", "");
        c.createdBlock = parseL(coin.optString("created", "0"));

        c.unlockAddr  = state(coin, 0);
        c.total       = bd(state(coin, 1));
        c.startBlock  = parseL(state(coin, 2));
        c.endBlock    = parseL(state(coin, 3));
        c.graceBlocks = parseL(state(coin, 4));
        c.createdMs   = parseL(state(coin, 5));
        c.startMs     = parseL(state(coin, 6));
        c.graceHours  = (int) parseL(state(coin, 7));
        c.endMs       = parseL(state(coin, 8));
        c.uid         = state(coin, 199);
        return c;
    }

    public boolean isMinima() { return Util.isMinima(tokenid); }
    public BigDecimal collected() { return total.subtract(amount).max(BigDecimal.ZERO); }

    /** Tokens collectible right now (mirrors the contract's on-chain math). */
    public BigDecimal canCollect(long tip) {
        long age = Math.max(0, tip - createdBlock);
        return VestingContract.canCollect(tip, startBlock, endBlock, graceBlocks, total, amount, age);
    }

    /** Blocks left in the current grace window (0 = collectible now). */
    public long graceBlocksRemaining(long tip) {
        long age = Math.max(0, tip - createdBlock);
        long left = graceBlocks - age;
        return left > 0 ? left : 0;
    }

    static String state(JSONObject coin, int port) {
        JSONArray st = coin.optJSONArray("state");
        if (st == null) return "";
        for (int i = 0; i < st.length(); i++) {
            JSONObject s = st.optJSONObject(i);
            if (s != null && s.optInt("port", -1) == port) return s.optString("data", "");
        }
        return "";
    }

    static BigDecimal bd(String s) { try { return new BigDecimal(s); } catch (Exception e) { return BigDecimal.ZERO; } }
    static long parseL(String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; } }
}
