package com.eurobuddha.statenft;
import com.eurobuddha.minimacore.i18n.Language;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The engine's real home. The MiniDapp completes mints because engine.js
 * lives in the node's service context, ticking on every block — this
 * foreground service gives the Android engine the same lifetime. It runs
 * whenever any collection is in an active phase (or an airdrop is armed),
 * shows live progress in its notification, and stops itself when idle.
 */
public class MintService extends Service {

    private static final String CHANNEL = "mint_engine";
    private static final int NOTE_ID = 7601;
    private static final long TICK_MS = 25000;

    private boolean foregroundActive;
    private final Runnable languageChanged = this::refreshLanguageNotification;
    private NodeApi node;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int idleTicks = 0;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!hasWork(MintService.this)) {
                if (++idleTicks >= 2) {
                    LocalStore.logEvent(MintService.this, "Engine idle — service stopped");
                    stopSelf();
                    return;
                }
            } else {
                idleTicks = 0;
            }
            if (node != null && node.isEnabled()) {
                MintEngine.tick(MintService.this, node, msg -> {
                    LocalStore.recordHeartbeat(MintService.this, msg);
                    updateNotification();
                });
                AirdropEngine.tick(MintService.this, node, msg -> {
                    LocalStore.recordHeartbeat(MintService.this, msg);
                    updateNotification();
                });
            } else if (node != null) {
                node.reRegister();
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    /** Start (or poke) the engine — call after create/resume/recovery/airdrop-arm. */
    public static void kick(Context c) {
        if (!hasWork(c)) return;
        Intent i = new Intent(c, MintService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
            // background-start restrictions: the in-app loop still covers foreground use
        }
    }

    static boolean hasWork(Context c) {
        JSONArray rows = LocalStore.load(c);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            String p = r.optString("phase", "DONE");
            if ("CREATE".equals(p) || "MOVE".equals(p) || "SPLIT".equals(p)
                    || "STAMP".equals(p) || "NEEDIMAGES".equals(p)) return true;
        }
        JSONObject idx = LocalStore.loadDraft(c, "airdrop_index");
        return idx != null && idx.length() > 0;
    }

    @Override public void onCreate() {
        super.onCreate();
        Design.load(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, Language.text(this, com.eurobuddha.minimacore.i18n.R.string.l10n_creative_mint_channel),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(Language.text(this, com.eurobuddha.minimacore.i18n.R.string.l10n_creative_mint_channel_help));
            nm.createNotificationChannel(ch);
        }
        // If the OS refuses foreground promotion, bail gracefully rather than
        // crash-looping the whole app on every launch that kicks the engine —
        // the mint resumes on the next successful start.
        if (!startForegroundCompat()) {
            LocalStore.logEvent(this, "Foreground refused by the OS — engine waits for the next start");
            stopSelf();
            return;
        }
        foregroundActive = true;
        Language.addListener(languageChanged);
        com.eurobuddha.minimacore.i18n.NotificationLabels.update(this);
        LocalStore.logEvent(this, "Engine service engaged — sealing continues with the app closed");
        node = new NodeApi(this, enabled -> { if (enabled) handler.post(tick); });
        handler.postDelayed(tick, 4000);
    }

    private boolean startForegroundCompat() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // specialUse, not dataSync: Android 14 caps dataSync at ~6h/day
                // and then CRASHES the service. A mint legitimately runs long.
                startForeground(NOTE_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTE_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTE_ID, n);
            }
            return true;
        } catch (Exception e) {
            return false;   // ForegroundServiceStartNotAllowedException etc. — never crash
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        idleTicks = 0;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1500);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        foregroundActive = false;
        Language.removeListener(languageChanged);
        handler.removeCallbacks(tick);
        if (node != null) node.onDestroy();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        Language.configurationChanged(this);
        refreshLanguageNotification();
    }

    private void refreshLanguageNotification() {
        if (!foregroundActive) return;
        com.eurobuddha.minimacore.i18n.NotificationLabels.update(this);
        updateNotification();
    }

    private void updateNotification() {
        if (!foregroundActive) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            try { nm.notify(NOTE_ID, buildNotification()); }
            catch (SecurityException denied) { /* Notification permission changed. */ }
        }
    }

    private String phaseLabel(String phase) {
        switch (phase) {
            case "CREATE": return Language.text(this, com.eurobuddha.minimacore.i18n.R.string.l10n_creative_phase_create);
            case "MOVE": return Language.text(this, com.eurobuddha.minimacore.i18n.R.string.l10n_creative_phase_move);
            case "SPLIT": return Language.text(this, com.eurobuddha.minimacore.i18n.R.string.l10n_creative_phase_split);
            case "STAMP": return Language.text(this, com.eurobuddha.minimacore.i18n.R.string.l10n_creative_phase_stamp);
            default: return phase;
        }
    }

    private Notification buildNotification() {
        String title = Language.text(this, com.eurobuddha.minimacore.i18n.R.string.l10n_creative_mint_title);
        String text = Language.text(this, com.eurobuddha.minimacore.i18n.R.string.l10n_creative_watching_chain);
        JSONArray rows = LocalStore.load(this);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            String p = r.optString("phase", "DONE");
            if ("CREATE".equals(p) || "MOVE".equals(p) || "SPLIT".equals(p) || "STAMP".equals(p)) {
                text = Language.text(this, com.eurobuddha.minimacore.i18n.R.string.l10n_creative_sealing, r.optString("name", Language.text(this, com.eurobuddha.minimacore.i18n.R.string.l10n_nft_collection)), r.optInt("minted", 0), r.optInt("size", 0), phaseLabel(p));
                break;
            }
            if ("NEEDIMAGES".equals(p)) {
                text = Language.text(this, com.eurobuddha.minimacore.i18n.R.string.l10n_creative_needs_images, r.optString("name", Language.text(this, com.eurobuddha.minimacore.i18n.R.string.l10n_nft_collection)));
                break;
            }
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.pm_atelier_ic_launcher_monochrome)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }
}
