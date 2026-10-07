package com.eurobuddha.futurecashnext;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Periodic fallback: if the OS kills {@link GuardianService}, WorkManager brings it back so stakes
 * keep being watched. Does no node work itself — it only ensures the service is alive.
 */
public class GuardianWorker extends Worker {

    private static final String UNIQUE = "futurecash_guardian";

    public GuardianWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    @NonNull
    @Override
    public Result doWork() {
        // Only revive the daemon if the user actually has it switched on — otherwise a cancelled
        // schedule that outlives the toggle would keep resurrecting a service they turned off — and
        // only if it can still rescue, so a destination removed since scheduling doesn't come back as
        // a guardian that merely watches.
        final Cfg cfg = new Cfg(getApplicationContext());
        if (!cfg.is(Cfg.GUARDIAN_ON, false) || !cfg.rescueReady()) return Result.success();
        try {
            ContextCompat.startForegroundService(getApplicationContext(),
                    new Intent(getApplicationContext(), GuardianService.class));
        } catch (Exception ignored) {}
        return Result.success();
    }

    /** Schedule the ~15-minute fallback (WorkManager's minimum period). */
    public static void schedule(Context ctx) {
        final PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                GuardianWorker.class, 15, TimeUnit.MINUTES).build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    public static void cancel(Context ctx) {
        try { WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE); } catch (Exception ignored) {}
    }
}
