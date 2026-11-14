package com.eurobuddha.minimacore.integrated;

import android.content.Intent;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/**
 * Pandamonium 2.2.34 (owner, 14 Nov 2026: "just have a dexicon tab, should be very integrated"): the whole Dexicon screen -
 * its three rooms, MxUSD (AtomiX), Pools and Exchange - lives in this tab, trading with the embedded node's own wallet
 * (Dexicon's host-node mode, integrated/dexicon). It is the screen's Host: its slot is this page, and it never lets
 * Dexicon change the core window (edge-to-edge, day/night).
 *
 * The screen is created the first time the tab is shown (or a Dexicon notification opens it), so a user who never trades
 * gets no Dexicon work at all. It is resumed only while the tab is the visible page of a resumed Activity: on Wallet or
 * Node its live ticker and order-book polling sleep (background trading is the MakerService's job, not the screen's).
 */
final class DexiconTabView extends com.eurobuddha.minimacore.main.BaseView
        implements com.eurobuddha.dexicon.ui.Screen.Host {
    private final com.eurobuddha.minimacore.main.MainActivity host;
    private final FrameLayout slot;
    private com.eurobuddha.dexicon.MainActivity screen;   // null until first shown
    private boolean hostStarted, hostResumed, screenStarted, screenResumed;
    private final Runnable relabel = () -> slot().invalidate();

    DexiconTabView(com.eurobuddha.minimacore.main.MainActivity activity) {
        super(activity);   // no-inflate: the Dexicon screen builds its own views into the slot
        host = activity;
        slot = new FrameLayout(activity);
        mMainView = slot;
        com.eurobuddha.dexicon.HostMode.setExit(activity::showWallet);   // Dexicon's header ✕
        // Every label in the tab in Pandamonium's language, on every redraw (Dexicon re-sets English as it updates);
        // a language change redraws it.
        slot.getViewTreeObserver().addOnPreDrawListener(() -> { com.eurobuddha.dexicon.HostMode.translateTree(slot); return true; });
        com.eurobuddha.minimacore.i18n.Language.addListener(relabel);
        activity.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(LifecycleOwner owner) { hostStarted = true; sync(); }
            @Override public void onResume(LifecycleOwner owner) { hostResumed = true; sync(); }
            @Override public void onPause(LifecycleOwner owner) { hostResumed = false; sync(); }
            @Override public void onStop(LifecycleOwner owner) { hostStarted = false; sync(); }
        });
    }

    private com.eurobuddha.dexicon.MainActivity screen() {
        if (screen == null) { screen = new com.eurobuddha.dexicon.MainActivity(this); screen.create(null); sync(); }
        return screen;
    }

    /** Started while the Activity is; resumed only while this tab is the page on screen. */
    private void sync() {
        if (screen == null) return;
        boolean start = hostStarted, resume = hostResumed && isDisplayActive();
        if (start && !screenStarted) { screenStarted = true; screen.start(); }
        if (resume && !screenResumed) { screenResumed = true; screen.resume(); }
        if (!resume && screenResumed) { screenResumed = false; screen.pause(); }
        if (!start && screenStarted) { screenStarted = false; screen.stop(); }
    }

    @Override protected void onShown() { PandamoniumHooks.thawTradingTab(host); host.setFullScreen(true); screen(); sync(); }
    @Override protected void onHidden() { host.setFullScreen(false); sync(); }

    // ---- Screen.Host ----
    @Override public AppCompatActivity activity() { return host; }
    @Override public ViewGroup slot() { return slot; }
    @Override public boolean allowWindowChanges() { return false; }   // the core window stays the core's
    @Override public void finishScreen() { /* a tab is never closed by its screen */ }

    // ---- the core Activity's callbacks, forwarded (only once the screen exists; a notification creates it) ----
    /** Back: Dexicon's own (dialogs, pages) first, else leave full screen for Wallet. */
    @Override public boolean onBack() {
        if (screen != null && screen.back()) return true;
        host.showWallet();
        return true;
    }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (screen != null) screen.activityResult(requestCode, resultCode, data);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (screen != null) screen.permissionsResult(requestCode, permissions, results);
    }
    @Override public void onNewIntent(Intent intent) { screen().newIntent(intent); }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        if (screen != null) screen.configurationChanged(config);
    }
    @Override public void onActivityDestroy() {
        com.eurobuddha.minimacore.i18n.Language.removeListener(relabel);
        if (screen != null) screen.destroy();
    }
}
