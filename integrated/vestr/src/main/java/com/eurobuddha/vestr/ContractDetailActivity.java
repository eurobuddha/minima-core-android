package com.eurobuddha.vestr;

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
    private EditText burnInput;
    private TextView collectBtn, status, availableTv;

    /** Parse the contract coin passed in via intent, render it, then fetch the tip to run the maths. */
    @Override
    protected void init() {
        title("Contract");
        try { c = Contract.from(new JSONObject(getIntent().getStringExtra("coin"))); }
        catch (Exception e) { finish(); return; }
        render();
        fetchBlock();
    }

    /** Fetch the chain tip, then run the collect maths against it. */
    private void fetchBlock() {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) try { tip = Integer.parseInt(r.optString("block", "0")); } catch (Exception ignored) {}
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
                if (vars != null) {
                    cancollect = bd(vars.optString("cancollect", "0"));
                    change = bd(vars.optString("change", c.amount.subtract(cancollect).toPlainString()));
                }
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
        boolean collectable = mathsReady && canCollect.signum() > 0;

        // Available-to-collect hero card
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(collectable ? VestrDesign.GREEN_BG : VestrDesign.CARD);
        hb.setCornerRadius(dp(10));
        hero.setBackground(hb);
        TextView hl = new TextView(this);
        hl.setText("Available to collect");
        hl.setTextColor(VestrDesign.DIM);
        hl.setTextSize(13f);
        hero.addView(hl);
        availableTv = new TextView(this);
        availableTv.setText((!mathsReady ? "…" : ContractUi.amount(canCollect)) + " " + c.tokenName);
        availableTv.setTextColor(collectable ? VestrDesign.GREEN : VestrDesign.TEXT);
        availableTv.setTextSize(26f);
        availableTv.setTypeface(Typeface.DEFAULT_BOLD);
        hero.addView(availableTv);
        form.addView(hero);

        // Collected / Vested / Remaining
        addStat("Collected", ContractUi.amount(c.collected()) + " " + c.tokenName);
        addStat("Total vested", ContractUi.amount(c.total) + " " + c.tokenName);
        addStat("Remaining in contract", ContractUi.amount(c.amount) + " " + c.tokenName);

        // Schedule
        addStat("Collect every", VestingContract.Grace.fromHours(c.graceHours).label);
        if (tip > 0) {
            long gr = c.graceBlocksRemaining(tip);
            addStat("Grace remaining", gr == 0 ? "ready" : gr + " blocks");
        }
        addStat("Starts", ContractUi.relative(c.startMs, "start"));
        addStat("Ends", ContractUi.relative(c.endMs, "end"));
        if (tip > 0) {
            addStat("Start block", String.valueOf(c.startBlock));
            addStat("End block", String.valueOf(c.endBlock));
        }

        // Copyable ids
        addCopy("Token id", c.tokenid);
        addCopy("Coin id", c.coinid);
        addCopy("Withdrawal address", c.unlockAddr);
        addCopy("Contract id", c.uid);

        // Network fee
        label("Network fee (burn, optional)");
        burnInput = input("0", T_DEC);

        collectBtn = primaryButton(collectable ? "Collect " + ContractUi.amount(canCollect) + " " + c.tokenName
                : (!mathsReady ? "Loading…" : "Nothing to collect yet"));
        collectBtn.setEnabled(collectable);
        collectBtn.setAlpha(collectable ? 1f : 0.4f);
        collectBtn.setOnClickListener(v -> collect(canCollect));
        status = status();
    }

    /** Append a key/value stat row (label left, bold value right). */
    private void addStat(String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView kt = new TextView(this);
        kt.setText(k);
        kt.setTextColor(VestrDesign.DIM);
        kt.setTextSize(14f);
        kt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(kt);
        TextView vt = new TextView(this);
        vt.setText(v == null ? "" : v);
        vt.setTextColor(VestrDesign.TEXT);
        vt.setTextSize(14f);
        vt.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(vt);
        form.addView(row);
    }

    /** Append a monospace id row that copies its value to the clipboard on tap. */
    private void addCopy(String k, final String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView kt = new TextView(this);
        kt.setText(k);
        kt.setTextColor(VestrDesign.DIM);
        kt.setTextSize(12f);
        row.addView(kt);
        TextView vt = new TextView(this);
        vt.setText(v);
        vt.setTextColor(VestrDesign.TEXT);
        vt.setTextSize(12f);
        vt.setTypeface(Typeface.MONOSPACE);
        vt.setOnClickListener(view -> {
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText(k, v));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        row.addView(vt);
        form.addView(row);
    }

    // ---- collect ----

    /** Assemble the collect transaction command-by-command and run it as a strict sequence. */
    private void collect(BigDecimal canCollect) {
        if (canCollect.signum() <= 0) return;
        String burn = burnInput.getText().toString().trim();
        BigDecimal change = this.change;   // exact runscript value (@AMOUNT - cancollect), NOT recomputed here
        String id = "vc" + System.currentTimeMillis();
        String tid = c.tokenid;

        // Build the txn in pieces because txncreate/input/output/state are separate node commands that
        // mutate one named pending txn; they must run in order against the same id, hence runSequence.
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
        String post = "txnpost id:" + id;
        if (!burn.isEmpty() && isPositive(burn)) post += " burn:" + burn;
        cmds.add(post);

        collectBtn.setEnabled(false);
        setStatus(status, "Collecting…", true);
        runSequence(cmds, 0, id);
    }

    /** Run the build/post commands in order; clean up the txn on completion or error. */
    private void runSequence(final List<String> cmds, final int i, final String id) {
        // node.cmd is async, so we recurse from each callback rather than loop — this guarantees each
        // command finishes (and didn't fail) before the next mutates the same txn. Index past the end = done.
        if (i >= cmds.size()) {
            node.cmd("txndelete id:" + id, null);
            setStatus(status, "✓ Collected — tokens will arrive shortly.", true);
            collectBtn.postDelayed(this::finish, 1400);
            return;
        }
        node.cmd(cmds.get(i), new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                // every build step returns status:true; txnpost returns the posted txpow
                if (!j.optBoolean("status", false)) { fail(j.optString("error", "Collect failed"), id); return; }
                runSequence(cmds, i + 1, id);
            }
            @Override public void onError(String m) {
                fail(NodeApi.ERR_NOT_ENABLED.equals(m) ? "The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry." : m, id);
            }
        });
    }

    /** Abort: drop the half-built txn, re-enable the button, and surface the error. */
    private void fail(String msg, String id) {
        node.cmd("txndelete id:" + id, null);
        collectBtn.setEnabled(true);
        setStatus(status, "Failed: " + msg, false);
    }

    /** True if s parses as a strictly-positive number. */
    private boolean isPositive(String s) { try { return new BigDecimal(s).signum() > 0; } catch (Exception e) { return false; } }
}
