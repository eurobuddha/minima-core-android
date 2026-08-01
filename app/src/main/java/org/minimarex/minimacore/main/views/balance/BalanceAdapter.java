package org.minimarex.minimacore.main.views.balance;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;
import org.minimarex.minimacore.R;
import org.minimarex.minimacore.main.views.balance.tokens.Identicon;
import org.minimarex.minimacore.main.views.balance.tokens.ImageLoader;
import org.minimarex.minimacore.main.views.balance.tokens.TokenMeta;
import org.minimarex.minimacore.main.views.balance.tokens.WebValidate;
import org.minimarex.minimacore.utils.Clip;
import org.minimarex.minimacore.utils.Format;

public class BalanceAdapter extends BaseAdapter {

    private final Activity mActivity;
    private final LayoutInflater inflater;

    JSONArray mCurrentBalance = new JSONArray();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = this::notifyDataSetChanged;

    public BalanceAdapter(Activity zActivity){
        super();
        mActivity = zActivity;
        inflater = (LayoutInflater) zActivity.getSystemService(Activity.LAYOUT_INFLATER_SERVICE);
    }

    public void updateValues(JSONArray zCurrentBalance){
        mCurrentBalance = zCurrentBalance;
        notifyDataSetChanged();
    }

    /** Coalesce async web-validation callbacks into a single re-render. */
    private void scheduleRefresh(){
        mHandler.removeCallbacks(mRefresh);
        mHandler.postDelayed(mRefresh, 120);
    }

    @Override
    public int getCount() { return mCurrentBalance.size(); }

    @Override
    public Object getItem(int position) { return mCurrentBalance.get(position); }

    @Override
    public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        if(row == null){
            row = inflater.inflate(R.layout.view_balance_row, null);
        }

        TextView tokenname   = row.findViewById(R.id.balance_tokenname);
        TextView tokenamount = row.findViewById(R.id.balance_tokenamount);
        TextView tokenid     = row.findViewById(R.id.balance_tokenid);
        TextView breakdown   = row.findViewById(R.id.balance_breakdown);
        ImageView icon       = row.findViewById(R.id.balance_tokenicon);
        ImageView badge      = row.findViewById(R.id.balance_tokenbadge);

        //Get the balance..
        JSONObject bal = (JSONObject) mCurrentBalance.get(position);

        //Token ID - in full, never shortened
        String id = String.valueOf(bal.get("tokenid"));
        tokenid.setText(id);

        //Token metadata (name / icon / webvalidate)
        TokenMeta meta = TokenMeta.parse(bal.get("token"), id);
        tokenname.setText(meta.name);

        int density = (int) mActivity.getResources().getDisplayMetrics().density;
        int iconPx  = 40 * Math.max(1, density);
        int badgePx = 15 * Math.max(1, density);

        if(TokenMeta.isMinima(id)){
            //Native coin: black Minima mark on a white tile, and it is the official verified coin
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = 6 * Math.max(1, density);
            icon.setPadding(pad, pad, pad, pad);
            icon.setBackgroundResource(R.drawable.bg_token_tile_white);
            icon.setColorFilter(0xFF000000);
            icon.setImageResource(R.drawable.ic_minima);
            icon.setTag(null);
            badge.setImageBitmap(Identicon.checkBadge(badgePx));
            badge.setVisibility(View.VISIBLE);
        }else{
            //Deterministic identicon base, real icon loaded over the top when it decodes
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setPadding(0, 0, 0, 0);
            icon.setBackground(null);
            icon.clearColorFilter();
            icon.setImageBitmap(Identicon.forToken(id, iconPx));
            ImageLoader.loadOver(mActivity, meta.iconUrl, icon, null);

            //Web-validation badge (async, cached): show only when the domain proves the token
            badge.setVisibility(View.GONE);
            if(meta.webvalidate != null && !meta.webvalidate.isEmpty()){
                WebValidate.ensure(mActivity, id, meta.webvalidate, this::scheduleRefresh);
                if(Boolean.TRUE.equals(WebValidate.status(id))){
                    badge.setImageBitmap(Identicon.checkBadge(badgePx));
                    badge.setVisibility(View.VISIBLE);
                }
            }
        }

        //SENDABLE is the headline figure - confirmed includes coins locked in contracts
        //and overstates what the user can actually spend. Shown in full, never clipped.
        String confirmed   = val(bal, "confirmed");
        String unconfirmed = val(bal, "unconfirmed");
        String sendable    = bal.get("sendable") == null ? confirmed : val(bal, "sendable");
        String coincount   = val(bal, "coins");

        tokenamount.setText(Format.tidyAmount(sendable));

        //Everything the headline number leaves out, for THIS token - not just Minima
        StringBuilder sub = new StringBuilder();
        String locked = Format.subtract(confirmed, sendable);
        if(isPositive(locked)){
            sub.append("locked ").append(locked);
        }
        if(isPositive(unconfirmed)){
            if(sub.length() > 0){ sub.append("  ·  "); }
            sub.append("unconfirmed ").append(Format.tidyAmount(unconfirmed));
        }
        if(!"0".equals(coincount) && !"—".equals(coincount)){
            if(sub.length() > 0){ sub.append("  ·  "); }
            sub.append(coincount).append(" coins");
        }

        if(sub.length() > 0){
            breakdown.setText(sub.toString());
            breakdown.setVisibility(View.VISIBLE);
        }else{
            breakdown.setVisibility(View.GONE);
        }

        return row;
    }

    private static String val(JSONObject zBal, String zKey){
        Object v = zBal.get(zKey);
        if(v == null){
            return "0";
        }
        String s = String.valueOf(v);
        return (s.isEmpty() || "null".equals(s)) ? "0" : s;
    }

    /** True when the string parses as a number greater than zero. */
    private static boolean isPositive(String zValue){
        try{
            return new java.math.BigDecimal(zValue).signum() > 0;
        }catch(Exception exc){
            return false;
        }
    }
}
