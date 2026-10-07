package com.eurobuddha.atomix.integratedcomms;

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

    public static final String ERR_WRITE_UNCERTAIN = "A node write lost its reply and may still complete. New signing is paused. "
            + "Check Activity, then use Wallet → Resolve interrupted write.";
    private boolean destroyRequested;
    private android.content.SharedPreferences writePrefs() {
        return mContext.getSharedPreferences("atomix_write_safety", Context.MODE_PRIVATE);
    }
    public boolean hasInterruptedWrite() { return WriteSafety.interrupted(writePrefs()); }

    /** Plain-language account of the pending write - what it was, when, and whether the node is
     *  implicated - or null when nothing is pending. */
    public String interruptedWriteDetail() { return WriteSafety.describe(writePrefs()); }

    private static String sLoggedInterrupted = "";
    /** Log the pending write's cause once per distinct cause, not once per refused publish -
     *  the reprice loop retries every 90s and would otherwise bury the log in duplicates.
     *
     *  Dedup on the write's STABLE identity (its id + recorded reason), never on the rendered
     *  sentence: describe() embeds "about N min ago", so keying on the prose let a fresh line
     *  through every single minute - roughly what it was meant to prevent. */
    private void logInterruptedOnce() {
        android.content.SharedPreferences p = writePrefs();
        String key = p.getString("pending", "") + "/" + p.getString("pending_why", "");
        if (key.equals("/") || key.equals(sLoggedInterrupted)) return;
        String detail = WriteSafety.describe(p);
        if (detail == null) return;
        sLoggedInterrupted = key;
        android.util.Log.w("SwapPub", "write pause latched: " + detail);
    }
    /** Invoked only by the explicit restart-and-reconcile action in Wallet. */
    public boolean acknowledgeInterruptedWrite() { return WriteSafety.acknowledge(writePrefs()); }

    private static final long READ_TIMEOUT_MS = 30000;
    private static final long WRITE_TIMEOUT_MS = 180000;   // build + proof-of-work + post is slow on mobile

    /** A MegaMMR/deep coin walk (settlement recovery scans) can far outrun a normal read on a phone node,
     *  and a timed-out scan is worse than a slow one: the response arrives after the handler is purged, so
     *  the claim/refund it carries simply never happens. */
    private static final long DEEP_READ_TIMEOUT_MS = 120000;

    /** Transaction/PoW commands can take a long time on a phone; reads are quick. */
    private static long timeoutFor(String command) {
        String c = command == null ? "" : command.trim();
        if (WriteSafety.writesFunds(c) || c.startsWith("send") || c.startsWith("consolidate") || c.startsWith("txnsign")
                || c.startsWith("txnpost") || c.startsWith("tokencreate") || c.startsWith("txnbasics")) {
            return WRITE_TIMEOUT_MS;
        }
        if (c.contains("megammr:true")) return DEEP_READ_TIMEOUT_MS;
        return READ_TIMEOUT_MS;
    }

    private DirectNodeApi mApi;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final PairingListener mPairing;
    private final Context mContext;
    private final MinimaAPIListener mRegisterListener;
    // Pending timeout Runnables (main-thread only) so they can be cancelled on destroy.
    private final java.util.HashSet<Runnable> mPending = new java.util.HashSet<>();
    private boolean mReleased = false;

    // ---- pairing state (main-thread only). The register reply is a one-shot: if the node app isn't
    // running when we construct (e.g. right after a phone reboot), the broadcast is silently lost and
    // NOTHING would ever pair — the host must call reRegister() on a timer while !isEnabled(). ----
    private Boolean mEnabled = null;          // null until the first signal
    private long mLastOkMs = 0;               // last time ANY node reply arrived
    private int mConsecTimeouts = 0;
    // WHY we are not talking to the node. Silence and refusal are different faults with different
    // remedies, and only the node can tell us it refused: "enabled":false arrives in a REPLY. Timing out
    // proves nothing about permissions — it proves we got no answer. Conflating them (the old
    // noteEnabled(false) on the timeout path) told a user whose node was wedged to go and enable an app
    // that was already enabled. Proven live 2026-09-21: a node four days up, its Dalvik heap at 93% of
    // the 512MB cap, thrashing GC and no longer following the chain, while AtomiX blamed the Apps list.
    private Offline mOffline = Offline.UNREACHABLE;

    /** Why the node is not usable. REFUSED is a verdict the node gave us; UNREACHABLE is our own silence. */
    public enum Offline { ENABLED, REFUSED, UNREACHABLE }
    // Writes (send/txnpost/…) grind proof-of-work for minutes on the node's SINGLE command thread, so
    // reads queued behind one routinely time out — that's "busy", not "dead". While a write is pending,
    // read timeouts don't count toward unpairing and reRegister() must not drop the write's reply.
    private int mPendingWrites = 0;
    /** Command timeouts in a row before we consider the node dead (~90-180s of silence). */
    private static final int TIMEOUTS_TO_UNPAIR = 3;

    public NodeApi(Context ctx, PairingListener pairing) {
        mContext = ctx;
        mPairing = pairing;
        mRegisterListener = new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                final boolean enabled = zResponse.optBoolean("enabled", false);
                mMain.post(() -> {
                    if (mReleased || dead()) return;   // MA-12: a late register reply after onDestroy must not touch state
                    mOffline = enabled ? Offline.ENABLED : Offline.REFUSED;   // a reply either way
                    noteEnabled(enabled);
                });
            }
        };
        // Embedded readiness callback; no registration or app permission handshake.
        mApi = new DirectNodeApi(ctx, mRegisterListener);
    }

    /** Fire the pairing listener ONLY on a state change — so recovery/loss is signalled exactly once. */
    private void noteEnabled(boolean enabled) {
        if (mEnabled != null && mEnabled == enabled) return;
        mEnabled = enabled;
        android.util.Log.d("SwapPub", "pairing -> " + enabled);
        if (mPairing != null) mPairing.onEnabled(enabled);
    }

    public boolean isEnabled() { return mEnabled != null && mEnabled; }

    /** Why we are offline, as the UI should explain it. */
    public Offline offline() { return mOffline; }

    /** True once ANY node reply has arrived in this process — proof the pairing itself is good. */
    public boolean everReplied() { return mLastOkMs > 0; }

    /** What to tell the user when the node is unusable. Static + pure so the wording is unit-testable.
     *  Three distinct faults, three distinct remedies — never one message that guesses:
     *   REFUSED      the node replied "enabled":false. Enabling it in Minima Core is the fix.
     *   UNREACHABLE after a good reply — the pairing is proven, so the node itself stopped answering
     *               (busy on a long write, wedged, or killed). Restarting Minima Core is the fix.
     *   UNREACHABLE having never heard back — genuinely ambiguous, so say both possibilities. */
    public static String offlineMessage(Offline why, boolean everReplied) {
        if (why == Offline.REFUSED) return "The embedded node is not ready. Open Minima Core and check its status.";
        if (everReplied) return "The embedded node stopped responding. It may be busy; check Minima Core before retrying.";
        return "Waiting for the embedded node. Start Minima Core from the menu.";
    }

    /** Instance form of {@link #offlineMessage} for the current state. */
    public String offlineMessage() { return offlineMessage(mOffline, everReplied()); }

    public long lastOkMs() { return mLastOkMs; }

    /**
     * Re-send the pairing REGISTER by recreating the SDK instance — {@code DirectNodeApi.Register()} is
     * private and constructor-only. Safe and idempotent: the app/node uids are persisted in the SDK's
     * own prefs, so the identity is stable across instances, and the node persists the enabled flag in
     * its DB. In-flight response handlers from the old instance are dropped — their timeouts (above)
     * already surface as onError. Call only while unpaired or after consecutive timeouts.
     */
    public void reRegister() {
        if (mReleased) return;
        if (mPendingWrites > 0) {   // a slow write (PoW) is in flight — recreating would drop its reply
            android.util.Log.d("SwapPub", "reREGISTER skipped — write in flight");
            return;
        }
        android.util.Log.d("SwapPub", "reREGISTER");
        try { mApi.onDestroy(); } catch (Exception ignored) {}
        mApi = new DirectNodeApi(mContext, mRegisterListener);
    }

    /** True once the hosting Activity is gone — don't deliver callbacks into dead views. */
    private boolean dead() {
        return mContext instanceof Activity
                && (((Activity) mContext).isFinishing() || ((Activity) mContext).isDestroyed());
    }

    public void cmd(String command, Cb cb) {
        if (Looper.myLooper() != Looper.getMainLooper()) { mMain.post(() -> cmd(command, cb)); return; }
        if (command == null || command.trim().isEmpty()) { if (cb != null) cb.onError("Empty node command"); return; }
        // MA-11: after release, deliver an async onError rather than returning silently — a caller that set a
        // `running`/in-flight flag before calling (CommsScanner, the SignGate lambda) would otherwise hang
        // forever waiting for a callback that never arrives. Async (mMain.post) matches the normal delivery.
        if (mReleased) { if (cb != null) mMain.post(() -> cb.onError("released")); return; }
        final boolean funds = WriteSafety.writesFunds(command) && !command.matches(".*(?:^|\\s)dryrun:true(?:\\s|$).*");
        final String writeId = java.util.UUID.randomUUID().toString();
        if (funds && !WriteSafety.begin(writePrefs(), writeId, command)) {
            // One line naming the ORIGINAL cause, on every refusal. The latch outlives the logcat
            // buffer, so a refusal read hours later otherwise says only "a write lost its reply"
            // with no way back to which write or why.
            logInterruptedOnce();
            if (cb != null) mMain.post(() -> cb.onError(ERR_WRITE_UNCERTAIN));
            return;
        }
        final boolean isWrite = timeoutFor(command) == WRITE_TIMEOUT_MS;
        if (isWrite) mPendingWrites++;
        final boolean[] done = {false};
        final Runnable[] ref = new Runnable[1];
        final Runnable timeout = () -> {
            mPending.remove(ref[0]);
            if (done[0]) return;
            done[0] = true;
            // Review MAJOR: ALWAYS decrement a consumed write, even on a dead view — the old `|| dead()`
            // early-return leaked mPendingWrites, which then made reRegister() early-return forever
            // (mPendingWrites > 0), and dropped the callback so a caller that set an in-flight flag hung.
            if (isWrite) mPendingWrites--;
            // The "paired-then-node-died" detector touches the pairing listener, so skip it on a dead host;
            // but still deliver the error so the caller (scanner/SignGate lambda) never hangs (MA-11 parity).
            if (!dead() && mPendingWrites == 0 && ++mConsecTimeouts >= TIMEOUTS_TO_UNPAIR) {
                mOffline = Offline.UNREACHABLE;   // silence, NOT a permissions verdict
                noteEnabled(false);
            }
            if (funds) WriteSafety.uncertain(writePrefs(), writeId);
            try { if (cb != null) cb.onError(funds ? ERR_WRITE_UNCERTAIN
                    : offlineMessage(Offline.UNREACHABLE, mLastOkMs > 0)); }
            finally { finishDestroy(); }
        };
        ref[0] = timeout;
        mPending.add(timeout);
        mMain.postDelayed(timeout, timeoutFor(command));

        try { mApi.Command(command, new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                mMain.post(() -> {
                    // A late complete reply resolves only this write's durable marker; never a newer one.
                    if (funds) WriteSafety.returned(writePrefs(), writeId, WriteSafety.completeReply(zResponse));
                    if (done[0]) return;
                    done[0] = true;
                    mMain.removeCallbacks(timeout);
                    mPending.remove(timeout);
                    if (isWrite) mPendingWrites--;
                    // Any real reply proves the node is alive — track BEFORE the dead-view check
                    // (pairing state isn't a view).
                    mLastOkMs = System.currentTimeMillis();
                    mConsecTimeouts = 0;
                    // Keep transaction callbacks alive even after Activity teardown; UI consumers guard their views.
                    if (mReleased) return;
                    try {
                    if (!WriteSafety.completeReply(zResponse)) {
                        if (cb != null) cb.onError(funds ? ERR_WRITE_UNCERTAIN : "Incomplete node reply");
                        return;
                    }

                    // "enabled":false only appears on the gating reply; real command
                    // responses omit the key, so default true.
                    if (!zResponse.optBoolean("enabled", true)) {
                        mOffline = Offline.REFUSED;   // the node ANSWERED and said no — the one real "not enabled"
                        if (!dead()) noteEnabled(false);
                        if (cb != null) cb.onError(ERR_NOT_ENABLED);
                        return;
                    }
                    // A successful command means the node ran it as an enabled app — if we thought we
                    // were unpaired (e.g. the register reply got lost), this is the recovery signal.
                    mOffline = Offline.ENABLED;
                    if (!dead()) noteEnabled(true);
                    if (cb != null) cb.onResult(zResponse);
                    } catch (RuntimeException callbackFailure) {
                        if (funds) WriteSafety.callbackFailed(writePrefs(), writeId, command);
                        if (cb != null) try { cb.onError(funds ? ERR_WRITE_UNCERTAIN : "Bad node reply"); } catch (RuntimeException ignored) {}
                    } finally { finishDestroy(); }
                });
            }
        }); } catch (RuntimeException dispatchFailure) {
            mMain.removeCallbacks(timeout);
            timeout.run();
        }
    }

    public void onDestroy() {
        destroyRequested = true;
        finishDestroy();
    }

    private void finishDestroy() {
        // Keep the SDK alive until callback chains finish, as in PandaPools NodeApi. A screen closing
        // cannot silently discard a sign/post reply and strand the shared gate or operation marker.
        if (!destroyRequested || mReleased || !mPending.isEmpty()) return;
        mReleased = true;
        if (mApi != null) try { mApi.onDestroy(); } catch (Exception ignored) {}
    }
}
