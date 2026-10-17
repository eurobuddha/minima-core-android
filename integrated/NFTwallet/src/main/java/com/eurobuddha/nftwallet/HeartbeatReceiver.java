package com.eurobuddha.nftwallet;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.content.ContextCompat;

/**
 * Doze-proof clock for {@link MintService}.
 *
 * A foreground service keeps the process resident but does NOT exempt Handler.postDelayed or the
 * node's broadcasts from Doze — a phone left on a desk overnight can stop delivering NEWBLOCK for
 * long stretches. An exact allow-while-idle alarm is the one thing that reliably wakes us.
 */
public class HeartbeatReceiver extends BroadcastReceiver {

    private static final long INTERVAL_MS = 15 * 60_000;
    /** Fixed request code + FLAG_UPDATE_CURRENT, so rescheduling is idempotent. */
    private static final int REQUEST_CODE = 31;

    @Override public void onReceive(Context ctx, Intent intent) {
        // Nothing to mint: stop the chain. This check MUST come before the reschedule below — an
        // earlier version chained first and then returned here, so the "let the alarm lapse" it
        // claimed could never happen and every install kept firing an exact allow-while-idle
        // RTC_WAKEUP every 15 minutes forever, long after the last collection was minted.
        if (!MintDriver.hasWork(ctx)) { cancel(ctx); return; }
        // Chain the next one BEFORE the work below: a crash there must never end the heartbeat.
        schedule(ctx);
        try {
            ContextCompat.startForegroundService(ctx,
                    new Intent(ctx, MintService.class).setAction(MintService.ACTION_HEARTBEAT));
        } catch (Exception ignored) {}
    }

    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, REQUEST_CODE,
                    new Intent(ctx, HeartbeatReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = System.currentTimeMillis() + INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception ignored) {}
    }

    /** Stop the chain. Safe to call when nothing is scheduled. */
    public static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            // Same request code + component as schedule(), so this matches the pending alarm.
            PendingIntent pi = PendingIntent.getBroadcast(ctx, REQUEST_CODE,
                    new Intent(ctx, HeartbeatReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
            pi.cancel();
        } catch (Exception ignored) {}
    }
}
