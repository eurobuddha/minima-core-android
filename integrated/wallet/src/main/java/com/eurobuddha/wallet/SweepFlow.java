package com.eurobuddha.wallet;

import android.text.InputType;
import static com.eurobuddha.wallet.SweepPlanner.*;
import static com.eurobuddha.minimacore.i18n.R.string.*;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.wallet.comms.NodeLink;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import android.graphics.Typeface;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.utils.json.JSONArray;
import org.minima.utils.json.JSONObject;

import com.eurobuddha.wallet.comms.NodeApi;

/** Standalone SweepFlow UI and batching port, with the integrated wallet's session/signing
 * boundary and exact transaction confirmation. Source: standalone wallet commit 690e9bc. */
public class SweepFlow {
    private final MainActivity act;

    private final java.util.function.BooleanSupplier session;
    private final NodeLink node;
    private final TxnFactory factory;
    private final SweepPending pending;
    private boolean running;
    public SweepFlow(MainActivity zAct) {
        act=zAct;session=act.sessionAuthorization();node=act.node();factory=act.factory();pending=new SweepPending(act);
    }
    private String t(int resource,Object...args){return Language.text(act,resource,args);}
    private boolean current(){return session.getAsBoolean()&&!act.isDestroyed();}

    /** Entry point: refresh the coin list, then use the standalone destination/review flow. */
    public void start() {
        if(!current()||running)return;
        if(!act.isPaired()||node==null){Toast.makeText(act,t(l10n_sweep_connect),Toast.LENGTH_LONG).show();return;}
        try {
            if(!pending.transactions().isEmpty()) {
                showPending();
                return;
            }
        }catch(Exception error){showReport(t(l10n_sweep_pending),t(l10n_sweep_store_error),null,null);return;}
        AtomicBoolean once=new AtomicBoolean();
        node.coinsRelevant(new NodeApi.Cb(){
            public void onResult(org.json.JSONObject reply){
                if(!once.compareAndSet(false,true)||!current())return;
                act.runAuthenticatedWalletWork(()->{
                    if(reply==null||!Boolean.TRUE.equals(reply.opt("status"))||reply.optJSONArray("response")==null)throw new IllegalStateException();
                    JSONArray coins=(JSONArray)new org.minima.utils.json.parser.JSONParser().parse(reply.getJSONArray("response").toString());
                    HashSet<String> reserved=new HashSet<>(pending.coins());reserved.addAll(NftView.pendingCoinIds());
                    return plan(coins,act::keyIndexForAddress,reserved);
                },plan->{
                    if(!current())return;
                    if(plan.isEmpty()){showReport(t(l10n_sweep_empty),t(l10n_sweep_empty_body)+"\n"+joinLines(plan.skipped),null,null);return;}
                    promptDestination(plan);
                },error->{if(current())showReport(t(l10n_sweep_title),t(l10n_sweep_load_error),null,null);});
            }
            public void onError(String error){if(once.compareAndSet(false,true)&&current())showReport(t(l10n_sweep_title),t(l10n_sweep_load_error),null,null);}
        });
    }

    private void showPending(){
        if(!current())return;
        act.runAuthenticatedWalletWork(pending::savedTransactions,saved->{
            if(!current())return;
            StringBuilder summary=new StringBuilder(t(l10n_sweep_pending_body)).append("\n\n").append(t(l10n_sweep_replay_help));
            for(TxnFactory.BuiltTxn built:saved){
                summary.append("\n\n").append(built.getTransactionID().to0xString());
                for(org.minima.objects.Coin output:built.getTransaction().getAllOutputs())summary.append("\n").append(output.getAddress().to0xString());
            }
            TextView text=new TextView(act);text.setText(summary.toString());text.setTextIsSelectable(true);text.setTextColor(Design.text());text.setPadding(dp(20),dp(12),dp(20),dp(8));
            ScrollView wrap=new ScrollView(act);wrap.addView(text);
            new WalletDialog(act).setTitle(t(l10n_sweep_pending)).setView(wrap)
                .setPositiveButton(t(l10n_sweep_check),(d,w)->trackConfirmations("","",new ArrayList<>(),new ArrayList<>()))
                .setNeutralButton(t(l10n_sweep_replay),(d,w)->replay(saved,0))
                .setNegativeButton(t(l10n_sweep_cancel),null).show();
        },error->{if(current())showReport(t(l10n_sweep_pending),t(l10n_sweep_store_error),null,null);});
    }
    /** Explicit retry reuses the exact stored transaction and signatures, never a new WOTS leaf. */
    private void replay(List<TxnFactory.BuiltTxn> saved,int index){
        if(!current())return;
        if(index>=saved.size()){trackConfirmations("","",new ArrayList<>(),new ArrayList<>());return;}
        TxnFactory.BuiltTxn built=saved.get(index);
        try {if(!pending.transactions().containsKey(built.getTransactionID().to0xString())){replay(saved,index+1);return;}}
        catch(Exception invalid){showReport(t(l10n_sweep_pending),t(l10n_sweep_store_error),null,null);return;}
        AtomicBoolean once=new AtomicBoolean();
        node.publish(built,new NodeApi.Cb(){
            public void onResult(org.json.JSONObject reply){
                if(!once.compareAndSet(false,true)||!current())return;
                try{pending.receipt(built.getTransactionID().to0xString(),reply);}catch(Exception unknown){}
                replay(saved,index+1);
            }
            public void onError(String error){if(once.compareAndSet(false,true)&&current())replay(saved,index+1);}
        });
    }

    private void promptDestination(final Plan zPlan) {
        if(!current())return;
        final EditText addr = new EditText(act);
        addr.setHint(t(l10n_sweep_destination));
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addr.setTextColor(Design.text());
        TextView note = new TextView(act);
        note.setTextColor(Design.text());
        note.setTextSize(13f);
        note.setText(t(l10n_sweep_description,zPlan.jobs.size()));
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(note);
        wrap.addView(addr, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new WalletDialog(act)
                .setTitle(t(l10n_sweep_title))
                .setView(wrap)
                .setNeutralButton(t(l10n_sweep_qr), (d, w) -> act.scanQr(s -> review(zPlan, s == null ? "" : s.trim())))
                .setPositiveButton(t(l10n_sweep_review), (d, w) -> review(zPlan, addr.getText().toString().trim()))
                .setNegativeButton(t(l10n_sweep_cancel), null)
                .show();
    }

    /** Validate Minima's checksum, a 32-byte address and its canonical Mx encoding.
     * This cannot establish that another wallet holds the corresponding spending keys. */
    static boolean isValidDestination(String destination) {
        if (destination == null || destination.length() > 128
                || !destination.matches("(?i)Mx[0-9a-z]+")) return false;
        try {
            MiniData address = org.minima.objects.Address.convertMinimaAddress(destination);
            return address.getLength() == 32
                    && org.minima.objects.Address.makeMinimaAddress(address).equalsIgnoreCase(destination);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    static boolean destinationConfirmed(String destination, String confirmation) {
        return isValidDestination(destination) && confirmation != null
                && destination.equalsIgnoreCase(confirmation)
                && isValidDestination(confirmation);
    }

    private void review(final Plan zPlan, final String zDest) {
        if(!current()||running)return;
        try {
            if (!isValidDestination(zDest)) throw new IllegalArgumentException(t(l10n_sweep_invalid));

            StringBuilder sb = new StringBuilder();
            sb.append(t(l10n_sweep_to)).append("\n").append(zDest).append("\n");
            try {
                String d0x = TxnFactory.parseAddress(zDest).to0xString();
                Integer ki = act.keyIndexForAddress(d0x);
                if (ki != null) {
                    sb.append("\n⚠ ").append(t(l10n_sweep_self,ki)).append("\n");
                }
            } catch (Exception ignore) { }

            sb.append("\n").append(t(l10n_sweep_moving)).append("\n");
            for (Map.Entry<String, String> e : zPlan.totals.entrySet()) {
                sb.append("  ").append(localLine(e.getKey())).append(": ").append(localLine(e.getValue())).append("\n");
            }
            if (!zPlan.skipped.isEmpty()) {
                sb.append("\n").append(t(l10n_sweep_skipping,zPlan.skipped.size())).append("\n")
                  .append(joinLines(zPlan.skipped)).append("\n");
            }
            if (!zPlan.oversizedWarnings.isEmpty()) {
                sb.append("\n⚠ ").append(t(l10n_sweep_heavy,zPlan.oversizedWarnings.size())).append("\n")
                  .append(joinLines(zPlan.oversizedWarnings)).append("\n");
            }

            sb.append("\n").append(t(l10n_sweep_signatures,zPlan.jobs.size(),zPlan.signatures));
            List<Integer> seen = new ArrayList<>();
            for (Job j : zPlan.jobs) {
                for (JSONObject c : j.coins) {
                    Integer ki = act.keyIndexForAddress(Nft.str(c, "address"));
                    if (ki != null && !seen.contains(ki)) {
                        seen.add(ki);
                        sb.append("\n  ").append(t(l10n_sweep_key,ki,act.keyUses().currentUses(ki),Util.WOTS_MAX_USES));
                    }
                }
            }

            sb.append("\n\n").append(t(l10n_sweep_warning));

            TextView msg = new TextView(act);
            msg.setText(sb.toString());
            msg.setTextIsSelectable(true);
            msg.setTypeface(Typeface.MONOSPACE);
            msg.setTextColor(Design.text());
            msg.setTextSize(12f);
            msg.setPadding(dp(20), dp(12), dp(20), dp(8));
            ScrollView wrap = new ScrollView(act);
            wrap.addView(msg);

            new WalletDialog(act)
                    .setTitle(t(l10n_sweep_confirm))
                    .setView(wrap)
                    .setPositiveButton(t(l10n_sweep_confirm_destination), (d, w) -> confirmDestination(zPlan, zDest))
                    .setNegativeButton(t(l10n_sweep_cancel), null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(act, String.valueOf(e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Execute — sequential build+sign → publish, same contract as the collection send
    // ---------------------------------------------------------------------------------------------

    private void confirmDestination(final Plan zPlan, final String zDest) {
        if(!current()||running)return;
        if (!isValidDestination(zDest)) return;
        final EditText confirmation = new EditText(act);
        confirmation.setHint(t(l10n_sweep_repeat_address));
        confirmation.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        confirmation.setTextColor(Design.text());
        TextView note = new TextView(act);
        note.setTextColor(Design.text());
        note.setText(t(l10n_sweep_address_check) + "\n\n" + zDest);
        note.setTextIsSelectable(true);
        LinearLayout contents = new LinearLayout(act);
        contents.setOrientation(LinearLayout.VERTICAL);
        contents.setPadding(dp(20), dp(8), dp(20), dp(8));
        contents.addView(note);
        contents.addView(confirmation, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ScrollView wrap = new ScrollView(act);
        wrap.addView(contents);
        final androidx.appcompat.app.AlertDialog dialog = new WalletDialog(act)
                .setTitle(t(l10n_sweep_confirm_destination))
                .setView(wrap)
                .setPositiveButton(t(l10n_sweep_sign), (d, w) -> run(zPlan, zDest, confirmation.getText().toString().trim()))
                .setNegativeButton(t(l10n_sweep_cancel), null)
                .create();
        dialog.show();
        final android.widget.Button execute = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
        execute.setEnabled(false);
        confirmation.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable text) {
                execute.setEnabled(destinationConfirmed(zDest, text.toString().trim()));
            }
        });
    }

    private void run(final Plan zPlan, final String zDest, final String confirmation) {
        if (!destinationConfirmed(zDest, confirmation)) return;
        if(!current()||running)return;
        try {
            act.vault().assertSigningAllowed();
        } catch (SeedVault.SigningNotAllowedException block) {
            Toast.makeText(act, block.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        running=true;
        final TextView progress = new TextView(act);
        progress.setTextColor(Design.text());
        progress.setTextSize(14f);
        progress.setPadding(dp(24), dp(20), dp(24), dp(8));
        final boolean[] cancelled = {false};
        final androidx.appcompat.app.AlertDialog pd = new WalletDialog(act)
                .setTitle(t(l10n_sweep_working))
                .setView(progress)
                .setCancelable(false)
                .setNegativeButton(t(l10n_sweep_stop), (d, w) -> cancelled[0] = true)
                .create();
        pd.show();
        //A Dozing phone drops its peers and mined transactions evaporate before block
        //inclusion. Hold the screen through the whole batch.
        if (pd.getWindow() != null) {
            pd.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        next(zPlan, zDest, 0, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                progress, pd, cancelled);
    }

    private void next(final Plan plan,final String destination,final int i,
            final List<String> txpowids,final List<String> sentCoinIds,final List<String> failures,
            final TextView progress,final androidx.appcompat.app.AlertDialog dialog,final boolean[] cancelled){
        if(!current()){running=false;dialog.dismiss();return;}
        if(i>=plan.jobs.size()||cancelled[0]){finish(plan,destination,i,txpowids,sentCoinIds,failures,dialog);return;}
        Job job=plan.jobs.get(i);
        progress.setText(localLine(job.label)+"\n"+t(l10n_sweep_progress,i+1,plan.jobs.size()));
        final Runnable advance=()->next(plan,destination,i+1,txpowids,sentCoinIds,failures,progress,dialog,cancelled);
        final AtomicBoolean resolved=new AtomicBoolean();
        act.resolveInputs(job.coins,new MainActivity.InputsCb(){
            public void onResolved(List<TxnFactory.InputCoin> inputs){
                if(!resolved.compareAndSet(false,true))return;
                if(!current()){dialog.dismiss();return;}
                act.runSigningWork(()->{
                    if(!current())throw new IllegalStateException(t(l10n_wallet_session_changed));
                    java.util.Set<String> reserved=pending.coins();reserved.addAll(NftView.pendingCoinIds());
                    for(TxnFactory.InputCoin input:inputs)if(reserved.contains(input.getCoinID().to0xString().toLowerCase(java.util.Locale.ROOT)))throw new IllegalStateException(t(l10n_sweep_pending));
                    TxnFactory.BuiltTxn built;
                    if(job.whole)built=factory.buildWholeTransfer(inputs.get(0),destination,Util.newTxnId());
                    else {
                        MiniNumber sum=MiniNumber.ZERO;for(TxnFactory.InputCoin input:inputs)sum=sum.add(input.getAmount());
                        built=factory.buildSend(inputs,destination,sum,new MiniData(job.tokenid),MiniNumber.ZERO,Util.newTxnId());
                    }
                    // Save before any post; lost callbacks/restarts never permit a new sweep signature.
                    pending.reserve(built);return built;
                },built->{
                    if(!current()){dialog.dismiss();return;}
                    Map<String,Long> marked=new LinkedHashMap<>();
                    for(JSONObject coin:job.coins){String id=Nft.str(coin,"coinid");marked.put(id,NftView.markPending(id));}
                    AtomicBoolean published=new AtomicBoolean();
                    node.publish(built,new NodeLink.SizeAwareCb(){
                        public void onSizeRefusal(org.json.JSONObject reply){
                            if(!published.compareAndSet(false,true)||!current())return;
                            try {
                                // An acknowledged rejection releases only this first attempt's reservation.
                                // If saving fails, retain the signed bytes and do not create replacements.
                                pending.rejectedSize(built,reply);
                                for(Map.Entry<String,Long> mark:marked.entrySet())NftView.clearSweepPending(mark.getKey(),mark.getValue());
                            }catch(Exception unavailable){
                                failures.add(localLine(job.label)+": "+t(l10n_sweep_unknown));advance.run();return;
                            }
                            if(!job.whole&&job.coins.size()>1){
                                progress.setText(t(l10n_sweep_splitting));
                                plan.jobs.remove(i);plan.jobs.addAll(i,split(job));
                                next(plan,destination,i,txpowids,sentCoinIds,failures,progress,dialog,cancelled);
                                return;
                            }
                            failures.add(localLine(job.label)+": "+t(l10n_sweep_single_large));advance.run();
                        }
                        public void onResult(org.json.JSONObject reply){
                            if(!published.compareAndSet(false,true)||!current())return;
                            try {
                                pending.receipt(built.getTransactionID().to0xString(),reply);
                                txpowids.add(SweepPending.receiptId(built.getTransactionID().to0xString(),reply));
                                for(JSONObject coin:job.coins)sentCoinIds.add(Nft.str(coin,"coinid"));
                            }catch(Exception unknown){failures.add(localLine(job.label)+": "+t(l10n_sweep_unknown));}
                            advance.run();
                        }
                        public void onError(String message){
                            if(!published.compareAndSet(false,true)||!current())return;
                            failures.add(localLine(job.label)+": "+t(l10n_sweep_unknown));advance.run();
                        }
                    });
                },error->{if(current()){failures.add(localLine(job.label)+": "+t(l10n_sweep_prepare_error));advance.run();}});
            }
            public void onError(String error){if(resolved.compareAndSet(false,true)&&current()){failures.add(localLine(job.label)+": "+t(l10n_sweep_prepare_error));advance.run();}}
        });
    }

    /** Broadcasting is over (complete, stopped, or failed) — now confirm ON-CHAIN. */
    private void finish(Plan plan,String destination,int attempted,List<String> txpowids,
            List<String> coinids,List<String> failures,androidx.appcompat.app.AlertDialog dialog){
        running=false;dialog.dismiss();if(!current())return;act.reload();
        String header=t(l10n_sweep_summary,txpowids.size(),plan.jobs.size())+"\n"+joinLines(failures);
        if(attempted<plan.jobs.size())header+="\n"+t(l10n_sweep_stopped);
        try {
            if(pending.transactions().isEmpty()){showReport(t(l10n_sweep_title),header,null,null);return;}
            trackConfirmations(destination,header,coinids,txpowids);
        }catch(Exception error){showReport(t(l10n_sweep_pending),t(l10n_sweep_store_error),null,null);}
    }

    /** Same confirmation screen as standalone; verify exact transaction inclusion, never coin absence. */
    private void trackConfirmations(String destination,String header,List<String> coinids,List<String> txpowids){
        if(!current())return;
        final TextView message=new TextView(act);message.setTextColor(Design.text());message.setTextIsSelectable(true);
        message.setPadding(dp(24),dp(16),dp(24),dp(8));
        ScrollView wrap=new ScrollView(act);wrap.addView(message);
        AtomicBoolean stopped=new AtomicBoolean();
        androidx.appcompat.app.AlertDialog dialog=new WalletDialog(act).setTitle(t(l10n_sweep_confirming))
            .setView(wrap).setNegativeButton(t(l10n_sweep_hide),(d,w)->stopped.set(true)).create();
        dialog.setOnDismissListener(d->stopped.set(true));dialog.show();
        if(dialog.getWindow()!=null)dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
        final int[] polls={0};
        Runnable poll=new Runnable(){public void run(){
            if(stopped.get()||!current())return;
            try {
                Map<String,String> entries=pending.transactions();
                if(entries.isEmpty()){dialog.dismiss();act.reload();showReport(t(l10n_sweep_complete),header+"\n"+t(l10n_sweep_confirmed),null,null);return;}
                message.setText(header+"\n"+t(l10n_sweep_waiting,entries.size()));
                if(++polls[0]>45){dialog.dismiss();showReport(t(l10n_sweep_pending),t(l10n_sweep_pending_body),t(l10n_sweep_check),()->trackConfirmations(destination,header,coinids,txpowids));return;}
                checkTransactions(new ArrayList<>(entries.entrySet()),0,()->{
                    if(!stopped.get()&&current())handler.postDelayed(this,20_000);
                });
            }catch(Exception unavailable){dialog.dismiss();showReport(t(l10n_sweep_pending),t(l10n_sweep_store_error),null,null);}
        }};handler.post(poll);
    }
    private void checkTransactions(List<Map.Entry<String,String>> rows,int index,Runnable done){
        if(!current())return;if(index>=rows.size()){done.run();return;}
        Map.Entry<String,String> row=rows.get(index);Runnable next=()->checkTransactions(rows,index+1,done);
        if(row.getValue().isEmpty()) {
            // Recover a lost post response by matching the signed transaction ID, without signing.
            node.raw("txpow relevant:true max:100",new NodeApi.Cb(){
                final AtomicBoolean once=new AtomicBoolean();
                public void onResult(org.json.JSONObject reply){
                    if(!once.compareAndSet(false,true)||!current())return;
                    try {
                        if(Boolean.TRUE.equals(reply.opt("status"))&&reply.optJSONArray("response")!=null){
                            org.json.JSONArray list=reply.getJSONArray("response");
                            for(int i=0;i<list.length();i++)try{
                                org.json.JSONObject receipt=new org.json.JSONObject().put("status",true).put("response",list.getJSONObject(i));
                                pending.receipt(row.getKey(),receipt);break;
                            }catch(Exception otherTransaction){}
                        }
                    }catch(Exception ignored){}next.run();
                }
                public void onError(String error){if(once.compareAndSet(false,true)&&current())next.run();}
            });return;
        }
        node.raw("txpow onchain:"+row.getValue(),new NodeApi.Cb(){
            final AtomicBoolean once=new AtomicBoolean();
            public void onResult(org.json.JSONObject reply){
                if(!once.compareAndSet(false,true)||!current())return;
                try {if(SweepPending.included(reply))pending.confirmed(row.getKey(),row.getValue(),reply);}catch(Exception unavailable){}
                next.run();
            }
            public void onError(String error){if(once.compareAndSet(false,true)&&current())next.run();}
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------------------------------

    private void showReport(String zTitle, String zBody, String zAction, final Runnable zOnAction) {
        if(!current())return;
        TextView msg = new TextView(act);
        msg.setText(zBody);
        msg.setTextIsSelectable(true);
        msg.setTypeface(Typeface.MONOSPACE);
        msg.setTextColor(Design.text());
        msg.setTextSize(12f);
        msg.setPadding(dp(20), dp(12), dp(20), dp(8));
        ScrollView wrap = new ScrollView(act);
        wrap.addView(msg);
        WalletDialog b = new WalletDialog(act);
        b.setTitle(zTitle).setView(wrap).setNegativeButton(t(l10n_sweep_close), null);
        if (zAction != null) b.setPositiveButton(zAction, (d, w) -> zOnAction.run());
        b.show();
    }

    private String joinLines(List<String> zLines) {
        StringBuilder sb = new StringBuilder();
        for (String s : zLines) sb.append("  ").append(localLine(s)).append("\n");
        return sb.toString();
    }

    private String localLine(String line) {
        int cut=line.indexOf(" — ");
        if(cut>=0){
            String detail=line.substring(cut+3);int resource=0;
            if(detail.startsWith("already confirming"))resource=l10n_sweep_pending_skip;
            else if(detail.startsWith("address "))resource=l10n_sweep_foreign_skip;
            else if(detail.startsWith("UNSTAMPED"))resource=l10n_sweep_locked_skip;
            else if(detail.startsWith("estimated ~"))resource=l10n_sweep_large_item;
            if(resource!=0)return line.substring(0,cut)+" — "+t(resource);
        }
        return line.replace("NFT / state items",t(l10n_sweep_items)).replace("Minima (state)",t(l10n_sweep_native_state))
            .replace("item(s), sent whole",t(l10n_sweep_whole)).replace("coin(s)",t(l10n_sweep_coins)).replace("(part ","("+t(l10n_sweep_part)+" ")
            .replace("(split A)",t(l10n_sweep_split_a)).replace("(split B)",t(l10n_sweep_split_b));
    }

    private int dp(int zDp) {
        return Math.round(zDp * act.getResources().getDisplayMetrics().density);
    }
}
