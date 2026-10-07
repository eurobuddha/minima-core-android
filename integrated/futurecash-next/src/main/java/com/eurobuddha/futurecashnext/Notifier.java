package com.eurobuddha.futurecashnext;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/** Notification channels and alerts. */
public final class Notifier {

    public static final String CH_FG = "fcnext_fg";
    public static final String CH_ALERT = "fcnext_alert";

    private static final java.util.concurrent.atomic.AtomicInteger alertId = new java.util.concurrent.atomic.AtomicInteger(3100);

    private Notifier() {}

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        final NotificationChannel fg = new NotificationChannel(CH_FG, "Guardian",
                NotificationManager.IMPORTANCE_MIN);
        fg.setDescription("Shows while the guardian is watching your stakes");
        nm.createNotificationChannel(fg);

        // Collect/sweep alerts are the point of the app — one of them means money moved, or should
        // have. Keep them at DEFAULT so they are not silently collapsed.
        final NotificationChannel al = new NotificationChannel(CH_ALERT, "Stake alerts",
                NotificationManager.IMPORTANCE_DEFAULT);
        al.setDescription("Collections, rescues, and anything needing your attention");
        nm.createNotificationChannel(al);
    }

    public static void alert(Context ctx, String title, String body) {
        ensureChannels(ctx);
        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        final Notification n = new NotificationCompat.Builder(ctx, CH_ALERT)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setAutoCancel(true)
                .build();
        try { nm.notify("pandamonium.futurecashnext", alertId.getAndIncrement(), n); } catch (Exception ignored) {}
    }
}
