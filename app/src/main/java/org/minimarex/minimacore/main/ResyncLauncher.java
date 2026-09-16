package org.minimarex.minimacore.main;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.minima.system.Main;
import org.minimarex.minimacore.service.Alarm;
import org.minimarex.minimacore.service.MinimaService;

/**
 * The one way a destructive node job is started, from any screen or from the health check.
 *
 * These steps are not optional and not obvious, which is exactly why they must not exist
 * twice: the pending flag has to be committed BEFORE the destructive command so a process
 * death cannot look like success; the alarms have to be cancelled so restart stays under
 * the user's control; and MainActivity's service binding has to be released or the node's
 * stopSelf at job completion never reaches onDestroy.
 *
 * Restore and MegaMMR-from-file restart the node exactly as a host resync does, so they come
 * through here too. Anything that runs a restarting command straight from an Activity is a bug.
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
    public static Result begin(Context context, ResyncJob job) {
        if (job == null) {
            return fail("Check the details and try again");
        }
        // A missing chain tip is precisely why a damaged node may need this, so the
        // check is "is the node process up", not "is the chain healthy".
        if (Main.getInstance() == null || MinimaService.haveStartedShutdown()) {
            return fail("The node is not running. Restart the node before continuing.");
        }

        SharedPreferences prefs = context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE);
        // Record WHICH job is pending, so a process death is reported as the operation the user
        // actually started rather than always as a resync.
        if (!prefs.edit()
                .putBoolean(SeedSyncActivity.PREF_PENDING, true)
                .putString(SeedSyncActivity.PREF_PENDING_LABEL, job.label())
                .commit()) {
            return fail("Could not save progress state. Check available storage and try again.");
        }

        // Leave restart under the user's control, including after process death.
        new Alarm().cancelAlarm(context);
        MinimaService.cancelAlarm();

        if (!ResyncSession.get().start(job, System.nanoTime())) {
            // Refused (already running, or already finished and awaiting restart). Do not
            // leave a pending flag behind claiming a job that never began.
            prefs.edit().putBoolean(SeedSyncActivity.PREF_PENDING, false).commit();
            return fail("Another " + job.label().toLowerCase() + " is already in progress.");
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
