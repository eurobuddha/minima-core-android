package com.eurobuddha.minimacore.keets;

import android.content.Context;

import com.eurobuddha.minimacore.utils.logger;

import org.minima.system.network.keets.KeetsManager;
import org.minima.system.params.GeneralParams;

import java.util.ArrayList;

import to.holepunch.bare.kit.Worklet;

/**
 * Hosts the keets sidecar as a bare-kit worklet — the Android replacement for the
 * desktop's ProcessBuilder("node", sidecar) path (KeetsManager stays dormant here:
 * -keets is never passed, so it spawns nothing; we only use its per-boot token).
 *
 * The worklet runs assets/keets.bundle (bare-pack of keets/sidecar/index.js — see
 * scripts/pack-keets-worklet.sh), joins the mainnet keets topic on the owner-run
 * HyperDHT bootstrap fleet, and bridges every peer stream to the node's own port
 * over loopback with the MSG_KEETS preamble (spec: keets/BRIDGE.md). The token
 * travels as a worklet argument: worklet args live in process memory, not in a
 * /proc cmdline, so this is as private as the desktop's env var.
 */
public class KeetsWorklet implements com.eurobuddha.minimacore.service.BatteryPolicy.Listener {

    /** V8 heap limit for the keets worklet. */
    static final int WORKLET_MEMORY_LIMIT = 128 * 1024 * 1024;

    /** Saver suspends the sidecar this long after the screen goes off. */
    static final long SUSPEND_AFTER_SCREEN_OFF_MS = 5 * 60_000;

    private Worklet mWorklet;
    private boolean mSuspended;
    private Context mContext;
    private String mDhtMode = "routing";
    /** A plug/unplug that changes the DHT mode restarts the sidecar, at most once per settle period
     *  (a loose cable must not cause a restart storm). */
    static final long DHT_MODE_SETTLE_MS = 30_000;
    private final Runnable mDhtModeRecheck = this::applyDhtMode;
    private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mRecheck = this::applyPolicy;

    /**
     * Whether the battery policy wants the sidecar suspended. Only Saver suspends, and only
     * once the screen has been off for 5 minutes with the app not in use; the mailbox keeps
     * any message meanwhile and the sidecar fetches it on resume (screen on).
     */
    static boolean shouldSuspend(com.eurobuddha.minimacore.service.BatteryPolicy.Profile zProfile,
                                 boolean zScreenOn, boolean zAppVisible, long zScreenOffMs) {
        return zProfile == com.eurobuddha.minimacore.service.BatteryPolicy.Profile.SAVER
                && !zScreenOn && !zAppVisible && zScreenOffMs >= SUSPEND_AFTER_SCREEN_OFF_MS;
    }

    @Override public void onBatteryPolicyChanged() {
        mHandler.post(mRecheck);
        mHandler.removeCallbacks(mDhtModeRecheck);
        mHandler.postDelayed(mDhtModeRecheck, DHT_MODE_SETTLE_MS);
    }

    /** Restart the sidecar when the wanted DHT mode differs from the running one (plug change or a
     *  new relay choice). A restart takes seconds; mail waits in the mailbox meanwhile. */
    private synchronized void applyDhtMode() {
        if (mWorklet == null || mContext == null) return;
        String want = com.eurobuddha.minimacore.service.BatteryPolicy.dhtMode();
        if (want.equals(mDhtMode)) return;
        logger.log("KEETS dht mode " + mDhtMode + " -> " + want + ": restarting the worklet");
        Context ctx = mContext;
        terminate();
        start(ctx);
    }

    private synchronized void applyPolicy() {
        mHandler.removeCallbacks(mRecheck);
        if (mWorklet == null) return;
        boolean want = shouldSuspend(com.eurobuddha.minimacore.service.BatteryPolicy.effective(),
                com.eurobuddha.minimacore.service.BatteryPolicy.screenOn(),
                com.eurobuddha.minimacore.service.BatteryPolicy.appVisible(),
                com.eurobuddha.minimacore.service.BatteryPolicy.screenOffMillis());
        if (want && !mSuspended) {
            try { mWorklet.suspend(); mSuspended = true; logger.log("KEETS worklet suspended (battery saver, screen off)"); }
            catch (Throwable t) { logger.log("KEETS suspend failed : " + t); }
        } else if (!want && mSuspended) {
            try { mWorklet.resume(); mSuspended = false; logger.log("KEETS worklet resumed"); }
            catch (Throwable t) { logger.log("KEETS resume failed : " + t); }
        }
        // screen off but not yet long enough: look again when the 5 minutes are up
        if (!want && !com.eurobuddha.minimacore.service.BatteryPolicy.screenOn()
                && com.eurobuddha.minimacore.service.BatteryPolicy.effective()
                   == com.eurobuddha.minimacore.service.BatteryPolicy.Profile.SAVER) {
            long left = SUSPEND_AFTER_SCREEN_OFF_MS - com.eurobuddha.minimacore.service.BatteryPolicy.screenOffMillis();
            mHandler.postDelayed(mRecheck, Math.max(1000, left));
        }
    }

    public synchronized boolean isSuspended() { return mSuspended; }

    public synchronized void start(Context zContext) {
        if (mWorklet != null) return;
        // bare-kit's native runtime declares minSdk 29 (we override the manifest merge) —
        // on Android 9 the node simply runs classic-only.
        if (android.os.Build.VERSION.SDK_INT < 29) {
            logger.log("KEETS worklet needs Android 10+ .. classic transports only");
            return;
        }
        try {
            ArrayList<String> args = new ArrayList<>();
            args.add("--port");     args.add(String.valueOf(GeneralParams.MINIMA_PORT));
            args.add("--datadir");  args.add(GeneralParams.DATA_FOLDER);
            args.add("--maxconns"); args.add(String.valueOf(GeneralParams.KEETS_MAX_CONNS));
            args.add("--token");    args.add(KeetsManager.getTokenHex());
            mDhtMode = com.eurobuddha.minimacore.service.BatteryPolicy.dhtMode();
            args.add("--dht-mode"); args.add(mDhtMode);
            if (!GeneralParams.KEETS_BOOTSTRAP.equals("")) {
                args.add("--bootstrap"); args.add(GeneralParams.KEETS_BOOTSTRAP);
            }
            if (GeneralParams.TEST_PARAMS) {
                args.add("--topic"); args.add("minima-testnet-keets-v1");
            }

            // 32 MB killed the whole app every ~85-90 min (2.0.40 on the Fold, 16 crashes in a row:
            // v8 FatalProcessOutOfMemory on the bare-worklet thread). Under Bare the sidecar's
            // steady working set is ~17 MB heap in use, ~20 MB reserved, ~18 MB buffers (measured
            // 2026-10-23), before the phone's contacts, peers and media drive. A V8 OOM aborts the
            // process, node included, so leave real headroom.
            Worklet.Options opts = new Worklet.Options().memoryLimit(WORKLET_MEMORY_LIMIT);
            mWorklet = new Worklet(opts);
            mWorklet.start("/keets.bundle", zContext.getAssets().open("keets.bundle"),
                    args.toArray(new String[0]));
            mContext = zContext.getApplicationContext();
            logger.log("KEETS worklet started port:" + GeneralParams.MINIMA_PORT
                    + " datadir:" + GeneralParams.DATA_FOLDER + " dht:" + mDhtMode);
            mSuspended = false;
            com.eurobuddha.minimacore.service.BatteryPolicy.addListener(this);
            mHandler.post(mRecheck);
        } catch (Throwable t) {
            // UnsatisfiedLinkError (missing ABI), missing asset, anything — the node must
            // keep running classic-only rather than die with the worklet.
            logger.log("KEETS worklet failed to start : " + t);
            mWorklet = null;
        }
    }

    public synchronized void terminate() {
        com.eurobuddha.minimacore.service.BatteryPolicy.removeListener(this);
        mHandler.removeCallbacks(mRecheck);
        mHandler.removeCallbacks(mDhtModeRecheck);
        if (mWorklet == null) return;
        try { mWorklet.terminate(); } catch (Throwable ignored) { }
        mWorklet = null;
        logger.log("KEETS worklet terminated");
    }
}
