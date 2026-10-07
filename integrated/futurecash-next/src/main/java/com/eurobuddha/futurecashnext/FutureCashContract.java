package com.eurobuddha.futurecashnext;

/**
 * The FutureCash smart contract — KISS-VM script + state layout, taken VERBATIM from the FutureCash
 * MiniDapp (verified identical across the 2.6.2 source and the latest 2.7.1 build). Registering this exact
 * script with {@code newscript} yields the canonical FutureCash address — a deterministic hash of the
 * cleaned script — so coins created here are interoperable with the web dapp (and vice-versa).
 *
 * It's a single-shot time-lock: a locked coin can be spent once the chain reaches the future block
 * (or the coin has aged enough), and only if the whole amount goes to the recipient stored in state[2].
 */
public final class FutureCashContract {

    private FutureCashContract() {}

    /** The contract — register this verbatim; the returned address is the shared FutureCash address. */
    public static final String SCRIPT =
        "RETURN (@BLOCK GTE PREVSTATE(1) OR @COINAGE GTE PREVSTATE(4)) AND VERIFYOUT(@INPUT PREVSTATE(2) @AMOUNT @TOKENID FALSE)";

    /**
     * The canonical FutureCash address — where every FutureCash / Maximize stake on the chain actually sits.
     * It is not a guess: core itself hardcodes it inside the bond covenant
     * ({@code BondServer.BOND_SCRIPT}, which sets {@code fcaddress} to the same value as
     * {@code KNOWN_ADDRESS} below), and the MiniDapp carries the same
     * constant as its {@code KNOWN_FC_ADDRESSES} backstop.
     *
     * We scan it ALONGSIDE whatever address this node derives, because the address is a hash of the exact
     * script TEXT — {@code Wallet.addScript} does {@code new Address(zScript)} with no cleaning, so registering
     * with {@code clean:true} (what the MiniDapp does) and {@code clean:false} (what we do) can hash to
     * different addresses. Relying on the local derivation alone is what lets a real stake stay invisible.
     */
    public static final String KNOWN_ADDRESS =
        "0xEA8823992AB3CEBBA855D68006F0D05B0C4838FE55885375837D90F98954FA13";

    // ---- state ports (set on `send`, exactly as the dapp) ----
    public static final int ST_RESERVED    = 0;   // "0xFF"
    public static final int ST_FUTUREBLOCK = 1;   // unlock block height
    public static final int ST_RECIPIENT   = 2;   // who can collect (0x hex address)
    public static final int ST_CREATED_MS  = 3;   // creation timestamp (ms) — display only
    public static final int ST_COINAGE     = 4;   // alt unlock: blocks since creation (= futureBlock − tip at create)

    public static final int SECONDS_PER_BLOCK = 50;   // Minima ~50s/block (date <-> block)

    /** Convert a future date (ms) to an estimated block height: tip + (then − now) / 50s. */
    public static long blockForDate(long tipBlock, long whenMs) {
        long deltaSec = (whenMs - System.currentTimeMillis()) / 1000L;
        return tipBlock + Math.round(deltaSec / (double) SECONDS_PER_BLOCK);
    }

    /** Matured (spendable) — exactly the on-chain unlock: {@code @BLOCK GTE state1 OR @COINAGE GTE state4}
     *  (no extra guard, so the client's "Ready" can never disagree with what the script will accept). */
    public static boolean matured(long tip, long coinAgeBlocks, long futureBlock, long coinageThreshold) {
        return tip >= futureBlock || coinAgeBlocks >= coinageThreshold;
    }
}
