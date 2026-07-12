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
import org.minimarex.minimacore.main.views.receive.ReceiveActivity;
import org.minimarex.minimacore.main.views.send.SendActivity;
import org.minimarex.minimacore.utils.MinimaCMD;
import org.minimarex.minimacore.utils.MinimaCMDListener;
import org.minimarex.minimacore.utils.logger;

public class BalanceView extends BaseView {

    ListView mBalanceList;

    TextView mTotalBalance;

    BalanceAdapter mBalanceAdapter;

    public BalanceView(Activity zActivity){
        super(zActivity, R.layout.view_wallet_balance);

        mBalanceAdapter = new BalanceAdapter(zActivity);

        mBalanceList = getMainView().findViewById(R.id.wallet_balance_list);
        mBalanceList.setAdapter(mBalanceAdapter);

        mTotalBalance = getMainView().findViewById(R.id.wallet_total_balance);

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

        //Have we started
        if(!MinimaCMD.checkMinimaStarted()){

            JSONArray tempbal = new JSONArray();
            JSONObject bal = new JSONObject();
            bal.put("tokenid","0x00");
            bal.put("token","Awaiting Connection..");
            bal.put("confirmed","0");
            bal.put("unconfirmed","0");
            tempbal.add(bal);

            refreshBalance(tempbal);

            return;
        }

        //Run Cmd
        MinimaCMD.runMinima("balance", new MinimaCMDListener() {
            @Override
            public void cmdResult(JSONObject zResult) {

                //Get the balance response
                JSONArray balance = (JSONArray)zResult.get("response");

                if(balance == null){
                    logger.log("NULL BALANCE : "+zResult.toString());
                    return;
                }

                refreshBalance(balance);
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
        try{
            for(int i=0;i<zBalance.size();i++){
                JSONObject token = (JSONObject) zBalance.get(i);
                String tokenid   = String.valueOf(token.get("tokenid"));
                if("0x00".equals(tokenid)){
                    //Prefer sendable; fall back to confirmed
                    Object send = token.get("sendable");
                    Object conf = token.get("confirmed");
                    total = String.valueOf(send != null ? send : conf);
                    break;
                }
            }
        }catch(Exception exc){}

        mTotalBalance.setText(total);
    }
}
