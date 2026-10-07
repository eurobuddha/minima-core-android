package com.eurobuddha.futurecashnext;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import com.eurobuddha.minimaapi.direct.DirectNodeEvents;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import com.eurobuddha.minimaapi.direct.DirectNodeApi;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The guardian daemon: keeps collecting and rescuing while the app is closed.
 *
 * <p>This is what makes it "-next" rather than the plain lock-and-collect app. A matured stake
 * paying out to a reused key needs collecting and sweeping <b>promptly and unattended</b> — if it
 * only happened when the user opened the app, the exposure window would be however long they take
 * to notice.
 *
 * <p>Threading: {@link Guardian} blocks on node replies, and those replies are delivered on the main
 * thread, so every pass runs on a single-thread executor. One thread also means passes are naturally
 * serialised — no two reconciles can interleave.
 */
public class GuardianService extends Service {

    private static final int FG_ID = 3101;

    /** Set by MainActivity while it is visible; the service stands down so both don't post. */
    public static volatile boolean UI_FOREGROUND = false;

    private NodeApi nodeApi;
    private Node node;
    private Cfg cfg;
    private Store store;
    private Guardian guardian;
    private DirectNodeEvents.Listener receiver;
    private ExecutorService worker;
    private volatile boolean started = false;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        // Design holds process-wide static state (theme mode, typefaces) and until now was only
        // initialised by MainActivity. This service can be the FIRST thing in the process — started
        // by BootReceiver or GuardianWorker with no Activity — and anything reading Design then would
        // silently get the ONYX default and null typefaces regardless of the user's choice.
        Design.load(this);
        Notifier.ensureChannels(this);
        cfg = new Cfg(this);

        // REFUSE TO RUN WITHOUT A RESCUE DESTINATION. The UI gates this too, but the check has to
        // live here as well: this service is also started by BootReceiver and GuardianWorker, and a
        // build that shipped before the gate can already have GUARDIAN_ON set with nothing to sweep
        // to. Running then would show an ongoing "watching your stakes" notification for a guardian
        // that cannot rescue — the exact false assurance this whole change exists to remove.
        //
        // Checked BEFORE startForegroundCompat so that notification is never posted at all.
        if (!cfg.rescueReady()) {
            cfg.setBool(Cfg.GUARDIAN_ON, false);
            GuardianWorker.cancel(getApplicationContext());
            cfg.log(Cfg.LVL_ERROR, "Guardian stopped — no rescue destination is set, so it could not "
                    + "move an at-risk stake to safety. Set one on the Guardian tab and start it again.");
            Notifier.alert(this, "Future Cash guardian stopped",
                    "It had no safe destination, so it could not rescue an at-risk stake. Open the app "
                            + "and pick where rescued funds should go.");
            stopSelf();
            return;
        }

        // If the OS refuses the foreground service, bail gracefully rather than crashing — the
        // guardian resumes when the app is next opened or the budget resets.
        if (!startForegroundCompat()) { stopSelf(); return; }

        store = new Store(this);
        worker = Executors.newSingleThreadExecutor();
        nodeApi = new NodeApi(this, enabled -> {});
        node = new Node(nodeApi);
        guardian = new Guardian(node, cfg, store,
                (title, body) -> { if (cfg.is(Cfg.NOTIFY, true)) Notifier.alert(this, title, body); });

        receiver = new DirectNodeEvents.Listener() {
            @Override public void onEvent(JSONObject nodeEvent) {
                try {
                    final String event = nodeEvent.optString("event", "");
                    if ("NEWBLOCK".equals(event) || "NEWBALANCE".equals(event)) tick();
                } catch (Exception ignored) {}
            }
        };
        DirectNodeEvents.subscribe(receiver, "NEWBLOCK", "NEWBALANCE");

        // Assert the covenant is registered at every start, then run one pass immediately so
        // in-flight work resumes without waiting for the next block (~50s).
        submit(() -> {
            guardian.wallet().registerContract();
            started = true;
            cfg.log(Cfg.LVL_INFO, "Guardian started");
            runPass();
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    private void tick() {
        if (!started) return;
        if (UI_FOREGROUND) return;   // the Activity owns processing while it is visible
        submit(this::runPass);
    }

    private void runPass() {
        try {
            final long tip = guardian.tip();
            if (tip > 0) guardian.reconcile(tip);
        } catch (Throwable t) {
            // A pass that throws must never take the daemon down — the next block brings another.
            cfg.log(Cfg.LVL_ERROR, "Guardian pass failed — will retry next block: " + t);
        }
    }

    private void submit(Runnable r) {
        final ExecutorService w = worker;
        if (w == null || w.isShutdown()) return;
        try { w.execute(r); } catch (Exception ignored) {}
    }

    /** Swiped off recents. Keep guarding: reschedule the worker and ask to be brought back. */
    @Override public void onTaskRemoved(Intent rootIntent) {
        try { GuardianWorker.schedule(getApplicationContext()); } catch (Exception ignored) {}
        try {
            final android.app.AlarmManager am =
                    (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            final android.app.PendingIntent pi = android.app.PendingIntent.getForegroundService(
                    getApplicationContext(), 9, new Intent(getApplicationContext(), GuardianService.class),
                    android.app.PendingIntent.FLAG_ONE_SHOT | android.app.PendingIntent.FLAG_IMMUTABLE);
            if (am != null) am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 2000, pi);
        } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        super.onDestroy();
        if (receiver != null) { try { DirectNodeEvents.unsubscribe(receiver); } catch (Exception ignored) {} }
        if (worker != null) worker.shutdownNow();
        if (nodeApi != null) nodeApi.onDestroy();
        if (store != null) store.close();
    }

    /**
     * Start in the foreground, NEVER crashing. Uses specialUse on Android 14+ — a persistent
     * chain-watcher does not fit dataSync, which is capped at ~6h/day and then crashes the service.
     */
    private boolean startForegroundCompat() {
        final Notification n = buildNotification(this);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FG_ID, n);
            }
            return true;
        } catch (Exception e) {
            return false;   // ForegroundServiceStartNotAllowedException etc. — don't crash
        }
    }

    /**
     * The ongoing notification must not promise more than the config allows: "collecting safely"
     * while auto-collect is off described a guardian that would leave matured clean stakes
     * untouched forever. The text follows the switch, and BigTextStyle keeps the honest (longer)
     * variant readable instead of ellipsized.
     */
    private static Notification buildNotification(Context ctx) {
        final boolean auto = new Cfg(ctx).is(Cfg.AUTO_COLLECT_SAFE, false);
        final String text = auto
                ? "Watching your stakes — collecting matured ones, rescuing at-risk ones"
                : "Rescuing at-risk stakes only. Auto-collect is OFF — matured clean stakes are "
                  + "NOT collected until you turn it on in the app.";
        return new NotificationCompat.Builder(ctx, Notifier.CH_FG)
                .setContentTitle("Future Cash guardian")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .build();
    }

    /** Re-post the foreground notification so its text tracks the auto-collect switch. */
    public static void refreshNotification(Context ctx) {
        if (!new Cfg(ctx).is(Cfg.GUARDIAN_ON, false)) return;   // no service → nothing to update
        final NotificationManager nm =
                (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) try { nm.notify(FG_ID, buildNotification(ctx)); } catch (Exception ignored) {}
    }

    /** Android 14+ can ask a time-limited FGS to stop. Stop gracefully rather than crashing. */
    @Override public void onTimeout(int startId) { stopGracefully(); }
    @Override public void onTimeout(int startId, int fgsType) { stopGracefully(); }

    private void stopGracefully() {
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        stopSelf();
    }

    /* ---------- control ---------- */

    public static void start(Context ctx) {
        try {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, GuardianService.class));
            GuardianWorker.schedule(ctx.getApplicationContext());
        } catch (Exception ignored) {}
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, GuardianService.class));
            GuardianWorker.cancel(ctx.getApplicationContext());
            final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm != null) nm.cancel(FG_ID);
        } catch (Exception ignored) {}
    }
}
