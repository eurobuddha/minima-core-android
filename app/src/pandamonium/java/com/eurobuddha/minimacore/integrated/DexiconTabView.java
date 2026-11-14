package com.eurobuddha.minimacore.integrated;

import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/**
 * Pandamonium 2.2.34 (owner, 14 Nov 2026: "just have a dexicon tab, should be very integrated"): the whole Dexicon screen -
 * its three rooms, MxUSD (AtomiX), Pools and Exchange - lives in this tab, trading with the embedded node's own wallet
 * (Dexicon's host-node mode, integrated/dexicon). It is the screen's Host: its slot is this page, it follows the core
 * Activity's lifecycle, and it never lets Dexicon change the core window (edge-to-edge, day/night).
 */
final class DexiconTabView extends com.eurobuddha.minimacore.main.BaseView
        implements com.eurobuddha.dexicon.ui.Screen.Host {
    private final com.eurobuddha.minimacore.main.MainActivity host;
    private final FrameLayout slot;
    private final com.eurobuddha.dexicon.MainActivity screen;

    DexiconTabView(com.eurobuddha.minimacore.main.MainActivity activity) {
        super(activity);   // no-inflate: the Dexicon screen builds its own views into the slot
        host = activity;
        slot = new FrameLayout(activity);
        mMainView = slot;
        screen = new com.eurobuddha.dexicon.MainActivity(this);
        screen.create(null);
        activity.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(LifecycleOwner owner) { screen.start(); }
            @Override public void onResume(LifecycleOwner owner) { screen.resume(); }
            @Override public void onPause(LifecycleOwner owner) { screen.pause(); }
            @Override public void onStop(LifecycleOwner owner) { screen.stop(); }
        });
    }

    // ---- Screen.Host ----
    @Override public AppCompatActivity activity() { return host; }
    @Override public ViewGroup slot() { return slot; }
    @Override public boolean allowWindowChanges() { return false; }   // the core window stays the core's
    @Override public void finishScreen() { /* a tab is never closed by its screen */ }

    // ---- the core Activity's callbacks, forwarded ----
    @Override public boolean onBack() { return screen.back(); }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) { screen.activityResult(requestCode, resultCode, data); }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        screen.permissionsResult(requestCode, permissions, results);
    }
    @Override public void onNewIntent(Intent intent) { screen.newIntent(intent); }
    @Override public void onSaveState(Bundle out) { screen.saveState(out); }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) { screen.configurationChanged(config); }
    @Override public void onActivityDestroy() { screen.destroy(); }
}
