package org.minimarex.minimacore.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.minima.system.Main;
import org.minimarex.minimacore.service.Alarm;
import org.minimarex.minimacore.service.MinimaService;

/**
 * The one way a resync is started, from the screen or from the health check.
 *
 * These steps are not optional and not obvious, which is exactly why they must not exist
 * twice: the pending flag has to be committed BEFORE the destructive command so a process
 * death cannot look like success; the alarms have to be cancelled so restart stays under
 * the user's control; and MainActivity's service binding has to be released or the node's
 * stopSelf at resync completion never reaches onDestroy.
 */
public final class ResyncLauncher {

    public static final class Result {
        public final boolean started;
        /** Why it did not start, or null when it did. */
        public final String error;

        private Result(boolean started, String error) {
            this.started = started;
            this.error = error;
        }
    }

    private ResyncLauncher() {}

    private static Result fail(String error) { return new Result(false, error); }

    @android.annotation.SuppressLint("ApplySharedPref") // Persist before the destructive command starts.
    public static Result begin(Context context, String host) {
        if (!ResyncSession.validHost(host)) {
            return fail("Enter a hostname or IPv4 address and a port from 1 to 65535");
        }
        // A missing chain tip is precisely why a damaged node may need resync, so the
        // check is "is the node process up", not "is the chain healthy".
        if (Main.getInstance() == null || MinimaService.haveStartedShutdown()) {
            return fail("The node is not running. Restart the node before resyncing.");
        }

        SharedPreferences prefs = context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE);
        if (!prefs.edit().putBoolean(SeedSyncActivity.PREF_PENDING, true).commit()) {
            return fail("Could not save resync state. Check available storage and try again.");
        }

        // Leave restart under the user's control, including after process death.
        new Alarm().cancelAlarm(context);
        MinimaService.cancelAlarm();

        if (!ResyncSession.get().start(host, System.nanoTime())) {
            // Refused (already running, or already finished and awaiting restart). Do not
            // leave a pending flag behind claiming a resync that never began.
            prefs.edit().putBoolean(SeedSyncActivity.PREF_PENDING, false).commit();
            return fail("A resync is already in progress.");
        }

        // MainActivity binds the service; release the binding on the main thread.
        final MainActivity open = MainActivity.MAIN_ACTIVITY;
        if (open != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!open.isFinishing() && !open.isDestroyed()) open.finish();
            });
        }
        return new Result(true, null);
    }
}
