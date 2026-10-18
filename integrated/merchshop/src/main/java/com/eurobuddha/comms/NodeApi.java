package com.eurobuddha.merchshop.integratedcomms;

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
    }

    public interface PairingListener {
        void onEnabled(boolean enabled);
    }

    /** Returned as the error message when the node says we are not enabled yet. */
    public static final String ERR_NOT_ENABLED = "NOT_ENABLED";

    public static final String ERR_TOO_LONG = "TOO_LONG";
    private static boolean isTooLong(JSONObject j) {
        if (j == null || j.optBoolean("status", true)) return false;
        Object r = j.opt("response");
        if (!(r instanceof String)) return false;
        String s = ((String) r).toLowerCase(java.util.Locale.ROOT);
        return s.contains("too long") || s.contains("max(256000)");
    }

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
                    if (dead()) return;
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

    public void cmd(String command, Cb cb) {
        mMain.post(() -> cmdOnMain(command, cb));
    }

    private void cmdOnMain(String command, Cb cb) {
        if (mReleased) {
            if (cb != null) cb.onError("Node connection closed.");
            return;
        }
        final boolean[] done = {false};
        final Runnable[] ref = new Runnable[1];
        final Runnable timeout = () -> {
            mPending.remove(ref[0]);
            if (done[0] || dead()) return;
            done[0] = true;
            if (cb != null) cb.onError("The embedded node did not respond. Check its status in Minima Core.");
        };
        ref[0] = timeout;
        mPending.add(timeout);
        mMain.postDelayed(timeout, timeoutFor(command));

        mApi.Command(command, new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                mMain.post(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    mMain.removeCallbacks(timeout);
                    mPending.remove(timeout);
                    if (dead()) return;   // cleaned up above; just don't touch dead views

                    // "enabled":false only appears on the gating reply; real command
                    // responses omit the key, so default true.
                    if (!zResponse.optBoolean("enabled", true)) {
                        if (mPairing != null) mPairing.onEnabled(false);
                        if (cb != null) cb.onError(ERR_NOT_ENABLED);
                        return;
                    }
                    if (isTooLong(zResponse)) { if (cb != null) cb.onError(ERR_TOO_LONG); return; }
                    if (cb != null) cb.onResult(zResponse);
                });
            }
        });
    }

    /** Best-effort removal of this flow's completed scratch file. Never changes a transaction result. */
    public void discardPreparedFile(String name) {
        if(name==null||!name.matches("(?:ms_pay_[0-9a-f]{32}|signedtransaction-[0-9]{1,20})\\.txn"))return;
        mMain.post(()->{
            if((mReleased || dead()))return;
            try {
                // Filez uses the same guarded FILE bridge and serialized host mutation queue.
                final boolean[] checked={false};
                mApi.FileCommand("stat",name,null,null,reply->{
                    if(checked[0])return;checked[0]=true;
                    if((mReleased || dead())||reply==null||!Boolean.TRUE.equals(reply.opt("status"))
                            ||!"stat".equals(reply.opt("action"))||!name.equals(reply.opt("query"))
                            ||!Boolean.TRUE.equals(reply.opt("exists"))||!Boolean.FALSE.equals(reply.opt("isdir")))return;
                    Object base=reply.opt("base"),canonical=reply.opt("canonical"),size=reply.opt("size");
                    if(!(base instanceof String)||!((String)base).startsWith("/")||!(canonical instanceof String)
                            ||!(((String)base)+"/"+name).equals(canonical)||!(size instanceof Number)||((Number)size).longValue()<=0)return;
                    try {mApi.FileCommand("delete",name,null,null,ignored->{});}
                    catch(RuntimeException unavailable){/* Keep the file; no transfer retry or state change. */}
                });
            }catch(RuntimeException unavailable){/* Keep the file for manual inspection in Filez. */}
        });
    }

    public void onDestroy() {
        mReleased = true;
        for (Runnable r : mPending) mMain.removeCallbacks(r);
        mPending.clear();
        if (mApi != null) mApi.onDestroy();
    }
}
