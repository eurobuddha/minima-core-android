package org.minimarex.minimacore.main;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import org.minimarex.minimacore.main.views.balance.BalanceView;
import org.minimarex.minimacore.main.views.home.HomeView;
import org.minimarex.minimacore.main.views.receive.ReceiveView;
import org.minimarex.minimacore.main.views.send.SendView;
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
        mAllViews = new BaseView[4];
    }

    public void refreshPagerView(int zPosition){
        if(mAllViews[zPosition] != null){
            mAllViews[zPosition].refreshView();
        }
    }

    public void refreshHomeView(){
        if(mAllViews[0] != null){
            mAllViews[0].refreshView();
        }
    }

    @Override
    public int getCount() {
        return 4;
    }

    @Override
    public Object instantiateItem(final ViewGroup container, int position) {

        BaseView baseview = null;

        if(position == 0){
            baseview    = new HomeView(mActivity);
        }else if(position == 1){
            baseview = new BalanceView(mActivity);
        }else if(position == 2){
            baseview = new SendView(mActivity);
        }else if(position == 3){
            baseview = new ReceiveView(mActivity);
        }

        //Store this..
        mAllViews[position] = baseview;

        //Add to our view..
        container.addView(baseview.getMainView());

        return baseview.getMainView();
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
        return object==view;
    }

    @Override
    public void destroyItem(ViewGroup container, int position, Object object) {
        //No store..
        mAllViews[position] = null;

        //Remove from container
        container.removeView((View)object);
    }

    public void refreshAllViews(){
        for(int i=0;i<mAllViews.length;i++){
            if(mAllViews[i] != null){
                mAllViews[i].refreshView();
            }
        }
    }
}
