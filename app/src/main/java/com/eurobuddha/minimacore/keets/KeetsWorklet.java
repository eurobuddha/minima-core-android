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
public class KeetsWorklet {

    private Worklet mWorklet;

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
            if (!GeneralParams.KEETS_BOOTSTRAP.equals("")) {
                args.add("--bootstrap"); args.add(GeneralParams.KEETS_BOOTSTRAP);
            }
            if (GeneralParams.TEST_PARAMS) {
                args.add("--topic"); args.add("minima-testnet-keets-v1");
            }

            Worklet.Options opts = new Worklet.Options().memoryLimit(32 * 1024 * 1024);
            mWorklet = new Worklet(opts);
            mWorklet.start("/keets.bundle", zContext.getAssets().open("keets.bundle"),
                    args.toArray(new String[0]));
            logger.log("KEETS worklet started port:" + GeneralParams.MINIMA_PORT
                    + " datadir:" + GeneralParams.DATA_FOLDER);
        } catch (Throwable t) {
            // UnsatisfiedLinkError (missing ABI), missing asset, anything — the node must
            // keep running classic-only rather than die with the worklet.
            logger.log("KEETS worklet failed to start : " + t);
            mWorklet = null;
        }
    }

    public synchronized void terminate() {
        if (mWorklet == null) return;
        try { mWorklet.terminate(); } catch (Throwable ignored) { }
        mWorklet = null;
        logger.log("KEETS worklet terminated");
    }
}
