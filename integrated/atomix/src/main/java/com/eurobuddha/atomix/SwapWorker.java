package com.eurobuddha.atomix;

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
 * Periodic fallback: if the OS kills {@link SwapService}, WorkManager re-launches it so in-flight swaps
 * keep progressing (claim the counter-leg, refund on timeout) while the app is closed. The worker does no
 * node work itself — it just ensures the foreground service is alive.
 */
public class SwapWorker extends Worker {

    private static final String UNIQUE = "atomix_watch";

    public SwapWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    @NonNull
    @Override
    public Result doWork() {
        try {
            ContextCompat.startForegroundService(getApplicationContext(),
                    new Intent(getApplicationContext(), SwapService.class));
            return Result.success();
        } catch (Exception e) {
            // MA-13: on Android 12+ a background FGS start can be refused (ForegroundServiceStartNotAllowedException).
            // Swallowing it as success() meant WorkManager never retried and the failure was invisible in logcat.
            // Log it and ask WorkManager to retry so the keeper still gets relaunched.
            SwapLog.w("SwapWorker FGS start failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Result.retry();
        }
    }

    /** Schedule the ~15-minute fallback (WorkManager's minimum period). */
    public static void schedule(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                SwapWorker.class, 15, TimeUnit.MINUTES).build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.KEEP, req);
    }
}
