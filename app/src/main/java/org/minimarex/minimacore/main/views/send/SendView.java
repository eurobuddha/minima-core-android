package org.minimarex.minimacore.main.views.send;

import android.app.Activity;
import android.content.DialogInterface;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.minimarex.minimacore.R;
import org.minimarex.minimacore.main.BaseView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.minimarex.minimacore.utils.Format;
import org.minimarex.minimacore.utils.MinimaCMD;
import org.minimarex.minimacore.utils.MinimaCMDListener;
import org.minimarex.minimacore.utils.TokenUtils;
import org.minimarex.minimacore.utils.logger;

public class SendView extends BaseView {

    TextView mAmount;
    TextView mAddress;
    TextView mSendable;

    Button mSendButton;
    Spinner mTokens;

    TokenSpinnerAdapter mTokenAdapter;

    int mChosenToken=0;

    /** Set by SendActivity - it owns the scan launcher, which must be registered in onCreate. */
    Runnable mOnScanRequest = null;

    public void setOnScanRequest(Runnable zOnScanRequest){
        mOnScanRequest = zOnScanRequest;
    }

    /** Called back by SendActivity with whatever the QR contained. */
    public void setScannedAddress(String zRaw){
        String addr = Format.cleanAddress(zRaw);
        if(addr.isEmpty()){
            Toast.makeText(getActivity(), "That QR held no address", Toast.LENGTH_SHORT).show();
            return;
        }
        mAddress.setText(addr);
    }

    public SendView(Activity zActivity){
        super(zActivity, R.layout.view_wallet_send);

        mTokens = getMainView().findViewById(R.id.wallet_send_tokens);
        mTokenAdapter = new TokenSpinnerAdapter(zActivity);
        mTokens.setAdapter(mTokenAdapter);

        mTokens.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mChosenToken = position;
                updateSendable();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        mAmount     = getMainView().findViewById(R.id.wallet_send_amount);
        mAddress    = getMainView().findViewById(R.id.wallet_send_address);
        mSendable   = getMainView().findViewById(R.id.wallet_send_sendable);

        //Max = the selected token's SENDABLE, in full. Not confirmed - confirmed counts
        //coins locked in contracts and the send would simply fail.
        getMainView().findViewById(R.id.wallet_send_max).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String max = sendableOfChosenToken();
                if(max == null){
                    Toast.makeText(getActivity(), "No balance loaded yet", Toast.LENGTH_SHORT).show();
                    return;
                }
                mAmount.setText(max);
            }
        });

        getMainView().findViewById(R.id.wallet_send_scan).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(mOnScanRequest != null){
                    mOnScanRequest.run();
                }
            }
        });

        mSendButton = getMainView().findViewById(R.id.wallet_send_sendbutton);
        mSendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String amount       = mAmount.getText().toString().trim();
                String address      = mAddress.getText().toString().trim();

                if(amount.equals("") || address.equals("")){
                    logger.showDialog(getActivity(),"Error","Cannot have blank inputs..");
                    return;
                }

                JSONObject token    = mTokenAdapter.getToken(mChosenToken);
                String tokenid      = token.get("tokenid").toString();
                String tokenname    = TokenUtils.getTokenName(token);

                showConfirmDialog(amount, address, tokenname, tokenid);
            }
        });

        refreshView();
    }

    private void showConfirmDialog(String zAmount, String zAddress, String zTokenName, String zTokenid ){
        new MaterialAlertDialogBuilder(getActivity())
                .setTitle("Confirm")
                .setMessage("You are about to send "+zAmount+" "+zTokenName+" to \n"+zAddress)
                .setIcon(R.drawable.ic_minima)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener(){
                    public void onClick(DialogInterface dialog, int whichButton) {
                        sendFunds(zAmount, zAddress, zTokenid);
                    }})
                .setNegativeButton(android.R.string.no, null).show();
    }

    protected void sendFunds(String zAMount, String zAddress, String zTokenid){

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getActivity(), "Sending funds..", Toast.LENGTH_SHORT).show();
            }
        });

        String cmd = "send amount:"+zAMount+" address:"+zAddress+" tokenid:"+zTokenid;

        MinimaCMD.runMinima(cmd, new MinimaCMDListener() {
            @Override
            public void cmdResult(JSONObject zResult) {
                //Raw Thread - an escaping throwable would kill the app
                try{
                    final boolean ok = Boolean.TRUE.equals(zResult.get("status"));
                    final String  err = String.valueOf(zResult.get("error"));

                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if(ok){
                                //Only clear the form once the node has actually accepted it -
                                //on failure the user keeps what they typed
                                mAmount.setText("");
                                mAddress.setText("");
                                Toast.makeText(getActivity(), "Funds Sent!", Toast.LENGTH_SHORT).show();
                            }else{
                                logger.showDialog(getActivity(), "Send failed",
                                        ("null".equals(err) ? "The node rejected the transaction." : err)
                                        + "\n\nNothing was sent. Your inputs have been kept.");
                            }
                        }
                    });

                }catch(Throwable exc){
                    logger.log("Send failed : "+exc);
                }
            }
        });
    }

    @Override
    public void refreshView() {

        //Have we started
        if(!MinimaCMD.checkMinimaStarted()){
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

                refreshTokenSpinner(balance);
            }
        });
    }

    public void refreshTokenSpinner(JSONArray zBalance){
        mTokens.post(new Runnable() {
            @Override
            public void run() {
                mTokenAdapter.updateTokens(zBalance);
                mTokens.invalidate();
                updateSendable();
            }
        });
    }

    /** The chosen token's sendable amount, or null when no balance has loaded yet. */
    private String sendableOfChosenToken(){
        try{
            JSONObject token = mTokenAdapter.getToken(mChosenToken);
            if(token == null){
                return null;
            }
            Object send = token.get("sendable");
            if(send == null){
                send = token.get("confirmed");
            }
            return send == null ? null : Format.tidyAmount(String.valueOf(send));

        }catch(Exception exc){
            return null;
        }
    }

    private void updateSendable(){
        if(mSendable == null){
            return;
        }

        String send = sendableOfChosenToken();
        if(send == null){
            mSendable.setVisibility(View.GONE);
            return;
        }

        mSendable.setText("sendable "+send);
        mSendable.setVisibility(View.VISIBLE);
    }
}
