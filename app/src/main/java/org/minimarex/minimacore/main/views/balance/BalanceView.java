package org.minimarex.minimacore.main.views.balance;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.minimarex.minimacore.R;
import org.minimarex.minimacore.main.BaseView;
import org.minimarex.minimacore.main.views.balance.coins.CoinsDialog;
import org.minimarex.minimacore.main.views.balance.tokens.TokenMeta;
import org.minimarex.minimacore.main.views.receive.ReceiveActivity;
import org.minimarex.minimacore.main.views.send.SendActivity;
import org.minimarex.minimacore.utils.Format;
import org.minimarex.minimacore.utils.MinimaCMD;
import org.minimarex.minimacore.utils.MinimaCMDListener;
import org.minimarex.minimacore.utils.logger;

public class BalanceView extends BaseView {

    ListView mBalanceList;

    TextView mTotalBalance;

    TextView mBreakdown;

    BalanceAdapter mBalanceAdapter;

    //The Minima (0x00) figures behind the header number
    String mConfirmed   = "—";
    String mUnconfirmed = "—";
    String mSendable    = "—";
    String mCoinCount   = "—";

    /** When the node last answered `balance`. Drives the "updated ..." staleness. */
    long mLastBalanceUpdate = 0;

    CoinsDialog mOpenCoins = null;

    /**
     * The tab only refreshes on tab select, so a user parked here would otherwise read
     * "updated 3s ago" forever. Recompute the string (no node call) every 10s.
     */
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            updateBreakdown();
            if(mBreakdown != null && mBreakdown.getWindowToken() != null){
                mBreakdown.postDelayed(this, 10000);
            }
        }
    };

    public BalanceView(Activity zActivity){
        super(zActivity, R.layout.view_wallet_balance);

        mBalanceAdapter = new BalanceAdapter(zActivity);

        mBalanceList = getMainView().findViewById(R.id.wallet_balance_list);
        mBalanceList.setAdapter(mBalanceAdapter);

        mTotalBalance = getMainView().findViewById(R.id.wallet_total_balance);
        mBreakdown    = getMainView().findViewById(R.id.wallet_balance_breakdown);

        //Any token row - Minima is just tokenid 0x00 on the same path
        mBalanceList.setOnItemClickListener((parent, view, position, id) -> {
            try{
                JSONObject bal   = (JSONObject) mBalanceAdapter.getItem(position);
                String     tid   = String.valueOf(bal.get("tokenid"));
                TokenMeta  meta  = TokenMeta.parse(bal.get("token"), tid);

                mOpenCoins = CoinsDialog.show(getActivity(), tid, meta.name,
                        String.valueOf(bal.get("sendable")), String.valueOf(bal.get("confirmed")));

            }catch(Throwable exc){
                logger.log("Could not open coins : "+exc);
            }
        });

        getMainView().findViewById(R.id.wallet_btn_send).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getActivity().startActivity(new Intent(getActivity(), SendActivity.class));
            }
        });

        getMainView().findViewById(R.id.wallet_btn_receive).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getActivity().startActivity(new Intent(getActivity(), ReceiveActivity.class));
            }
        });

        refreshView();
    }

    @Override
    public void refreshView() {

        //Never stack timers when the tab is re-selected
        if(mBreakdown != null){
            mBreakdown.removeCallbacks(mTick);
            mBreakdown.postDelayed(mTick, 10000);
        }

        //Have we started
        if(!MinimaCMD.checkMinimaStarted()){

            JSONArray tempbal = new JSONArray();
            JSONObject bal = new JSONObject();
            bal.put("tokenid","0x00");
            bal.put("token","Awaiting Connection..");
            bal.put("confirmed","0");
            bal.put("unconfirmed","0");
            tempbal.add(bal);

            //The placeholder has no sendable / coins - don't let the breakdown print nulls
            mLastBalanceUpdate = 0;
            refreshBalance(tempbal);

            return;
        }

        //Run Cmd
        MinimaCMD.runMinima("balance", new MinimaCMDListener() {
            @Override
            public void cmdResult(JSONObject zResult) {
                //A throwable escaping here would take the app down - MinimaCMD uses a bare Thread
                try{
                    //Get the balance response
                    JSONArray balance = (JSONArray)zResult.get("response");

                    if(balance == null){
                        logger.log("NULL BALANCE : "+zResult.toString());
                        return;
                    }

                    //Stamp when the node actually answered, not when we asked
                    mLastBalanceUpdate = System.currentTimeMillis();

                    refreshBalance(balance);

                }catch(Throwable exc){
                    logger.log("Balance refresh failed : "+exc);
                }
            }
        });
    }

    private void refreshBalance(JSONArray zBalance){
        mBalanceList.post(new Runnable() {
            @Override
            public void run() {
                mBalanceAdapter.updateValues(zBalance);
                mBalanceList.invalidate();

                //Header total = the Minima (0x00) sendable balance
                updateTotal(zBalance);
            }
        });
    }

    private void updateTotal(JSONArray zBalance){
        if(mTotalBalance == null){
            return;
        }

        String total = "0";

        mConfirmed   = "—";
        mUnconfirmed = "—";
        mSendable    = "—";
        mCoinCount   = "—";

        try{
            for(int i=0;i<zBalance.size();i++){
                JSONObject token = (JSONObject) zBalance.get(i);
                String tokenid   = String.valueOf(token.get("tokenid"));
                if("0x00".equals(tokenid)){
                    //Prefer sendable; fall back to confirmed
                    Object send = token.get("sendable");
                    Object conf = token.get("confirmed");
                    total = String.valueOf(send != null ? send : conf);

                    //Everything else the node told us, for the breakdown line
                    mConfirmed   = str(conf,               mConfirmed);
                    mUnconfirmed = str(token.get("unconfirmed"), mUnconfirmed);
                    mSendable    = str(send,               mSendable);
                    mCoinCount   = str(token.get("coins"), mCoinCount);
                    break;
                }
            }
        }catch(Exception exc){}

        mTotalBalance.setText(total);

        updateBreakdown();
    }

    /** What makes up the header number - ported from the AtomiX wallet card. */
    private void updateBreakdown(){
        if(mBreakdown == null){
            return;
        }

        if(mLastBalanceUpdate == 0){
            mBreakdown.setText("node not connected");
            return;
        }

        mBreakdown.setText(
                  "confirmed "  + Format.tidyAmount(mConfirmed)
                + "  ·  locked ≈ "   + Format.subtract(mConfirmed, mSendable)
                + "  ·  unconfirmed " + Format.tidyAmount(mUnconfirmed)
                + "  ·  " + mCoinCount + " coins"
                + "  ·  updated " + Format.ago(mLastBalanceUpdate)
                + "  ·  tap a token for coins");
    }

    @Override
    public void onActivityDestroy(){
        if(mBreakdown != null){
            mBreakdown.removeCallbacks(mTick);
        }
        if(mOpenCoins != null){
            mOpenCoins.dismiss();
            mOpenCoins = null;
        }
    }

    private static String str(Object zValue, String zFallback){
        if(zValue == null){
            return zFallback;
        }
        String s = String.valueOf(zValue);
        return (s.isEmpty() || "null".equals(s)) ? zFallback : s;
    }
}
