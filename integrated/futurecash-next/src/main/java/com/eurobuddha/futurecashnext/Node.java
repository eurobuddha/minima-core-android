package com.eurobuddha.futurecashnext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Blocking facade over {@link NodeApi}, for use from the guardian's worker thread ONLY.
 *
 * <p>The MiniDapp guardian is written as a pyramid of nested callbacks purely because
 * {@code MDS.cmd} has no synchronous form — the logic itself is a straight sequence. Here the
 * guardian owns a background thread, so each call can block that thread while the IPC reply comes
 * back on the main thread. Same order of operations, a fraction of the indentation, and errors
 * propagate as return values instead of being swallowed by a callback nobody checks.
 *
 * <p><b>Never call this from the main thread</b> — the reply is delivered there, so blocking it
 * deadlocks until the timeout.
 */
public final class Node {

    /** Slightly longer than NodeApi's own write timeout, so its error path fires first and explains why. */
    private static final long AWAIT_MS = 200_000;

    private final NodeApi api;

    public Node(NodeApi api) { this.api = api; }

    /* ---------- address shapes: the one gate between remote data and a command string ---------- */

    private static final Pattern HEX = Pattern.compile("^0x[0-9a-fA-F]{2,}$");
    private static final Pattern MX  = Pattern.compile("^M[xX][0-9A-Za-z]{30,100}$");

    public static boolean validHexAddr(String s) { return s != null && HEX.matcher(s).matches(); }
    public static boolean validMx(String s)      { return s != null && MX.matcher(s).matches(); }

    /**
     * The ONLY shapes allowed into a command string.
     *
     * <p>Addresses reach this app from the node (trusted) but also from the remote audit service,
     * whose reply feeds the at-risk set and from there {@code scripts address:…} lookups. Minima
     * commands are chained with ';' — this app relies on that itself when posting transactions — so
     * an unvalidated value interpolated into a command is arbitrary command execution on the user's
     * node, driven by an HTTP endpoint we tell users only ever sees public keys.
     */
    public static boolean validAddr(String s) { return validHexAddr(s) || validMx(s); }

    /* ---------- the blocking call ---------- */

    /** Missing or malformed status means an unknown outcome, including direct transport errors. */
    public static boolean unreadable(JSONObject r) {
        return r != null && !(r.opt("status") instanceof Boolean);
    }

    /** @return the reply, or null if the node errored / never answered. Never throws. */
    public JSONObject cmd(String command) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<JSONObject> out = new AtomicReference<>(null);
        try {
            api.cmd(command, new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) { out.set(json); done.countDown(); }
                @Override public void onError(String message) { out.set(null); done.countDown(); }
            });
            if (!done.await(AWAIT_MS, TimeUnit.MILLISECONDS)) return null;
        } catch (Throwable t) {
            return null;
        }
        return out.get();
    }

    /** True when the node reported success (and did not park the call as a pending approval). */
    public static boolean ok(JSONObject r) {
        return r != null && r.optBoolean("status", false) && !r.optBoolean("pending", false);
    }

    public static boolean pending(JSONObject r) {
        return r != null && r.optBoolean("pending", false);
    }

    public static String error(JSONObject r) {
        if (r == null) return "no reply from Minima Core";
        final String e = r.optString("error", "");
        return e.isEmpty() ? "command failed" : e;
    }

    /** The `response` field as an array — many commands return one, some return a bare object. */
    public static JSONArray arr(JSONObject r) {
        if (r == null) return new JSONArray();
        final JSONArray a = r.optJSONArray("response");
        if (a != null) return a;
        final JSONObject o = r.optJSONObject("response");
        final JSONArray wrapped = new JSONArray();
        if (o != null) wrapped.put(o);
        return wrapped;
    }

    public static JSONObject obj(JSONObject r) {
        return r == null ? null : r.optJSONObject("response");
    }

    /* ---------- coin helpers ---------- */

    /** A coin's state variable at a port, or null. */
    public static String state(JSONObject coin, int port) {
        if (coin == null) return null;
        final JSONArray st = coin.optJSONArray("state");
        if (st == null) return null;
        for (int i = 0; i < st.length(); i++) {
            final JSONObject v = st.optJSONObject(i);
            if (v == null) continue;
            if (String.valueOf(port).equals(v.optString("port", ""))) return v.optString("data", null);
        }
        return null;
    }

    /** MINIMA lives in `amount`, tokens in `tokenamount` — the value that must go to the payout. */
    public static String outAmount(JSONObject coin) {
        final String tok = coin.optString("tokenid", "0x00");
        return "0x00".equals(tok) ? coin.optString("amount", "0") : coin.optString("tokenamount", "0");
    }

    /**
     * Coin liveness, with "gone" kept distinct from "spent".
     *
     * <p>A spent coin stays in the node's coin DB; a coin that has VANISHED was rolled back by a
     * reorg. Treating the second as the first is how an ordinary shallow reorg turns into "your key
     * may be compromised" — the loudest alarm this app has.
     */
    public enum CoinState { UNSPENT, SPENT, GONE, UNKNOWN }

    public CoinState coinState(String coinid) {
        final JSONObject r = cmd("coins coinid:" + coinid);
        if (!ok(r)) return CoinState.UNKNOWN;
        final JSONArray a = r.optJSONArray("response");
        if (a == null) return CoinState.UNKNOWN;
        if (a.length() == 0) return CoinState.GONE;
        final JSONObject c = a.optJSONObject(0);
        return (c != null && c.optBoolean("spent", false)) ? CoinState.SPENT : CoinState.UNSPENT;
    }

    /** "unknown" counts as unspent — fail safe, so we retry rather than record a collect that never happened. */
    public boolean coinIsUnspent(String coinid) {
        final CoinState st = coinState(coinid);
        return st != CoinState.SPENT && st != CoinState.GONE;
    }
}
