package org.minimarex.minimacore;

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
    }

    private void setupActivityListener() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {

            //Block screenshots by default (FLAG_SECURE), UNLESS either:
            //  - the user has turned on "Allow Screenshots" (persisted toggle), or
            //  - this is a debuggable build (so the UI can be captured while developing).
            //The toggle lets our fork opt in/out at runtime instead of it being a hard rule.
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                boolean debuggable = (activity.getApplicationInfo().flags
                        & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                boolean allow = screenshotsAllowed(activity);
                if (!debuggable && !allow) {
                    activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
                }
            }

            @Override
            public void onActivityStarted(Activity activity) {}
            @Override
            public void onActivityResumed(Activity activity) {}
            @Override
            public void onActivityPaused(Activity activity) {}
            @Override
            public void onActivityStopped(Activity activity) {}
            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override
            public void onActivityDestroyed(Activity activity) {}
        });
    }
}
