package com.eurobuddha.nftwallet;

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
 * Periodic safety net: if the OS kills {@link MintService}, WorkManager brings it back so an
 * unfinished collection keeps stamping. Does no node work itself — the IPC model is async — it
 * only ensures the service is alive.
 */
public class MintWorker extends Worker {

    private static final String UNIQUE = "nftwallet_mint";

    public MintWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    @NonNull @Override public Result doWork() {
        // Nothing left to mint: retire the periodic work rather than waking every 15 minutes
        // forever. schedule() uses KEEP, so a later mint re-enqueues it cleanly.
        if (!MintDriver.hasWork(getApplicationContext())) {
            cancel(getApplicationContext());
            return Result.success();
        }
        try {
            ContextCompat.startForegroundService(getApplicationContext(),
                    new Intent(getApplicationContext(), MintService.class));
        } catch (Exception ignored) {}
        return Result.success();
    }

    /** ~15 minutes is WorkManager's minimum period. */
    public static void schedule(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                MintWorker.class, 15, TimeUnit.MINUTES).build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    /** Retire the relauncher once there's nothing to relaunch it for. */
    public static void cancel(Context ctx) {
        try { WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE); } catch (Exception ignored) {}
    }
}
