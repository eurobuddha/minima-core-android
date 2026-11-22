package com.eurobuddha.vestr;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Contract detail + the Collect transaction (spend the contract coin, pay out what has vested, return
 *  the remainder to the script with state carried forward). */
public class ContractDetailActivity extends SubActivity {

    private Contract c;
    private int tip = 0;
    private BigDecimal cancollect = BigDecimal.ZERO, change = BigDecimal.ZERO;
    private boolean mathsReady = false;
    private CollectRun run;   // the collect in progress; null = none (or the last one failed and may be retried)
    private boolean checking; // the spend check before a collect is built is in flight
    private boolean spending; // the node says a txn already spends this coin, or it is spent: no collect from this screen
    private EditText burnInput;
    private TextView collectBtn, status, availableTv;

    /** Parse the contract coin passed in via intent, render it, then fetch the tip to run the maths. */
    @Override
    protected void init() {
        title(Language.msg(l10n_vestr_contract));
        try { c = Contract.from(new JSONObject(getIntent().getStringExtra("coin"))); }
        catch (Exception e) { finish(); return; }
        if (!c.safeForCommands()) {
            Toast.makeText(this, Language.msg(l10n_vestr_unsafe_contract), Toast.LENGTH_LONG).show();
            finish(); return;
        }
        render();
        checkSpend();
    }

    /** Back on this screen while the button is blocked because a txn spends the coin: ask again, so the button comes
     *  back once the node dropped a txn that was never mined (it leaves the node's memory within 60 to 90 minutes). */
    @Override protected void onResume() {
        super.onResume();
        if (c != null && spending && run == null && !checking) checkSpend();
    }

    /** On open: a coin a txn already spends (a collect from a screen that has since been recreated) offers no collect.
     *  Then the tip and the maths, so the button is drawn once with all three known. */
    private void checkSpend() {
        node.cmd(Contract.spendCheckCommand(c.coinid), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { fetchBlock(j); }
            @Override public void onError(String m) { fetchBlock(null); }
        });
    }

    /** Fetch the chain tip, judge the spend check's reply against it (no tip: still syncing, the screen keeps loading),
     *  then run the collect maths against it. */
    private void fetchBlock(final JSONObject spendReply) {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) try { tip = Integer.parseInt(r.optString("block", "0")); } catch (Exception ignored) {}
                spending = Contract.alreadySpending(spendReply, tip);
                runMaths();
            }
            @Override public void onError(String m) {}
        });
    }

    /** Compute the exact collectible amount via runscript on the contract's checkMaths (SIGDIG(2)
     *  matches the on-chain script — client-side decimal rounding does NOT). */
    private void runMaths() {
        if (tip <= 0) { render(); return; }
        // Run the real on-chain CHECK_MATHS in the node's VM (runscript) with the same prevstate +
        // @AMOUNT/@BLOCK/@COINAGE globals the spend would see, and read cancollect/change straight back
        // out of its variables. Doing the division client-side in BigDecimal would round differently from
        // the script's SIGDIG(2) and the resulting txnoutput amount would fail the contract's check.
        String prevstate = "{\"1\":\"" + Contract.state(c.raw, 1) + "\",\"2\":\"" + Contract.state(c.raw, 2)
                + "\",\"3\":\"" + Contract.state(c.raw, 3) + "\",\"4\":\"" + Contract.state(c.raw, 4)
                + "\",\"5\":\"" + Contract.state(c.raw, 5) + "\"}";
        String globals = "{\"@AMOUNT\":\"" + c.amount.toPlainString() + "\",\"@BLOCK\":\"" + tip
                + "\",\"@COINAGE\":\"" + c.createdBlock + "\"}";
        node.cmd("runscript script:\"" + VestingContract.CHECK_MATHS + "\" prevstate:" + prevstate + " globals:" + globals,
                new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                JSONObject vars = r == null ? null : r.optJSONObject("variables");
                BigDecimal[] plan = c.collectPlan(vars, tip);
                cancollect = plan[0];
                change = plan[1];
                mathsReady = true;
                render();
            }
            @Override public void onError(String m) { mathsReady = true; render(); }
        });
    }

    /** Parse to BigDecimal, defaulting to zero on garbage. */
    private static BigDecimal bd(String s) { try { return new BigDecimal(s); } catch (Exception e) { return BigDecimal.ZERO; } }

    /** Rebuild the whole detail screen from current state; called again whenever tip/maths change. */
    private void render() {
        form.removeAllViews();
        BigDecimal canCollect = cancollect;
        boolean collectable = mathsReady && canCollect.signum() > 0 && !spending;

        // Available-to-collect hero card
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(collectable ? VestrDesign.GREEN_BG : VestrDesign.CARD);
        hb.setCornerRadius(dp(10));
        hero.setBackground(hb);
        TextView hl = new TextView(this);
        Language.setText(hl, Language.msg(l10n_vestr_available));
        hl.setTextColor(VestrDesign.DIM);
        hl.setTextSize(13f);
        hero.addView(hl);
        availableTv = new TextView(this);
        Language.setText(availableTv, Language.join(!mathsReady ? "…" : ContractUi.amount(canCollect), " ", VestrText.token(c.raw.opt("token"), c.tokenid)));
        availableTv.setTextColor(collectable ? VestrDesign.GREEN : VestrDesign.TEXT);
        availableTv.setTextSize(26f);
        availableTv.setTypeface(Typeface.DEFAULT_BOLD);
        hero.addView(availableTv);
        form.addView(hero);

        // Collected / Vested / Remaining
        addStat(Language.msg(l10n_vestr_collected), Language.join(ContractUi.amount(c.collected()), " ", VestrText.token(c.raw.opt("token"), c.tokenid)));
        addStat(Language.msg(l10n_vestr_total_vested), Language.join(ContractUi.amount(c.total), " ", VestrText.token(c.raw.opt("token"), c.tokenid)));
        addStat(Language.msg(l10n_vestr_remaining), Language.join(ContractUi.amount(c.amount), " ", VestrText.token(c.raw.opt("token"), c.tokenid)));

        // Schedule
        addStat(Language.msg(l10n_vestr_collect_every), VestrText.grace(VestingContract.Grace.fromHours(c.graceHours)));
        if (tip > 0) {
            long gr = c.graceBlocksRemaining(tip);
            addStat(Language.msg(l10n_vestr_grace_remaining), gr == 0 ? Language.msg(l10n_vestr_ready) : Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_vestr_blocks, (int)Math.min(Integer.MAX_VALUE, gr)));
        }
        addStat(Language.msg(l10n_vestr_starts), ContractUi.relative(c.startMs, "start"));
        addStat(Language.msg(l10n_vestr_ends), ContractUi.relative(c.endMs, "end"));
        if (tip > 0) {
            addStat(Language.msg(l10n_vestr_start_block), String.valueOf(c.startBlock));
            addStat(Language.msg(l10n_vestr_end_block), String.valueOf(c.endBlock));
        }

        // Copyable ids
        addCopy(Language.msg(l10n_vestr_token_id), c.tokenid);
        addCopy(Language.msg(l10n_vestr_coin_id), c.coinid);
        addCopy(Language.msg(l10n_vestr_withdrawal), c.unlockAddr);
        addCopy(Language.msg(l10n_vestr_contract_id), c.uid);

        // Network fee
        label(Language.msg(l10n_vestr_network_fee));
        burnInput = input("0", T_DEC);

        collectBtn = primaryButton(collectable ? Language.msg(l10n_vestr_collect_amount, ContractUi.amount(canCollect), VestrText.token(c.raw.opt("token"), c.tokenid))
                : (!mathsReady ? Language.msg(l10n_vestr_loading) : Language.msg(l10n_vestr_nothing)));
        collectBtn.setEnabled(collectable);
        collectBtn.setAlpha(collectable ? 1f : 0.4f);
        collectBtn.setOnClickListener(v -> collect(canCollect));
        status = status();
        if (spending) setStatus(status, Language.msg(l10n_vestr_already_spending), false);
    }

    /** Append a key/value stat row (label left, bold value right). */
    private void addStat(CharSequence k, CharSequence v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView kt = new TextView(this);
        Language.setText(kt, k);
        kt.setTextColor(VestrDesign.DIM);
        kt.setTextSize(14f);
        kt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(kt);
        TextView vt = new TextView(this);
        Language.setText(vt, v == null ? "" : v);
        vt.setTextColor(VestrDesign.TEXT);
        vt.setTextSize(14f);
        vt.setTypeface(Typeface.DEFAULT_BOLD);
        vt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        vt.setGravity(Gravity.END);
        row.addView(vt);
        form.addView(row);
    }

    /** Append a monospace id row that copies its value to the clipboard on tap. */
    private void addCopy(CharSequence k, final String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView kt = new TextView(this);
        Language.setText(kt, k);
        kt.setTextColor(VestrDesign.DIM);
        kt.setTextSize(12f);
        row.addView(kt);
        TextView vt = new TextView(this);
        Language.setText(vt, v);
        vt.setTextColor(VestrDesign.TEXT);
        vt.setTextSize(12f);
        vt.setTypeface(Typeface.MONOSPACE);
        vt.setOnClickListener(view -> {
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText(k, v));
            Toast.makeText(this, Language.msg(l10n_vestr_copied), Toast.LENGTH_SHORT).show();
        });
        row.addView(vt);
        form.addView(row);
    }

    // ---- collect ----

    /** Ask the node first whether a txn already spends this coin (Contract.alreadySpending); only a clean empty answer
     *  stops the collect, any other answer or none lets it go on as before. Nothing is built before the answer. */
    private void collect(BigDecimal canCollect) {
        if (spending || checking || run != null || !c.safeForCommands() || canCollect.signum() <= 0) return;
        checking = true;
        collectBtn.setEnabled(false);
        setStatus(status, Language.msg(l10n_vestr_collecting), true);
        node.cmd(Contract.spendCheckCommand(c.coinid), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (Contract.alreadySpending(j, tip)) {
                    // nothing was created on the node, so nothing is owed; the button stays off
                    checking = false;
                    spending = true;
                    setStatus(status, Language.msg(l10n_vestr_already_spending), false);
                    return;
                }
                build(canCollect);
            }
            @Override public void onError(String m) { build(canCollect); }
        });
    }

    /** Assemble the collect transaction command-by-command and run it as a strict sequence. */
    private void build(BigDecimal canCollect) {
        checking = false;
        String burn = Contract.burnParam(burnInput.getText().toString());
        BigDecimal change = this.change;   // @AMOUNT - payout (Contract.collectPlan), 0 = no change output
        String id = "vc" + System.currentTimeMillis();
        String tid = c.tokenid;

        // Build the txn in pieces because txncreate/input/output/state are separate node commands that
        // mutate one named pending txn; they must run in order against the same id (CollectRun).
        List<String> cmds = new ArrayList<>();
        cmds.add("txncreate id:" + id);
        // scriptmmr:true gives the input the MMR proof so the contract script can run when posted.
        cmds.add("txninput id:" + id + " coinid:" + c.coinid + " scriptmmr:true");
        // Pay the vested amount to the withdrawal address; storestate:false — recipient keeps no state.
        cmds.add("txnoutput id:" + id + " address:" + c.unlockAddr + " amount:" + canCollect.toPlainString()
                + " tokenid:" + tid + " storestate:false");
        if (change.signum() > 0) {
            // Return the remainder to the same contract script with storestate:true so the carried-forward
            // state below keeps the contract collectable again next grace period.
            cmds.add("txnoutput id:" + id + " address:" + c.address + " amount:" + change.toPlainString()
                    + " tokenid:" + tid + " storestate:true");
        }
        // Carry every state port forward unchanged onto the change output (the contract re-reads them).
        for (int port : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 199}) {
            cmds.add("txnstate id:" + id + " port:" + port + " value:" + Contract.state(c.raw, port));
        }
        // The node judges the finished txn (amounts, proofs, scripts at the tip) before anything is posted:
        // txnpost only queues it for mining and checks none of that.
        cmds.add("txncheck id:" + id);
        cmds.add("txnpost id:" + id + (burn.isEmpty() ? "" : " burn:" + burn));

        run = new CollectRun(id, cmds);
        step(run);
    }

    /** Send the run's next command; node.cmd is async, so each reply sends the one after it. */
    private void step(final CollectRun r) {
        String cmd = r.next();
        if (cmd == null) return;
        node.cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                switch (r.replied(j)) {
                    case RUN: step(r); break;
                    case DONE:
                        // txnpost returned the txpow it queued for mining; the custom txn row is no longer needed.
                        node.cmd("txndelete id:" + r.id, null);
                        setStatus(status, Language.msg(l10n_vestr_collected_ok), true);
                        collectBtn.postDelayed(ContractDetailActivity.this::finish, 1400);
                        break;
                    case INVALID: fail(Language.msg(l10n_vestr_check_invalid), r.id); break;
                    case UNKNOWN: postUnknown(); break;
                    default: fail(j.has("error") ? j.optString("error") : Language.msg(l10n_vestr_collect_failed), r.id);
                }
            }
            @Override public void onError(String m) {
                if (r.noReply() == CollectRun.Next.UNKNOWN) { postUnknown(); return; }
                fail(NodeApi.ERR_NOT_ENABLED.equals(m) ? Language.msg(l10n_explorer_node_retry) : VestrMessages.message(m), r.id);
            }
        });
    }

    /** txnpost may have gone through: no txndelete, no "Failed", and the button stays off so the same coin is not
     *  spent twice. */
    private void postUnknown() {
        setStatus(status, Language.msg(l10n_vestr_post_unknown), false);
    }

    /** Abort before anything was posted: drop the half-built txn, re-enable the button, and surface the error. */
    private void fail(CharSequence msg, String id) {
        node.cmd("txndelete id:" + id, null);
        run = null;
        collectBtn.setEnabled(true);
        setStatus(status, Language.msg(l10n_vestr_failed, msg), false);
    }

    /** Closed mid-build: this screen's replies are dropped from here on, so the half-built txn is deleted over a
     *  connection of its own. Never once txnpost was sent (CollectRun.abandon). */
    @Override protected void onDestroy() {
        String owed = run == null ? null : run.abandon();
        if (owed != null) NodeApi.detached(this, owed);
        super.onDestroy();
    }
}
