package org.minimarex.minimacore.main;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import org.minimarex.minimacore.main.views.apps.AppsView;
import org.minimarex.minimacore.main.views.balance.BalanceView;
import org.minimarex.minimacore.main.views.home.HomeView;
import org.minimarex.minimacore.main.views.terminal.TerminalView;
import org.minimarex.minimacore.utils.logger;

public class MainAdapter extends androidx.viewpager.widget.PagerAdapter {

    MainActivity mActivity;

    BaseView[] mAllViews;

    public static String RECEIVE_ADDRESS = null;

    public MainAdapter(MainActivity zContext){
        mActivity = zContext;

        //Clear receive address
        RECEIVE_ADDRESS = null;

        //Store of all current valid views..
        //Tabs: Home / Wallet / Terminal / Apps  (Send + Receive are reached
        //via buttons on the Wallet tab, launching SendActivity/ReceiveActivity).
        //NB: Apps MUST stay at index 3 (getAppsView) and the dashboard at
        //index 0 (refreshHomeView) so the service/DB wiring stays intact.
        mAllViews = new BaseView[4];

        mAllViews[0] = new HomeView(mActivity);
        mAllViews[1] = new BalanceView(mActivity);
        mAllViews[2] = new TerminalView(mActivity);
        mAllViews[3] = new AppsView(mActivity);
    }

    public void refreshPagerView(int zPosition){
        mAllViews[zPosition].refreshView();
        mAllViews[zPosition].getMainView().invalidate();
    }

    public AppsView getAppsView(){
        return (AppsView) mAllViews[3];
    }

    //Refresh the Balance..
    public void refreshHomeView(){
        mAllViews[0].refreshView();
        mAllViews[0].getMainView().invalidate();
    }

    @Override
    public int getCount() {
        return 4;
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
}
