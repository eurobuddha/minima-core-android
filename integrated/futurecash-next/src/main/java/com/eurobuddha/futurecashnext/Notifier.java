package com.eurobuddha.futurecashnext;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

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
        NotificationChannel fg = nm.getNotificationChannel(CH_FG);
        if (fg == null) fg = new NotificationChannel(CH_FG, Language.text(ctx, l10n_future_guardian),
                NotificationManager.IMPORTANCE_MIN);
        fg.setName(Language.text(ctx, l10n_future_guardian));
        fg.setDescription(Language.text(ctx, l10n_future_notice_watching));
        nm.createNotificationChannel(fg);

        // Collect/sweep alerts are the point of the app — one of them means money moved, or should
        // have. Keep them at DEFAULT so they are not silently collapsed.
        NotificationChannel al = nm.getNotificationChannel(CH_ALERT);
        if (al == null) al = new NotificationChannel(CH_ALERT, Language.text(ctx, l10n_future_notice_alerts),
                NotificationManager.IMPORTANCE_DEFAULT);
        al.setName(Language.text(ctx, l10n_future_notice_alerts));
        al.setDescription(Language.text(ctx, l10n_future_notice_alerts_help));
        nm.createNotificationChannel(al);
    }

    public static void alert(Context ctx, String title, String body) {
        ensureChannels(ctx);
        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        final CharSequence displayTitle = FutureMessages.message(title);
        final CharSequence displayBody = FutureMessages.message(body);
        final Notification n = new NotificationCompat.Builder(ctx, CH_ALERT)
                .setContentTitle(displayTitle)
                .setContentText(displayBody)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(displayBody))
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setAutoCancel(true)
                .build();
        try { nm.notify("pandamonium.futurecashnext", alertId.getAndIncrement(), n); } catch (Exception ignored) {}
    }
}
