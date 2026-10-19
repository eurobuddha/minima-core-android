package com.eurobuddha.utxo;

import android.widget.LinearLayout;
import android.widget.TextView;
import com.eurobuddha.minimacore.i18n.Language;
import org.json.JSONObject;
import static com.eurobuddha.minimacore.i18n.R.string.*;

/** Explorer's on-demand proof display, with the wallet's strict inclusion parser.
 * Read-only: never changes stored receipts, reservations or transaction outcomes. */
final class HistoryChainCheck {
    static final class Result {
        final JSONObject proof;
        final boolean missing;
        Result(JSONObject proof,boolean missing){this.proof=proof;this.missing=missing;}
    }
    static Result result(JSONObject reply) {
        if(reply==null||!Boolean.TRUE.equals(reply.opt("status")))throw TransactionEvidence.invalid();
        JSONObject response=reply.optJSONObject("response");
        if(response!=null&&Boolean.FALSE.equals(response.opt("found")))return new Result(null,true);
        return new Result(TransactionEvidence.observedInclusion(reply),false);
    }
    static void show(MainActivity act,LinearLayout parent,String txpowid) {
        TextView status=new TextView(act);
        status.setTextSize(12f);status.setTextColor(Design.dim());
        Language.setText(status,Language.msg(l10n_pools_checking_chain));parent.addView(status);
        if(txpowid==null||!txpowid.matches("(?i)0x[0-9a-f]{64}")
                ||new java.math.BigInteger(txpowid.substring(2),16).signum()==0) {
            Language.setText(status,Language.msg(l10n_nft_history_unverified_status));return;
        }
        final boolean[] answered={false};
        act.node().cmd("txpow onchain:"+txpowid,new NodeApi.Cb(){
            private boolean accept(){
                if(answered[0]||act.isFinishing()||act.isDestroyed())return false;
                answered[0]=true;return true;
            }
            public void onResult(JSONObject reply){
                if(!accept())return;
                try {
                    Result result=result(reply);
                    CharSequence evidence=result.missing
                            ?Language.join(Language.msg(l10n_pools_not_found_node)," · ",Language.msg(l10n_nft_history_unverified_status))
                            :Language.msg(l10n_explorer_on_chain,result.proof.optString("block"),result.proof.optString("confirmations"));
                    Language.setText(status,Language.join(evidence,"\n",Language.msg(l10n_pools_last_node_check,Language.stamp(System.currentTimeMillis(),"yMMMdHms"))));
                }catch(RuntimeException malformed){Language.setText(status,Language.msg(l10n_nft_history_unverified_status));}
            }
            public void onError(String message){
                if(accept())Language.setText(status,Language.msg(l10n_nft_history_unverified_status));
            }
        });
    }
}
