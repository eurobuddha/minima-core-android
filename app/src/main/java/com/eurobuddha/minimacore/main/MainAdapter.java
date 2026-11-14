package com.eurobuddha.minimacore.main;

import com.eurobuddha.minimacore.BuildConfig;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.eurobuddha.minimacore.main.views.apps.AppsView;
import com.eurobuddha.minimacore.main.views.balance.BalanceView;
import com.eurobuddha.minimacore.main.views.home.HomeView;
import com.eurobuddha.minimacore.chat.ChatView;
import com.eurobuddha.minimacore.main.views.terminal.TerminalView;
import com.eurobuddha.minimacore.utils.logger;

public class MainAdapter extends androidx.viewpager.widget.PagerAdapter {

    // Named positions keep service refreshes and companion permissions tied to their views.
    // Chat is the only optional tab (Keets transport switch, 2.2.0): layout() sets the positions
    // for the shell being built, before any reader; the defaults are the keets-on layout.
    // Pandamonium 2.2.34: the Dexicon trading tab sits second (Wallet, Dexicon, History, Apps, Chat, Node); the pages
    // after it move one along. -1 = no trading tab (PandaBear, BlackBear: every position exactly as before).
    private static final int D = BuildConfig.PANDAMONIUM ? 1 : 0;
    public static final int DEXICON_POSITION = BuildConfig.PANDAMONIUM ? 1 : -1;
    public static int HOME_POSITION = BuildConfig.PANDA_SHELL ? 4 + D : 0;
    public static final int WALLET_POSITION = BuildConfig.PANDA_SHELL ? 0 : 1;
    public static final int UTILITY_POSITION = BuildConfig.PANDA_SHELL ? 1 + D : 2;
    public static final int APPS_POSITION = BuildConfig.PANDA_SHELL ? 2 + D : 3;
    public static int CHAT_POSITION = BuildConfig.PANDA_SHELL ? 3 + D : 4;   // was Logs (now in the overflow menu); -1 = no Chat tab

    static void layout(boolean chat) {
        int c = StartupMode.chatPosition(BuildConfig.PANDA_SHELL, chat);
        CHAT_POSITION = c < 0 ? -1 : c + (BuildConfig.PANDA_SHELL ? D : 0);
        HOME_POSITION = StartupMode.homePosition(BuildConfig.PANDA_SHELL, chat) + (BuildConfig.PANDA_SHELL ? D : 0);
    }

    MainActivity mActivity;

    BaseView[] mAllViews;
    private boolean hostVisible;
    private int selectedPage;

    public void setHostVisible(boolean visible, int page) {
        if (!BuildConfig.PANDAMONIUM) return;
        hostVisible = visible;
        selectedPage = page;
        updateVisibility();
    }

    public void selectPage(int page) {
        if (!BuildConfig.PANDAMONIUM) { refreshPagerView(page); return; }
        selectedPage = page;
        updateVisibility();
    }

    private void updateVisibility() {
        // Hide the previous page before activating the next one.
        for (int i = 0; i < mAllViews.length; i++)
            if (!hostVisible || i != selectedPage) mAllViews[i].setDisplayActive(false);
        if (hostVisible) mAllViews[selectedPage].setDisplayActive(true);
    }

    public static String RECEIVE_ADDRESS = null;

    public MainAdapter(MainActivity zContext){
        mActivity = zContext;

        //Clear receive address
        RECEIVE_ADDRESS = null;

        // Reuse the same screens. Pandamonium starts with Wallet and puts Node after Chat;
        // the other builds keep Home / Wallet / Terminal / Apps / Chat. Logs moved to the
        // toolbar overflow menu (LogsActivity) when Chat took its tab slot (1.9.20).
        boolean chat = StartupMode.keetsEnabled(
                mActivity.getSharedPreferences("main_prefs", android.content.Context.MODE_PRIVATE));
        layout(chat);
        mAllViews = new BaseView[(chat ? 5 : 4) + D];

        mAllViews[HOME_POSITION] = new HomeView(mActivity);
        mAllViews[WALLET_POSITION] = new BalanceView(mActivity);
        mAllViews[UTILITY_POSITION] = com.eurobuddha.minimacore.integrated.PandamoniumHooks.createUtilityTab(mActivity);
        mAllViews[APPS_POSITION] = new AppsView(mActivity);
        if (DEXICON_POSITION >= 0) mAllViews[DEXICON_POSITION] = com.eurobuddha.minimacore.integrated.PandamoniumHooks.createTradingTab(mActivity);
        if (chat) mAllViews[CHAT_POSITION] = new ChatView(mActivity);
    }

    public void refreshPagerView(int zPosition){
        mAllViews[zPosition].refreshView();
        if (mAllViews[zPosition].isDisplayActive()) mAllViews[zPosition].getMainView().invalidate();
    }

    public AppsView getAppsView(){
        return (AppsView) mAllViews[APPS_POSITION];
    }

    /** Pandamonium's Dexicon tab; null in every other build. */
    public BaseView getTradingView(){
        return DEXICON_POSITION < 0 ? null : mAllViews[DEXICON_POSITION];
    }

    /** null when the keets transport is off (no Chat tab). */
    public BaseView getChatView(){
        return CHAT_POSITION < 0 ? null : mAllViews[CHAT_POSITION];
    }

    //Refresh the node dashboard..
    public void refreshHomeView(){
        refreshPagerView(HOME_POSITION);
    }

    @Override
    public int getCount() {
        return mAllViews.length;
    }

    @Override
    public Object instantiateItem(final ViewGroup container, int position) {
        //Remove if added
        container.removeView(mAllViews[position].getMainView());

        //Add to our view..
        container.addView(mAllViews[position].getMainView());

        return mAllViews[position].getMainView();
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
        return object==view;
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        //Remove from container
        container.removeView((View)object);
    }

    public void languageChanged() {
        for (BaseView view : mAllViews) if (view != null) view.languageChanged();
    }

    public void refreshAllViews(){
        for(int i=0;i<mAllViews.length;i++){
            if(mAllViews[i] != null){
                mAllViews[i].refreshView();
                if (mAllViews[i].isDisplayActive()) mAllViews[i].getMainView().invalidate();
            }
        }
    }

    /** Called from MainActivity.onDestroy - a dialog still showing then leaks its window. */
    public void destroyAllViews(){
        for(int i=0;i<mAllViews.length;i++){
            if(mAllViews[i] != null){
                mAllViews[i].setDisplayActive(false);
                mAllViews[i].onActivityDestroy();
            }
        }
    }
}
