package com.eurobuddha.minimacore.main;

import com.eurobuddha.minimacore.BuildConfig;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.eurobuddha.minimacore.main.views.apps.AppsView;
import com.eurobuddha.minimacore.main.views.balance.BalanceView;
import com.eurobuddha.minimacore.main.views.home.HomeView;
import com.eurobuddha.minimacore.main.views.logs.LogsView;
import com.eurobuddha.minimacore.main.views.terminal.TerminalView;
import com.eurobuddha.minimacore.utils.logger;

public class MainAdapter extends androidx.viewpager.widget.PagerAdapter {

    // Named positions keep service refreshes and companion permissions tied to their views.
    public static final int HOME_POSITION = BuildConfig.PANDAMONIUM ? 4 : 0;
    public static final int WALLET_POSITION = BuildConfig.PANDAMONIUM ? 0 : 1;
    public static final int UTILITY_POSITION = BuildConfig.PANDAMONIUM ? 1 : 2;
    public static final int APPS_POSITION = BuildConfig.PANDAMONIUM ? 2 : 3;
    public static final int LOGS_POSITION = BuildConfig.PANDAMONIUM ? 3 : 4;

    MainActivity mActivity;

    BaseView[] mAllViews;

    public static String RECEIVE_ADDRESS = null;

    public MainAdapter(MainActivity zContext){
        mActivity = zContext;

        //Clear receive address
        RECEIVE_ADDRESS = null;

        // Reuse the same screens. Pandamonium starts with Wallet and puts Node after Logs;
        // the other builds keep Home / Wallet / Terminal / Apps / Logs.
        mAllViews = new BaseView[5];

        mAllViews[HOME_POSITION] = new HomeView(mActivity);
        mAllViews[WALLET_POSITION] = new BalanceView(mActivity);
        mAllViews[UTILITY_POSITION] = com.eurobuddha.minimacore.integrated.PandamoniumHooks.createUtilityTab(mActivity);
        mAllViews[APPS_POSITION] = new AppsView(mActivity);
        mAllViews[LOGS_POSITION] = new LogsView(mActivity);
    }

    public void refreshPagerView(int zPosition){
        mAllViews[zPosition].refreshView();
        mAllViews[zPosition].getMainView().invalidate();
    }

    public AppsView getAppsView(){
        return (AppsView) mAllViews[APPS_POSITION];
    }

    //Refresh the node dashboard..
    public void refreshHomeView(){
        mAllViews[HOME_POSITION].refreshView();
        mAllViews[HOME_POSITION].getMainView().invalidate();
    }

    @Override
    public int getCount() {
        return 5;
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

    public void refreshAllViews(){
        for(int i=0;i<mAllViews.length;i++){
            if(mAllViews[i] != null){
                mAllViews[i].refreshView();
                mAllViews[i].getMainView().invalidate();
            }
        }
    }

    /** Called from MainActivity.onDestroy - a dialog still showing then leaks its window. */
    public void destroyAllViews(){
        for(int i=0;i<mAllViews.length;i++){
            if(mAllViews[i] != null){
                mAllViews[i].onActivityDestroy();
            }
        }
    }
}
