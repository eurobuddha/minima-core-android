package com.eurobuddha.futurecashnext;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Bring the guardian back after a reboot.
 *
 * <p>Matters more here than in most apps: a stake matures on a schedule the user chose weeks
 * earlier, and a phone that restarted overnight would otherwise leave an at-risk payout sitting on
 * an exposed key until someone happened to open the app.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        // rescueReady, not just GUARDIAN_ON: never resurrect a guardian that cannot rescue.
        final Cfg cfg = new Cfg(ctx);
        if (!cfg.is(Cfg.GUARDIAN_ON, false) || !cfg.rescueReady()) return;
        GuardianService.start(ctx.getApplicationContext());
    }
}
