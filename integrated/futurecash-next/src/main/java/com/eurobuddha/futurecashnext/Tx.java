package com.eurobuddha.futurecashnext;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Building and posting a transaction.
 *
 * <p>The sequence is the MiniDapp engine's, verbatim: create, input, output, basics, post. Two
 * things remain important with the embedded node: each build step is checked before the next
 * command is sent, and async mining is not confirmation. A complete command result carries a
 * Boolean status. Missing/malformed status is an unknown outcome, never a successful post.
 *
 * <p>The sibling native apps use the other valid shape — {@code txninput … scriptmmr:true} with NO
 * {@code txnbasics} (see {@code apks/futurecash CollectActivity}, {@code apks/openly OpenlyTxn:454}),
 * which attaches the script and MMR proof directly instead of letting the node do it. Either works;
 * mixing them does not. This app follows the MiniDapp because that is the sequence proven against
 * the covenant on a live node with live stakes.
 *
 * <p>Every path out of here runs {@code txndelete}, success or failure. Classic 2.7.1 chains the
 * whole thing as one command, so a failure at any step aborts before the delete and leaks the
 * half-built transaction into the node's table.
 */
public final class Tx {

    private static final AtomicLong SEQ = new AtomicLong(0);

    private Tx() {}

    /** Collision-free per-process transaction id (classic used Math.random(), which can repeat). */
    public static String id(String kind) {
        return "FC" + kind + System.currentTimeMillis() + "_" + (SEQ.incrementAndGet() % 1000000);
    }

    public static final class Result {
        public final boolean ok;
        public final String error;
        public final boolean notPaired;   // the node parked it for approval / we aren't enabled

        private Result(boolean ok, String error, boolean notPaired) {
            this.ok = ok; this.error = error; this.notPaired = notPaired;
        }
        public static Result ok() { return new Result(true, null, false); }
        public static Result err(String e) { return new Result(false, e, false); }
        public static Result unpaired(String e) { return new Result(false, e, true); }
    }

    /**
     * Run the steps in order, deleting the transaction afterwards whatever happens.
     *
     * <p>{@code %ID%} in a step stands in for the transaction id. The LAST step is treated as the
     * post (async-mined; see the class comment); every earlier step must report success.
     */
    private static Result run(Node node, String kind, List<String> steps) {
        final String id = id(kind);
        try {
            for (int i = 0; i < steps.size(); i++) {
                final String cmd = steps.get(i).replace("%ID%", id);
                final JSONObject r = node.cmd(cmd);

                if (r == null) return Result.err("Minima Core didn't respond (at " + stage(cmd) + ")");
                if (Node.unreadable(r)) {
                    // The command almost certainly RAN — we just can't read the reply. Say exactly
                    // that, rather than reporting a failure that probably didn't happen.
                    return Result.err("Couldn't read Minima Core's reply to " + stage(cmd)
                            + " — it may have succeeded. Check the Activity log before retrying.");
                }
                if (Node.pending(r)) {
                    return Result.unpaired("The embedded node did not authorize the request. Check its status before retrying.");
                }
                // Both build and post must return a complete, successful node envelope.
                if (!r.optBoolean("status", false)) {
                    final String e = r.optString("error", "");
                    return Result.err((e.isEmpty() ? "failed" : e) + " (at " + stage(cmd) + ")");
                }
            }
            return Result.ok();
        } finally {
            node.cmd("txndelete id:" + id);
        }
    }

    /**
     * Refuse to build a command from malformed values.
     *
     * <p>The destination and token id of a collect come from the coin's ON-CHAIN state, and anyone
     * can put arbitrary state on a coin at the shared covenant address. VERIFYOUT would reject a bad
     * output on-chain anyway, but unvalidated data must never reach the node's command parser —
     * Minima chains commands on ';' (see the class comment).
     *
     * @return an error Result if anything is malformed, or null when all parts are well-formed
     */
    private static Result checkParts(String coinid, String address, String amount, String tokenid) {
        if (!Node.validHexAddr(coinid)) return Result.err("Refusing to spend a malformed coin id.");
        if (!Node.validAddr(address)) return Result.err("This payment's destination looks malformed — refusing.");
        if (!Node.validHexAddr(tokenid)) return Result.err("This payment's token id looks malformed — refusing.");
        if (amount == null || !amount.matches("^[0-9]+(\\.[0-9]+)?$")) {
            return Result.err("This payment's amount looks malformed — refusing.");
        }
        // ...and never post a ZERO output. Node.outAmount falls back to "0" when the field it wants is
        // absent (a token coin with no `tokenamount`), which would build a transaction paying nothing
        // while consuming the whole coin. VERIFYOUT would reject it on-chain, so it costs a wasted
        // attempt rather than the funds — but it is a transaction that should never leave here.
        if (amount.matches("^0(\\.0+)?$")) {
            return Result.err("Refusing to post a zero-amount output — the coin's amount didn't parse.");
        }
        return null;
    }

    /** First word of the command — names the stage without dumping a whole command line into the log. */
    private static String stage(String cmd) {
        final int sp = cmd.indexOf(' ');
        return sp > 0 ? cmd.substring(0, sp) : cmd;
    }

    /**
     * Collect: spend the stake coin to its pinned payout.
     *
     * <p>No signature — the covenant has no SIGNEDBY, and VERIFYOUT pins the destination, so a
     * collect can only ever pay the address committed at lock time. That is what makes collecting
     * WOTS-safe in itself.
     */
    public static Result collect(Node node, String coinid, String payout, String amount, String tokenid) {
        final Result bad = checkParts(coinid, payout, amount, tokenid);
        if (bad != null) return bad;
        final List<String> steps = new ArrayList<>();
        steps.add("txncreate id:%ID%");
        steps.add("txninput id:%ID% coinid:" + coinid);
        steps.add("txnoutput id:%ID% address:" + payout + " amount:" + amount
                + " tokenid:" + tokenid + " storestate:false");
        steps.add("txnbasics id:%ID%");   // attaches the script + MMR proof; NOT chained with the post
        steps.add("txnpost id:%ID%");
        return run(node, "COL", steps);
    }

    /**
     * Sweep: move a collected coin off its (reused) address to safety.
     *
     * <p>Also a whole-coin single-shot spend, so it needs no {@code txnbasics} either — it just
     * needs a signature, which the collect doesn't.
     *
     * <p>Signs with the address's REAL key, taken from its SIGNEDBY script, rather than
     * {@code publickey:auto}. Auto reads the script row's pubkey, which retiring an address nulls to
     * 0x00 — so auto cannot sign a retired address, while the explicit key is still in the keys
     * table and works either way.
     */
    public static Result sweep(Node node, String coinid, String dest, String amount,
                               String tokenid, String signKey) {
        final Result bad = checkParts(coinid, dest, amount, tokenid);
        if (bad != null) return bad;
        if (!"auto".equals(signKey) && !Node.validHexAddr(signKey)) {
            return Result.err("Refusing to sign with a malformed public key.");
        }
        final List<String> steps = new ArrayList<>();
        steps.add("txncreate id:%ID%");
        steps.add("txninput id:%ID% coinid:" + coinid);
        steps.add("txnoutput id:%ID% address:" + dest + " amount:" + amount
                + " tokenid:" + tokenid + " storestate:false");
        steps.add("txnsign id:%ID% publickey:" + signKey);
        steps.add("txnbasics id:%ID%");   // sign first, then basics — the order apks/openly uses
        steps.add("txnpost id:%ID%");
        return run(node, "SWP", steps);
    }
}
