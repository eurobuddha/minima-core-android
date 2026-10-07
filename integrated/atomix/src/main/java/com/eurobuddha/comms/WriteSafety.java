package com.eurobuddha.atomix.integratedcomms;

import android.content.SharedPreferences;
import org.json.JSONObject;

/** PandaPools NodeApi's persisted interrupted-write protocol, shared by all AtomiX SDK instances. */
final class WriteSafety {
    private static String active = "";
    private WriteSafety() {}

    static boolean writesFunds(String command) {
        String verb = command == null ? "" : command.trim().split("\\s+", 2)[0];
        return verb.equals("sign") || verb.equals("txnsign") || verb.equals("txnpost") || verb.equals("send")
                || verb.equals("consolidate") || verb.equals("tokencreate");
    }
    static boolean completeReply(JSONObject reply) {
        if (reply == null || Boolean.TRUE.equals(reply.opt("pending"))) return false;
        Object response = reply.opt("response");
        // PandaPools NodeApi.isTooLong: the node may replace a write result with an over-limit stub.
        if (Boolean.FALSE.equals(reply.opt("status")) && response instanceof String) {
            String text = ((String) response).toLowerCase(java.util.Locale.ROOT);
            if (text.contains("too long") || text.contains("max(256000)")) return false;
        }
        return reply.opt("status") instanceof Boolean || Boolean.FALSE.equals(reply.opt("enabled"));
    }
    // Why a write was left pending. Recorded at the moment it happens, because the latch outlives
    // the logcat buffer: the S10+ rotates in about three hours and the pause has twice been found
    // hours later with the causing event already gone. Every later refusal logs the SAME sentence
    // as the original failure, so without this the two are indistinguishable after the fact.
    static final String WHY_TIMEOUT    = "TIMEOUT";      // the node never answered (WRITE_TIMEOUT_MS)
    static final String WHY_INCOMPLETE = "INCOMPLETE";   // a reply arrived but completeReply() rejected it
    static final String WHY_CALLBACK   = "CALLBACK";     // our own callback threw after the reply

    static synchronized boolean begin(SharedPreferences prefs, String id, String command) {
        if (!prefs.getString("pending", "").isEmpty()) return false;
        // Stamp the command and time WITH the id, and clear any stale reason, in one commit. If the
        // process dies from here until returned(), "pending" survives on disk while `active` (a
        // static) does not - so the pause latches with NO log line anywhere. An empty reason is
        // therefore meaningful: nothing lived long enough to write one. See describe().
        if (!prefs.edit().putString("pending", id)
                         .putString("pending_cmd", verb(command))
                         .putLong("pending_at", System.currentTimeMillis())
                         .remove("pending_why").commit()) return false;
        active = id;
        return true;
    }
    static synchronized void uncertain(SharedPreferences prefs, String id) {
        if (id.equals(active)) active = "";
        why(prefs, id, WHY_TIMEOUT);
    }
    static synchronized void returned(SharedPreferences prefs, String id, boolean complete) {
        if (id.equals(active)) active = "";
        if (complete && id.equals(prefs.getString("pending", ""))) {
            prefs.edit().remove("pending").remove("pending_cmd").remove("pending_at")
                        .remove("pending_why").commit();
        } else if (!complete) {
            why(prefs, id, WHY_INCOMPLETE);
        }
    }
    static synchronized void callbackFailed(SharedPreferences prefs, String id, String command) {
        // Stamp the SAME three fields begin() does. Writing only the id left describe() with no verb
        // and no "N min ago" for precisely the cause where the command most likely DID complete on
        // the node - the one the user most needs the detail for.
        if (prefs.getString("pending", "").isEmpty()) {
            prefs.edit().putString("pending", id)
                        .putString("pending_cmd", verb(command))
                        .putLong("pending_at", System.currentTimeMillis()).commit();
        }
        why(prefs, id, WHY_CALLBACK);
    }
    /** Record the reason, but only against the write that is actually pending. */
    private static void why(SharedPreferences prefs, String id, String reason) {
        if (id.equals(prefs.getString("pending", ""))) {
            prefs.edit().putString("pending_why", reason).commit();
        }
    }
    private static String verb(String command) {
        String c = command == null ? "" : command.trim();
        int sp = c.indexOf(' ');
        return sp < 0 ? c : c.substring(0, sp);
    }

    /**
     * Plain-language account of the pending write, or null when there is none.
     *
     * The distinction that matters to the user is whether the NODE is implicated. A timeout means
     * the node went quiet and restarting it is reasonable. The other three mean the node answered,
     * or was never asked - restarting it achieves nothing, which is what the old blanket advice
     * ("First restart MinimaCore") kept sending people to do.
     */
    static synchronized String describe(SharedPreferences prefs) {
        String id = prefs.getString("pending", "");
        if (id.isEmpty()) return null;
        String cmd  = prefs.getString("pending_cmd", "");
        String when = prefs.getString("pending_why", "");
        long at     = prefs.getLong("pending_at", 0);
        String ago  = at <= 0 ? "" : " about " + Math.max(1, (System.currentTimeMillis() - at) / 60000) + " min ago";
        String what = cmd.isEmpty() ? "A node write" : "A '" + cmd + "' command";

        if (WHY_TIMEOUT.equals(when)) {
            return what + ago + " got no reply within the 3 minute limit. The node may have been "
                    + "restarting or stalled; it may still have completed.";
        }
        if (WHY_INCOMPLETE.equals(when)) {
            return what + ago + " came back without a usable result, so whether it completed is "
                    + "unknown. The node answered, so restarting it will not help.";
        }
        if (WHY_CALLBACK.equals(when)) {
            return what + ago + " replied, but AtomiX failed while handling the reply. The command "
                    + "most likely completed on the node.";
        }
        return what + ago + " was started and AtomiX stopped before the reply was handled - most "
                + "likely the app was killed in the background. Nothing was recorded either way, "
                + "and the node was never at fault.";
    }

    /** Simulate the process dying: `active` is a static and does not survive, `pending` does.
     *  Matches the SignGate.resetForTest() seam used elsewhere in this package. */
    static synchronized void resetForTest() { active = ""; }

    static synchronized boolean interrupted(SharedPreferences prefs) {
        return active.isEmpty() && !prefs.getString("pending", "").isEmpty();
    }
    static synchronized boolean acknowledge(SharedPreferences prefs) {
        if (!active.isEmpty()) return false;
        return prefs.edit().remove("pending").remove("pending_cmd").remove("pending_at")
                           .remove("pending_why").commit();
    }
}
