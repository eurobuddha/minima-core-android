package com.eurobuddha.vestr;

import org.json.JSONObject;

import java.util.List;

/**
 * One collect: the build commands, txncheck and txnpost, sent strictly in order against one node txn id (no
 * Android here, so every decision has a unit test). The screen sends {@link #next()}, reports the node's reply
 * or its absence, and acts on the answer. Two rules decide what is owed to the node:
 * - before txnpost is sent, nothing has reached the chain: a failure, a missing reply or a closed screen all
 *   owe a {@code txndelete} of the half-built txn;
 * - once txnpost is sent, it may have gone through whatever the reply says or fails to say: no txndelete from
 *   a closed screen, and a missing reply is an UNKNOWN outcome, never a failure the user could retry on the same
 *   coin. Only the node's own answer to txnpost with status false is a refusal ({@link #refusedPost}); a status
 *   false the SDK made up because it could not read the reply is UNKNOWN too.
 */
final class CollectRun {

    enum Next { RUN, DONE, FAILED, INVALID, UNKNOWN }

    final String id;
    private final List<String> cmds;
    private int sent;
    private boolean posted, over;

    CollectRun(String id, List<String> cmds) { this.id = id; this.cmds = cmds; }

    /** The next command to send, or null after the last one. txnpost counts as issued from here on. */
    String next() {
        if (over || sent >= cmds.size()) return null;
        String cmd = cmds.get(sent++);
        if (cmd.startsWith("txnpost ")) posted = true;
        return cmd;
    }

    /** The node's complete reply to the command {@link #next()} returned last. */
    Next replied(JSONObject reply) {
        if (over) return Next.DONE;
        if (reply == null || !reply.optBoolean("status", false)) {
            over = true;
            return posted && !refusedPost(reply) ? Next.UNKNOWN : Next.FAILED;
        }
        if (cmds.get(sent - 1).startsWith("txncheck ") && !Contract.txnCheckValid(reply)) { over = true; return Next.INVALID; }
        if (sent >= cmds.size()) { over = true; return Next.DONE; }
        return Next.RUN;
    }

    /**
     * The node's own refusal of txnpost, after which nothing is mined: the status is the boolean false, nothing is left
     * pending, and the reply names the command, as the node names it on every refusal (CommandRunner): "txnpost" from
     * Command.getJSONReply when txnpost throws (Transaction not found, burn: needs ADMIN, TxPoW size too large..), or the
     * whole command line from the parameter and permission gates. A status false without it is not the node's answer
     * (the bundled SDK's "Failed to read large response: ..." and "Node returned an invalid JSON response"): the post may
     * have gone through.
     */
    static boolean refusedPost(JSONObject reply) {
        if (reply == null || !Boolean.FALSE.equals(reply.opt("status")) || reply.optBoolean("pending", false)) return false;
        String command = reply.optString("command", "");
        return command.equals("txnpost") || command.startsWith("txnpost ");
    }

    /** No complete reply (timeout, transport error, refused by the wrapper). */
    Next noReply() {
        if (over) return Next.DONE;
        over = true;
        return posted ? Next.UNKNOWN : Next.FAILED;
    }

    /** The command still owed when the screen goes away mid-run: the txndelete of a txn that was started and never
     *  handed to txnpost, unless the run already ended (a failed run deletes it itself). null = nothing owed. */
    String abandon() {
        if (over || posted || sent == 0) return null;
        over = true;
        return "txndelete id:" + id;
    }
}
