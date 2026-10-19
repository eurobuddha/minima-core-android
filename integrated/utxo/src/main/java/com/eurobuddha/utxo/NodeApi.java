package com.eurobuddha.utxo;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;
import com.eurobuddha.minimaapi.MinimaAPIListener;

/**
 * Thin wrapper around the Minima Core native IPC SDK.
 *
 * - Holds the single {@link DirectNodeApi} instance (which connects to the embedded node).
 * - Runs node commands and delivers the result back ON THE MAIN THREAD (the raw SDK callback
 *   arrives on the broadcast-receiver thread), with a timeout so a missing/stopped node
 *   surfaces an error instead of hanging.
 * - Detects the "app not enabled in Minima Core" reply and routes it to a pairing listener
 *   so the UI can show the approve-me banner.
 */
public class NodeApi {

    public interface Cb {
        void onResult(JSONObject json);
        void onError(String message);
        /** An executing command may have completed despite a missing usable reply. */
        default void onUnknown(String message) { onError(message); }
    }

    public interface PairingListener {
        void onEnabled(boolean enabled);
    }

    /** Returned as the error message when the node says we are not enabled yet. */
    public static final String ERR_NOT_ENABLED = "NOT_ENABLED";
    /** The timeout message. A timed-out WRITE (txnsign) is NOT a failure — the node may still finish the
     *  proof-of-work and post; callers must treat it as unknown, never as "not sent". */
    public static final String ERR_TIMEOUT = "The embedded node did not respond. Check its status in Minima Core.";
    public static boolean isTimeout(String message) { return ERR_TIMEOUT.equals(message); }
    /** A pre-1.3.0 node answered an oversized reply with its "Result too long" stub (256,000-char cap). */
    public static final String ERR_TOO_LONG = "Node reply exceeded the IPC limit — update Minima Core (needs 1.3.0+).";
    public static boolean isTooLong(String message) { return ERR_TOO_LONG.equals(message); }

    private static final long READ_TIMEOUT_MS = 30000;
    private static final long WRITE_TIMEOUT_MS = 180000;   // build + proof-of-work + post is slow on mobile

    /** Transaction/PoW commands can take a long time on a phone; reads are quick. */
    private static long timeoutFor(String command) {
        String c = command == null ? "" : command.trim();
        if (c.startsWith("send") || c.startsWith("consolidate") || c.startsWith("txnsign")
                || c.startsWith("txnpost") || c.startsWith("tokencreate") || c.startsWith("txnbasics")) {
            return WRITE_TIMEOUT_MS;
        }
        return READ_TIMEOUT_MS;
    }

    private static String firstWord(String c) {
        if (c == null) return "?";
        int sp = c.trim().indexOf(' ');
        return sp < 0 ? c.trim() : c.trim().substring(0, sp);
    }

    private final DirectNodeApi mApi;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final PairingListener mPairing;
    private final Context mContext;
    // Pending timeout Runnables (main-thread only) so they can be cancelled on destroy.
    private final java.util.HashSet<Runnable> mPending = new java.util.HashSet<>();
    private boolean mReleased = false;

    public NodeApi(Context ctx, PairingListener pairing) {
        mContext = ctx;
        mPairing = pairing;
        // Embedded readiness callback; no registration or app permission handshake.
        mApi = new DirectNodeApi(ctx, new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                final boolean enabled = zResponse.optBoolean("enabled", false);
                mMain.post(() -> {
                    if (isClosed()) return;
                    if (mPairing != null) mPairing.onEnabled(enabled);
                });
            }
        });
    }

    /** True once the hosting Activity is gone — don't deliver callbacks into dead views. */
    private boolean dead() {
        return mContext instanceof Activity
                && (((Activity) mContext).isFinishing() || ((Activity) mContext).isDestroyed());
    }

    /** Reuse the bundled Vestr/Future Cash Boolean-status gate before reporting success. */
    private static Cb checked(Cb callback) {
        if (callback == null) return null;
        return new Cb() {
            @Override public void onResult(JSONObject result) {
                if (result == null || !(result.opt("status") instanceof Boolean)) {
                    String message = result == null ? "Node result unavailable. Outcome unknown."
                            : result.optString("transporterror", "Node result unavailable. Outcome unknown.");
                    if (nothingSent(message)) callback.onError(message);
                    else callback.onUnknown(message);
                } else if (!result.optBoolean("status", false)) {
                    callback.onError(result.optString("error", result.optString("response", "Node rejected the command.")));
                } else {
                    callback.onResult(result);
                }
            }
            @Override public void onError(String message) { callback.onError(message); }
            @Override public void onUnknown(String message) { callback.onUnknown(message); }
        };
    }

    /** Exact local transport rejections that occur before execution, from DirectNodeApi. */
    private static boolean nothingSent(String message) {
        return "The embedded node is not running. No command was sent.".equals(message)
                || "Too many pending node requests. Nothing was sent.".equals(message)
                || "Node request cancelled before execution. Nothing was sent.".equals(message)
                || "Node connection is closed.".equals(message);
    }

    public boolean isClosed() { return mReleased || dead(); }

    public void cmd(String command, Cb cb) {
        mMain.post(() -> cmdOnMain(command, checked(cb)));
    }

    private void cmdOnMain(String command, Cb cb) {
        if (isClosed()) {
            if (cb != null) mMain.post(() -> cb.onError("Node API released"));
            return;
        }
        final boolean[] done = {false};
        final Runnable[] ref = new Runnable[1];
        final Runnable timeout = () -> {
            mPending.remove(ref[0]);
            if (done[0] || isClosed()) return;
            done[0] = true;
            if (cb != null) cb.onUnknown(ERR_TIMEOUT);
        };
        ref[0] = timeout;
        mPending.add(timeout);
        mMain.postDelayed(timeout, timeoutFor(command));

        try { mApi.Command(command, new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                mMain.post(() -> {
                    if (done[0] || isClosed()) return;
                    done[0] = true;
                    mMain.removeCallbacks(timeout);
                    mPending.remove(timeout);
                    if (dead()) return;   // cleaned up above; just don't touch dead views

                    try {
                        if (zResponse != null && Boolean.FALSE.equals(zResponse.opt("enabled"))) {
                            if (mPairing != null) mPairing.onEnabled(false);
                            if (cb != null) cb.onError(ERR_NOT_ENABLED);
                            return;
                        }
                        Object response = zResponse == null ? null : zResponse.opt("response");
                        String text = response instanceof String ? ((String) response).toLowerCase(java.util.Locale.ROOT) : "";
                        boolean tooLong = zResponse != null && Boolean.FALSE.equals(zResponse.opt("status"))
                                && (text.contains("too long") || text.contains("max(256000)"));
                        if (tooLong) { if (cb != null) cb.onUnknown(ERR_TOO_LONG); return; }
                        if (cb != null) cb.onResult(zResponse);
                    } catch (RuntimeException malformed) {
                        if (cb != null) { try { cb.onUnknown("Node result unavailable. Outcome unknown."); } catch (RuntimeException ignored) {} }
                    }
                });
            }
        }); } catch (RuntimeException transportFailure) {
            // The transport can throw after dispatch; this is not proof that nothing ran.
            if (done[0]) return;
            done[0] = true;
            mMain.removeCallbacks(timeout);
            mPending.remove(timeout);
            if (!isClosed() && cb != null) cb.onUnknown("Node result unavailable. Outcome unknown.");
        }
    }

    public void onDestroy() {
        mReleased = true;
        for (Runnable r : mPending) mMain.removeCallbacks(r);
        mPending.clear();
        if (mApi != null) mApi.onDestroy();
    }
}
