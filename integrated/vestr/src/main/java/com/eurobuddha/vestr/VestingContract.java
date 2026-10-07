package com.eurobuddha.vestr;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The vesting smart contract — KISS-VM script, state layout and vesting math, ported verbatim from the
 * vestr MiniDapp (minima-global/vestr, src/minima/libs/contracts/index.ts).
 *
 * IMPORTANT: this is the dapp's `cleanScript`, which is what vestr deploys (AppContext.tsx:79). The app
 * must `newscript` THIS string and use the RETURNED address (the repo's hardcoded `scriptaddress` is
 * stale). Verified on a live node 1.0.48.3 to deploy to
 *   0x3C432D5099AB27EA901079EF54D9A97AB4DB3BD1CFFF670296C31B7C83C1C8BE
 * The address is a deterministic hash of the cleaned script, so it matches the web dapp → contracts
 * created in either are visible/collectible in the other.
 */
public final class VestingContract {

    private VestingContract() {}

    public static final String CLEAN_SCRIPT =
        "LET unlockaddress=PREVSTATE(0) LET totallockedamount=PREVSTATE(1) LET startblock=PREVSTATE(2) " +
        "LET finalblock=PREVSTATE(3) LET minblockwait=PREVSTATE(4) ASSERT SAMESTATE(0 8) ASSERT PREVSTATE(199) EQ STATE(199) " +
        "IF @BLOCK GTE finalblock THEN IF VERIFYOUT(@INPUT unlockaddress @AMOUNT @TOKENID FALSE) THEN RETURN TRUE ENDIF ENDIF " +
        "IF @BLOCK LT startblock THEN RETURN FALSE ENDIF IF @COINAGE LT minblockwait THEN RETURN FALSE ENDIF " +
        "LET totalduration=finalblock-startblock IF totalduration LTE 0 THEN LET blockamount=@AMOUNT ELSE LET blockamount=SIGDIG(2(totallockedamount/totalduration)) ENDIF " +
        "LET owedamounttime=@BLOCK-startblock LET owedamountminima=owedamounttime*blockamount LET alreadycollected=totallockedamount-@AMOUNT " +
        "LET cancollect=SIGDIG(2(owedamountminima-alreadycollected)) IF cancollect LTE 0 THEN RETURN FALSE ENDIF " +
        "IF cancollect GT @AMOUNT THEN LET cancollect=@AMOUNT ENDIF LET payout=GETOUTAMT(@INPUT) " +
        "IF GETOUTADDR(@INPUT) NEQ unlockaddress THEN RETURN FALSE ENDIF IF GETOUTTOK(@INPUT) NEQ @TOKENID THEN RETURN FALSE ENDIF " +
        "IF payout GT cancollect THEN RETURN FALSE ENDIF IF GETOUTKEEPSTATE(@INPUT) NEQ FALSE THEN RETURN FALSE ENDIF " +
        "LET change=@AMOUNT-payout IF change LTE 0 THEN RETURN TRUE ENDIF RETURN VERIFYOUT(@INPUT+1 @ADDRESS change @TOKENID TRUE)";

    /** Off-chain math (run via {@code runscript}) computing the exact {@code cancollect}/{@code change}
     *  the contract will accept — uses SIGDIG(2) like the on-chain script, so it matches to the digit.
     *  prevstate {1,2,3,4,5}; globals @AMOUNT, @BLOCK=tip, @COINAGE=coin.created. (vestr checkMaths.) */
    public static final String CHECK_MATHS =
        "LET totallockedamount=PREVSTATE(1) LET startblock=PREVSTATE(2) LET finalblock=PREVSTATE(3) " +
        "LET minblockwait=PREVSTATE(4) LET mustwaitblocks=\"0\" LET mustwait= (@BLOCK - @COINAGE) GT \"0\" AND minblockwait GT (@BLOCK - @COINAGE) " +
        "LET contractexpired = @BLOCK GTE finalblock IF mustwait EQ TRUE THEN LET mustwaitblocks=minblockwait - (@BLOCK - @COINAGE) ENDIF " +
        "LET coinsage=@COINAGE LET cliffed=@BLOCK LT startblock LET totalduration=finalblock-startblock " +
        "IF totalduration LTE 0 THEN LET blockamount=@AMOUNT ELSE LET blockamount=SIGDIG(2 (totallockedamount/totalduration)) ENDIF " +
        "LET owedamounttime=@BLOCK-startblock LET owedamountminima=owedamounttime*blockamount LET alreadycollected=totallockedamount-@AMOUNT " +
        "LET cancollect=SIGDIG(2 (owedamountminima - alreadycollected)) IF cancollect GT @AMOUNT THEN LET cancollect=@AMOUNT ENDIF " +
        "LET change=@AMOUNT-cancollect LET totalsum = change + cancollect LET totalinput = @AMOUNT " +
        "IF contractexpired EQ TRUE THEN LET mustwait=FALSE ENDIF IF contractexpired EQ TRUE THEN LET cancollect=@AMOUNT ENDIF";

    // ---- state ports ----
    public static final int ST_UNLOCK_ADDR = 0;   // withdrawal address
    public static final int ST_TOTAL       = 1;   // total locked amount
    public static final int ST_START_BLOCK = 2;
    public static final int ST_END_BLOCK   = 3;
    public static final int ST_GRACE_BLOCKS= 4;   // min blocks between collections
    public static final int ST_CREATED_MS  = 5;
    public static final int ST_START_MS    = 6;
    public static final int ST_GRACE_HOURS = 7;
    public static final int ST_END_MS      = 8;
    public static final int ST_UID         = 199;

    public static final int SECONDS_PER_BLOCK = 50;   // Minima ~50s/block (used for date<->block)

    /** Grace-period options exactly as vestr (gracePeriod/index.tsx): label + hours. */
    public enum Grace {
        NONE("None", 0), DAILY("Daily", 24), WEEKLY("Weekly", 168), MONTHLY("Monthly", 720),
        QUARTERLY("Every 3 Months", 2190), HALFYEARLY("Every 6 Months", 4320), YEARLY("Yearly", 8640);
        public final String label; public final int hours;
        Grace(String l, int h) { label = l; hours = h; }
        /** grace in blocks = hours * 3600 / 50. */
        public long blocks() { return (long) hours * 3600 / SECONDS_PER_BLOCK; }
        public static Grace fromHours(int h) { for (Grace g : values()) if (g.hours == h) return g; return NONE; }
    }

    /** Convert a future date (ms) to an estimated block height: tip + (then - now) / 50s. */
    public static long blockHeightForDate(long tipBlock, long whenMs) {
        long deltaSec = (whenMs - System.currentTimeMillis()) / 1000L;
        return tipBlock + Math.round(deltaSec / (double) SECONDS_PER_BLOCK);
    }

    /** Calculator preview: tokens released per grace period (vestr calculateVestingSchedule). */
    public static BigDecimal paymentPerGrace(BigDecimal total, long startBlock, long endBlock, long graceBlocks) {
        BigDecimal length = new BigDecimal(endBlock - startBlock);
        if (length.signum() <= 0 || graceBlocks <= 0) return total;
        BigDecimal ratio = new BigDecimal(graceBlocks).divide(length, 20, RoundingMode.DOWN).multiply(total);
        return ratio.compareTo(total) < 0 ? ratio.setScale(2, RoundingMode.DOWN) : total;
    }

    /**
     * How much can be collected from a contract coin right now (mirrors the contract's cancollect math).
     * @param coinAmount the contract coin's current amount (remaining locked)
     * @param coinAge    @COINAGE of the coin (blocks since it last moved)
     */
    public static BigDecimal canCollect(long tip, long startBlock, long endBlock, long graceBlocks,
                                        BigDecimal total, BigDecimal coinAmount, long coinAge) {
        if (tip >= endBlock) return coinAmount;                 // expired ⇒ full
        if (tip < startBlock) return BigDecimal.ZERO;           // cliff
        if (coinAge < graceBlocks) return BigDecimal.ZERO;      // within grace
        long duration = endBlock - startBlock;
        BigDecimal blockAmount = duration <= 0 ? coinAmount
                : total.divide(new BigDecimal(duration), 20, RoundingMode.DOWN);
        BigDecimal owed = new BigDecimal(tip - startBlock).multiply(blockAmount);
        BigDecimal alreadyCollected = total.subtract(coinAmount);
        BigDecimal canCollect = owed.subtract(alreadyCollected);
        if (canCollect.signum() <= 0) return BigDecimal.ZERO;
        return canCollect.compareTo(coinAmount) > 0 ? coinAmount : canCollect.setScale(2, RoundingMode.DOWN);
    }
}
