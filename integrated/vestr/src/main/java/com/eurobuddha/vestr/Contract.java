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

    /** Same typed-field gate as PandaDEX's safeOrder; never interpolate raw unvalidated state. */
    public boolean safeForCommands() {
        if (!Util.isValidAddress(address) || !Util.isValidAddress(unlockAddr)
                || coinid == null || !coinid.matches("(?i)0x[0-9a-f]{64}")
                || tokenid == null || !tokenid.matches("(?i)0x(?:00|[0-9a-f]{64})")
                || !isContractId(uid)) return false;
        for (int port = 1; port <= 8; port++) {
            String value = state(raw, port);
            if (!value.matches("[0-9]{1,40}(\\.[0-9]{1,40})?")) return false;
        }
        return amount != null && amount.signum() > 0 && total.signum() > 0;
    }

    /**
     * The contract id (state port 199), re-written by the collect txn as {@code txnstate port:199 value:<uid>}
     * and checked on chain by {@code PREVSTATE(199) EQ STATE(199)}. The node stores a {@code 0x} state value as
     * HEX bytes and reports it back as {@code 0x} + upper-case, even-length hex (StateVariable / MiniData), so
     * any id the node itself reports re-writes to the same bytes. Accepted: {@code 0x} + 1 to 64 whole bytes.
     * This app before 2.2.76 made 16-byte ids ({@code randomHex(32)} = 32 hex chars), the Vestr MiniDapp makes
     * {@code hash} ids (32 bytes), the desktop 32 bytes. Rejected: {@code 0x} alone (the node turns it into a
     * blank value txnstate refuses), odd length (never node output), anything else (never interpolated).
     */
    static boolean isContractId(String uid) {
        return uid != null && uid.matches("(?i)0x(?:[0-9a-f]{2}){1,64}");
    }

    public boolean isMinima() { return Util.isMinima(tokenid); }
    public BigDecimal collected() { return total.subtract(amount).max(BigDecimal.ZERO); }

    /** Tokens collectible right now (mirrors the contract's on-chain math). */
    public BigDecimal canCollect(long tip) {
        long age = Math.max(0, tip - createdBlock);
        return VestingContract.canCollect(tip, startBlock, endBlock, graceBlocks, total, amount, age);
    }

    /**
     * What the collect txn pays out, from the variables of {@code runscript} on CHECK_MATHS; [0] = payout to
     * the unlock address, [1] = change back to the contract (0 = no change output). CHECK_MATHS computes its
     * {@code change} BEFORE an expired contract's {@code cancollect} is raised to @AMOUNT, so at and just
     * after the end block it reports change > 0 next to a full payout: outputs above the input, a txn the
     * chain drops after txnpost already "succeeded". Change is therefore @AMOUNT - payout, as the on-chain
     * script computes it. Within the grace period ({@code mustwait}) nothing is collectible: the script
     * refuses @COINAGE below the minimum wait (the MiniDapp refuses it too), see {@link #inGrace}.
     */
    BigDecimal[] collectPlan(JSONObject vars, long tip) {
        BigDecimal payout = vars == null || "TRUE".equals(vars.optString("mustwait")) || inGrace(tip)
                ? BigDecimal.ZERO : bd(vars.optString("cancollect", "0")).max(BigDecimal.ZERO).min(amount);
        return new BigDecimal[]{payout, amount.subtract(payout)};
    }

    /**
     * The script's {@code IF @COINAGE LT minblockwait THEN RETURN FALSE}, which it skips from the final block on
     * (the expired branch pays the whole coin first). CHECK_MATHS's {@code mustwait} misses a coin of age 0 (it
     * tests age GT 0), so a change coin was offered a collect in the block that created it, which the chain
     * refuses. The age is taken at the tip, as CHECK_MATHS, {@code txncheck} and the node's own relay check
     * (NIOMessage: a failing non-monotonic txn is kept but never forwarded) all run the script with
     * {@code @BLOCK} = the tip: a collect only the next block would accept never leaves the phone.
     */
    boolean inGrace(long tip) {
        BigDecimal at = BigDecimal.valueOf(tip);
        return at.compareTo(bd(state(raw, 3))) < 0
                && at.subtract(BigDecimal.valueOf(createdBlock)).compareTo(bd(state(raw, 4))) < 0;
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
