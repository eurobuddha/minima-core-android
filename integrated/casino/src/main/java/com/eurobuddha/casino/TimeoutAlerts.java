package com.eurobuddha.casino;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.core.app.NotificationCompat;

import java.util.Collections;
import java.util.Set;

/** Stable, retractable alert: repeat scans and process restarts do not nag for the same coins. */
final class TimeoutAlerts {
    static final String OPEN_MY_BETS = "com.eurobuddha.casino.OPEN_TIMEOUT_CLAIMS";
    private static final String CHANNEL = "casino_timeouts";
    private static final String TAG = "timeout_claims";
    private static final int ID = 1;

    private TimeoutAlerts() {}

    // Call only after a successful complete bet scan and wallet identity load, on the main thread.
    static void update(Context context, TimeoutClaims claims) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        SharedPreferences prefs = context.getSharedPreferences(TAG, Context.MODE_PRIVATE);
        Set<String> previous = prefs.getStringSet("coinids", Collections.emptySet());
        if (claims.coinids.isEmpty()) {
            nm.cancel(TAG, ID);
            prefs.edit().remove("coinids").apply();
            return;
        }
        if (claims.coinids.equals(previous)) return;
        NotificationChannel channel = nm.getNotificationChannel(CHANNEL);
        if (channel == null) channel = new NotificationChannel(CHANNEL, Language.text(context, l10n_casino_timeout_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setName(Language.text(context, l10n_casino_timeout_channel));
        nm.createNotificationChannel(channel);
        if (!nm.areNotificationsEnabled()
                || nm.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) return;

        // Same immutable tap-to-open pattern as AtomiX SwapService; never submits a transaction.
        Intent open = new Intent(context, MainActivity.class).setAction(OPEN_MY_BETS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        CharSequence body = CasinoMessages.message(claims.summary() + ". Tap to open My Bets.");
        try {
            nm.notify(TAG, ID, new NotificationCompat.Builder(context, CHANNEL)
                    .setContentTitle(Language.text(context, l10n_casino_timeout_channel))
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .setSilent(MainActivity.FOREGROUND || !claims.hasNewClaims(previous))
                    .build());
            prefs.edit().putStringSet("coinids", claims.coinids).apply();
        } catch (SecurityException ignored) {
            // Permission can be revoked between the check and notify; leave it eligible to retry.
        }
    }
}
