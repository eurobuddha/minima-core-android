package com.eurobuddha.nftwallet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Resume an unfinished mint after a reboot or an app update.
 *
 * Starting a specialUse foreground service from BOOT_COMPLETED is legal — Android 15's boot-time
 * FGS restrictions cover dataSync, camera, media, microphone and phoneCall, not specialUse.
 * Only starts when there is genuinely work left.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        if (!MintDriver.hasWork(ctx)) return;
        HeartbeatReceiver.schedule(ctx);
        MintService.startIfWork(ctx);
    }
}
