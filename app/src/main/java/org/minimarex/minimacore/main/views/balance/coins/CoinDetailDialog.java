package org.minimarex.minimacore.main.views.balance.coins;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.minima.utils.MiniFormat;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.minimarex.minimacore.R;
import org.minimarex.minimacore.utils.Clip;
import org.minimarex.minimacore.utils.Format;

/**
 * Everything the node knows about one coin.
 *
 * No value is ever shortened here and every one has its own COPY button - ids that come
 * out of this modal must round-trip back into `coins coinid:...` byte for byte.
 * Purely synchronous: the coin JSON is already in hand, so no node call and no threading.
 */
public class CoinDetailDialog {

    private final Activity mActivity;

    private AlertDialog mDialog;

    public static CoinDetailDialog show(Activity zActivity, JSONObject zCoin, boolean zSendable){
        CoinDetailDialog cdd = new CoinDetailDialog(zActivity);
        cdd.open(zCoin, zSendable);
        return cdd;
    }

    private CoinDetailDialog(Activity zActivity){
        mActivity = zActivity;
    }

    private void open(JSONObject zCoin, boolean zSendable){

        LayoutInflater inflater = (LayoutInflater) mActivity.getSystemService(Activity.LAYOUT_INFLATER_SERVICE);
        View body = inflater.inflate(R.layout.dialog_coin_detail, null);

        LinearLayout rows = body.findViewById(R.id.coin_detail_rows);

        addRow(inflater, rows, "Coin ID",      value(zCoin, "coinid"));
        addRow(inflater, rows, "Amount",       value(zCoin, "amount"));
        addRow(inflater, rows, "Token amount", value(zCoin, "tokenamount"));
        addRow(inflater, rows, "Token ID",     value(zCoin, "tokenid"));
        addRow(inflater, rows, "Address",      value(zCoin, "address"));
        addRow(inflater, rows, "Mini address", value(zCoin, "miniaddress"));
        addRow(inflater, rows, "Sendable",     zSendable ? "yes" : "no — locked in a contract");
        addRow(inflater, rows, "Created",      value(zCoin, "created"));
        addRow(inflater, rows, "Age",          value(zCoin, "age"));
        addRow(inflater, rows, "MMR entry",    value(zCoin, "mmrentry"));
        addRow(inflater, rows, "Spent",        value(zCoin, "spent"));
        addRow(inflater, rows, "Store state",  value(zCoin, "storestate"));
        addRow(inflater, rows, "State",        stateOf(zCoin));

        final String raw = MiniFormat.JSONPretty(zCoin);

        TextView json = body.findViewById(R.id.coin_detail_json);
        json.setText(raw);

        mDialog = new MaterialAlertDialogBuilder(mActivity)
                .setTitle("Coin "+Format.shortHash(String.valueOf(zCoin.get("coinid"))))
                .setView(body)
                .setPositiveButton("Close", null)
                //null listener on purpose - see below
                .setNeutralButton("Copy JSON", null)
                .create();

        mDialog.show();

        //Any button with a listener passed to the builder dismisses the dialog on click.
        //Wiring it after show() keeps the modal open so the user can copy more than one thing.
        mDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
                v -> Clip.copy(mActivity, "coin", raw, "Coin JSON copied"));
    }

    private void addRow(LayoutInflater zInflater, LinearLayout zRows, String zLabel, String zValue){
        if(zValue == null || zValue.isEmpty()){
            return;
        }

        View row = zInflater.inflate(R.layout.view_coin_detail_row, zRows, false);

        ((TextView) row.findViewById(R.id.detail_label)).setText(zLabel);
        ((TextView) row.findViewById(R.id.detail_value)).setText(zValue);

        MaterialButton copy = row.findViewById(R.id.detail_copy);
        copy.setOnClickListener(v -> Clip.copy(mActivity, zLabel, zValue, zLabel+" copied"));

        zRows.addView(row);
    }

    public void dismiss(){
        if(mDialog != null && mDialog.isShowing()){
            mDialog.dismiss();
        }
    }

    // ---- helpers ----

    private static String value(JSONObject zCoin, String zKey){
        Object val = zCoin.get(zKey);
        if(val == null){
            return null;
        }
        String str = String.valueOf(val);
        return "null".equals(str) ? null : str;
    }

    /**
     * We ask for simplestate:false, so state arrives as an array of {port,data,keeper}.
     * Handle the object form too - it is what simplestate:true returns.
     */
    private static String stateOf(JSONObject zCoin){
        Object state = zCoin.get("state");
        if(state == null){
            return "(none)";
        }

        if(state instanceof JSONArray){
            JSONArray arr = (JSONArray) state;
            return arr.size() == 0 ? "(none)" : MiniFormat.JSONPretty(arr);
        }

        if(state instanceof JSONObject){
            JSONObject obj = (JSONObject) state;
            return obj.isEmpty() ? "(none)" : MiniFormat.JSONPretty(obj);
        }

        String str = String.valueOf(state);
        return str.isEmpty() ? "(none)" : str;
    }
}
