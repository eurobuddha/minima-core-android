package com.eurobuddha.minimacore;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.WindowManager;

import org.minima.utils.MinimaUncaughtException;

/***
 * The main entry point for the Minima Application
 */
public class MinimaApplication extends Application {

    //Shared prefs used to remember the user's screenshot preference
    public static final String PREFS_NAME     = "main_prefs";
    public static final String PREF_ALLOW_SS  = "ALLOW_SCREENSHOTS";
    private final java.util.Set<Activity> activities = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Update retained app windows too, including windows in the background or multi-window. */
    public void setScreenshotsAllowed(boolean allowed) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_ALLOW_SS, allowed).apply();
        for (Activity activity : new java.util.ArrayList<>(activities)) applyScreenshotPolicy(activity);
    }

    private void applyScreenshotPolicy(Activity activity) {
        // These screens already deliberately protect secrets independently of the general toggle.
        boolean sensitive = activity instanceof com.eurobuddha.minimacore.main.backup.VaultActivity
                || activity instanceof com.eurobuddha.minimacore.main.backup.BackupCreateActivity
                || activity instanceof com.eurobuddha.minimacore.main.backup.RestoreFileActivity;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean debug = (activity.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        boolean allowed = prefs.getBoolean(PREF_ALLOW_SS, debug);
        if (sensitive || !allowed) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    /** Whether screenshots are currently allowed (persisted, default false). */
    public static boolean screenshotsAllowed(Context zContext){
        SharedPreferences prefs = zContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_ALLOW_SS, false);
    }
    @Override
    public void onCreate() {
        super.onCreate();

        //Catch ALL Uncaught Exceptions..
        Thread.setDefaultUncaughtExceptionHandler(new MinimaUncaughtException());

        //Make all activities no screenshot
        setupActivityListener();
        com.eurobuddha.minimacore.integrated.PandamoniumHooks.initialize(this);
    }

    private void setupActivityListener() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {

            //Block screenshots by default (FLAG_SECURE), UNLESS either:
            //  - the user has turned on "Allow Screenshots" (persisted toggle), or
            //  - this is a debuggable build with no saved preference yet.
            //The toggle lets our fork opt in/out at runtime instead of it being a hard rule.
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                activities.add(activity);
                applyScreenshotPolicy(activity);
            }

            @Override
            public void onActivityStarted(Activity activity) {}
            @Override
            public void onActivityResumed(Activity activity) {
                applyScreenshotPolicy(activity);
                com.eurobuddha.minimacore.integrated.PandamoniumHooks.install(activity);
            }
            @Override
            public void onActivityPaused(Activity activity) {}
            @Override
            public void onActivityStopped(Activity activity) {}
            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override
            public void onActivityDestroyed(Activity activity) { activities.remove(activity); }
        });
    }
}
