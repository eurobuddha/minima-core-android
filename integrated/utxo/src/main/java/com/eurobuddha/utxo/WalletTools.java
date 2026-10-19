package com.eurobuddha.utxo;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The UTXO operations behind the Wallet tab's "Tools" dropdown: Split, Consolidate, Distribute,
 * Untrack. Split/merge build a custom transaction via {@link TxnBuilder}; Distribute hands off to
 * {@link DistributeManager}; the auto-consolidate uses the node's native "consolidate" command.
 */
public class WalletTools {

    /** Callback for surfacing a one-line status message to the user (ok = success vs. error styling). */
    public interface Status {
        void show(CharSequence message, boolean ok);
    }

    private static final int SPLIT_SCALE = 11;

    private final MainActivity act;
    private final Status status;
    private long statusGeneration;

    public WalletTools(MainActivity act, Status status) {
        this.act = act;
        this.status = status;
    }

    /** Reuse SendView's literal selection comparison across confirmation and async lookup. */
    private final class Review {
        final List<Coin> coins = new ArrayList<>();
        final String[] snapshot;
        final String tokenid;
        private boolean accepted;
        private long statusOwner = -1;
        private void claimStatus() { if (statusOwner < 0) statusOwner = ++statusGeneration; }
        final Status status = (message, ok) -> {
            claimStatus();
            if (statusOwner == statusGeneration && !act.isFinishing() && !act.isDestroyed())
                WalletTools.this.status.show(message, ok);
        };
        Review() {
            List<Coin> selected = act.selectedCoins();
            snapshot = TxnUtil.selectionSnapshot(selected);
            for (Coin source : selected) {
                if (source == null) { coins.add(null); continue; }
                // Freeze the command and display fields; these tools do not replay NFT state.
                Coin c = new Coin();
                c.coinid=source.coinid;c.tokenid=source.tokenid;c.amount=source.amount;c.rawAmount=source.rawAmount;
                c.address=source.address;c.miniaddress=source.miniaddress;
                c.confirmed=source.confirmed;c.sendable=source.sendable;c.spent=source.spent;
                c.tokenName=source.tokenName;c.tokenNameProvided=source.tokenNameProvided;
                coins.add(c);
            }
            tokenid=coins.isEmpty()?Util.MINIMA_TOKENID:coins.get(0)==null?null:coins.get(0).tokenid;
        }
        boolean accept(boolean spend) {
            if (accepted) return false;
            accepted=true;
            claimStatus();
            return current(spend);
        }
        boolean current(boolean spend) {
            if (act.isFinishing() || act.isDestroyed()) return false;
            boolean valid=Util.isValidHexId(tokenid);
            java.util.Set<String> ids=new java.util.HashSet<>();
            for (Coin c:coins) {
                if (c==null || !Util.isValidHexId(c.coinid) || !TransactionFields.same(tokenid,c.tokenid)
                        || !ids.add(c.coinid.toLowerCase(java.util.Locale.ROOT))
                        || (spend && (!c.isWellFormed() || !c.confirmed || !c.sendable || c.spent))) {valid=false;break;}
            }
            if (valid && java.util.Arrays.equals(snapshot,TxnUtil.selectionSnapshot(act.selectedCoins()))) return true;
            status.show(Language.msg(l10n_nft_tools_review_again),false);
            return false;
        }
    }

    // ---- shared helpers ----

    /** Views placed inside a dialog must be built on the DIALOG's themed context, not the activity's
     *  DayNight one — otherwise an EditText on a Light dialog inherits the phone's dark-theme text colour
     *  (light grey on white; seen on the S23). */
    private android.content.Context dialogCtx() {
        return new android.view.ContextThemeWrapper(act, Design.dialogTheme());
    }

    /** Build a numeric EditText (decimal toggles the decimal-point flag) for the dialog forms. */
    private EditText input(CharSequence hint, String preset, boolean decimal) {
        EditText e = new EditText(dialogCtx());
        e.setTextColor(Design.text());
        e.setHintTextColor(Design.dim());
        Language.hint(e, hint);
        Language.setText(e, preset);
        e.setInputType(decimal
                ? (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL)
                : InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    /** Wrap dialog children in a padded vertical layout. */
    private LinearLayout box(View... children) {
        LinearLayout l = new LinearLayout(dialogCtx());
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * act.getResources().getDisplayMetrics().density);
        l.setPadding(pad, pad / 2, pad, 0);
        for (View c : children) l.addView(c);
        return l;
    }

    /** Total the amounts of a coin list (bad amounts are skipped). */
    private BigDecimal sum(List<Coin> coins) {
        BigDecimal t = BigDecimal.ZERO;
        for (Coin c : coins) {
            try { t = t.add(new BigDecimal(c.amount)); } catch (Exception ignored) {}
        }
        return t;
    }

    /** Resolve a human token name from balances/coins, falling back to "Minima"/"Token". */
    private String tokenNameFor(String tokenid) {
        if (Util.isMinima(tokenid)) return "Minima";
        for (TokenBalance b : act.balances()) if (b.tokenid.equals(tokenid)) return b.name;
        for (Coin c : act.coins()) if (c.tokenid.equals(tokenid)) return c.tokenName;
        return "Token";
    }

    /** Post a custom transaction via {@link TxnBuilder} and reflect the outcome on the history row. */
    private void runBuilder(List<Coin> inputs, List<TxnBuilder.Out> outs, String tokenid,
                            String internalid, CharSequence okLabel, final Status status) {
        final String[] submittedCoins=TxnUtil.selectionSnapshot(inputs);
        status.show(Language.msg(l10n_utxo_posting), true);
        new TxnBuilder(act, inputs, outs, tokenid, new TxnBuilder.Done() {
            @Override public void onPosted(String txpowid, List<TxnBuilder.OutCoin> outputs) {
                // Don't store the txnsign-response txpowid (not the on-chain id) — the resolver fills
                // the real txpowid via the stored input coinids and marks it confirmed.
                act.history().update(internalid, HistoryDb.STATUS_POSTED, null, "");
                status.show(Language.msg(l10n_utxo_tool_sent, okLabel), true);
                if(java.util.Arrays.equals(submittedCoins,TxnUtil.selectionSnapshot(act.selectedCoins()))) act.clearSelection();
                act.reload();
            }
            @Override public void onFailed(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) message = "The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.";
                act.history().update(internalid, HistoryDb.STATUS_ERROR, null, message);
                status.show(Language.msg(l10n_utxo_failed, UtxoMessages.message(message)), false);
            }
            @Override public void onUnknown(String message) {
                act.history().update(internalid, HistoryDb.STATUS_UNKNOWN, null, message);
                status.show(UtxoMessages.message(message), false);
                act.reload();
            }
        }).record(internalid).run();
    }

    // ---- Split ----

    /** Prompt for a coin count, then split the selected coins into that many equal coins. */
    public void showSplit() {
        final Review review = new Review();
        final Status status = review.status;
        final List<Coin> sel = review.coins;
        if (sel.isEmpty()) { status.show(Language.msg(l10n_utxo_select_split), false); return; }
        final EditText count = input(Language.msg(l10n_utxo_coin_number), "4", false);
        Design.dialog(act)
                .setTitle(Language.msg(l10n_utxo_split_title))
                .setView(box(count))
                .setPositiveButton(Language.msg(l10n_utxo_split), (d, w) -> doSplit(review, count.getText().toString()))
                .setNegativeButton(Language.msg(l10n_utxo_cancel), null)
                .show();
    }

    /** Validate the count, compute equal slices, fetch one address per coin, then post the split. */
    private void doSplit(Review review, String cStr) {
        final Status status = review.status;
        if (!review.accept(true)) return;
        final List<Coin> sel = review.coins;
        int n;
        try { n = Integer.parseInt(cStr.trim()); } catch (Exception e) { status.show(Language.msg(l10n_utxo_valid_number), false); return; }
        if (n < 2 || n > 15) { status.show(Language.msg(l10n_utxo_coin_range), false); return; }

        final String tokenid = review.tokenid;
        int dec = act.tokenDecimals(tokenid);
        int scale = dec >= 0 ? dec : SPLIT_SCALE;
        BigDecimal total = sum(sel);
        BigDecimal each = total.divide(new BigDecimal(n), scale, RoundingMode.DOWN);
        if (each.signum() <= 0) { status.show(Language.msg(l10n_utxo_small_split, n), false); return; }
        // Rounding goes DOWN, so the last coin absorbs the remainder to keep inputs == outputs (no burn).
        final BigDecimal last = total.subtract(each.multiply(new BigDecimal(n - 1)));
        final BigDecimal eachF = each;
        final int fn = n;
        final String tokenName = sel.get(0).tokenName;

        status.show(Language.msg(l10n_utxo_fetch_addresses), true);
        TxnUtil.fetchAddresses(act, n, new TxnUtil.AddrList() {
            @Override public void onAddresses(List<String> addrs) {
                if (!review.current(true)) return;
                List<TxnBuilder.Out> outs = new ArrayList<>();
                for (int i = 0; i < fn; i++) {
                    BigDecimal amt = (i == fn - 1) ? last : eachF;
                    outs.add(new TxnBuilder.Out(addrs.get(i), amt.stripTrailingZeros().toPlainString()));
                }
                String internalid = TxnUtil.recordPosting(act, "Split → " + fn + " coins",
                        Util.tidyAmount(sum(sel).toPlainString()), tokenid, tokenName, sel, outs, null, null);
                runBuilder(sel, outs, tokenid, internalid, Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_utxo_split_done, fn), status);
            }
            @Override public void onError(String message) { status.show(Language.msg(l10n_utxo_failed, UtxoMessages.message(message)), false); }
        });
    }

    // ---- Consolidate ----
    // No selection: run the node's auto-consolidate on Minima (0x00).
    // 2+ coins selected: merge exactly those coins into one.

    /** Entry point: no selection ⇒ node auto-consolidate Minima; 2+ selected ⇒ merge exactly those. */
    public void showConsolidate() {
        final Review review = new Review();
        final Status status = review.status;
        final List<Coin> sel = review.coins;
        if (sel.isEmpty()) {
            Design.dialog(act)
                    .setTitle(Language.msg(l10n_utxo_consolidate_minima))
                    .setMessage(Language.msg(l10n_utxo_consolidate_help))
                    .setPositiveButton(Language.msg(l10n_utxo_consolidate), (d, w) -> { if(review.accept(false)) autoConsolidate(Util.MINIMA_TOKENID, status); })
                    .setNegativeButton(Language.msg(l10n_utxo_cancel), null)
                    .show();
            return;
        }
        if (sel.size() < 2) {
            status.show(Language.msg(l10n_utxo_select_merge), false);
            return;
        }
        final String tokenName = sel.get(0).tokenName;
        Design.dialog(act)
                .setTitle(Language.msg(l10n_utxo_consolidate_selected))
                .setMessage(Language.msg(l10n_utxo_merge_prompt, sel.size(), UtxoText.token(sel.get(0))))
                .setPositiveButton(Language.msg(l10n_utxo_merge), (d, w) -> mergeSelected(review))
                .setNegativeButton(Language.msg(l10n_utxo_cancel), null)
                .show();
    }

    /** Run the node's native "consolidate" command (it picks its own inputs). */
    private void autoConsolidate(String tokenid, final Status status) {
        if (!TxnUtil.canUseNativeFunds(act,tokenid,null)) { status.show(UtxoMessages.message(TransactionEvidence.RECORD_FAILED),false);return; }
        final String internalid = TxnUtil.recordPosting(act, "Consolidate " + tokenNameFor(tokenid),
                "—", tokenid, tokenNameFor(tokenid));
        if (internalid == null) { status.show(UtxoMessages.message(TransactionEvidence.SAVE_FAILED),false);return; }
        status.show(Language.msg(l10n_utxo_consolidating), true);
        new PreparedConsolidation(act,tokenid,internalid,new TxnBuilder.Done() {
            @Override public void onPosted(String txpowid,List<TxnBuilder.OutCoin> outputs) {
                act.history().update(internalid,HistoryDb.STATUS_POSTED,null,"");
                status.show(Language.msg(l10n_utxo_consolidate_submitted),true);act.reload();
            }
            @Override public void onFailed(String message) {
                if(NodeApi.ERR_NOT_ENABLED.equals(message))message="The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.";
                act.history().update(internalid,HistoryDb.STATUS_ERROR,null,message);
                status.show(Language.msg(l10n_utxo_failed,UtxoMessages.message(message)),false);
            }
            @Override public void onUnknown(String message) {
                act.history().update(internalid,HistoryDb.STATUS_UNKNOWN,null,message);
                status.show(UtxoMessages.message(message),false);act.reload();
            }
        }).run();
    }

    /** Merge exactly the selected coins into a single coin at a fresh address. */
    private void mergeSelected(Review review) {
        final Status status = review.status;
        if (!review.accept(true)) return;
        final List<Coin> sel = review.coins;
        final String tokenid = review.tokenid;
        final String tokenName = sel.get(0).tokenName;
        final BigDecimal total = sum(sel);
        status.show(Language.msg(l10n_utxo_fetching), true);
        act.node().cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!review.current(true)) return;
                JSONObject r = json.optJSONObject("response");
                String addr = r == null ? "" : r.optString("miniaddress", r.optString("address", ""));
                if (addr.isEmpty()) { status.show(Language.msg(l10n_utxo_no_address), false); return; }
                List<TxnBuilder.Out> outs = new ArrayList<>();
                outs.add(new TxnBuilder.Out(addr, total.stripTrailingZeros().toPlainString()));
                String internalid = TxnUtil.recordPosting(act, "Consolidate → 1 coin",
                        Util.tidyAmount(total.toPlainString()), tokenid, tokenName, sel, outs, null, null);
                runBuilder(sel, outs, tokenid, internalid, Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_utxo_merged_done, sel.size()), status);
            }
            @Override public void onError(String message) { status.show(Language.msg(l10n_utxo_failed, UtxoMessages.message(message)), false); }
        });
    }

    // ---- Distribute ----

    /** Prompt for address count + per-address amount, then kick off the multi-batch DistributeManager. */
    public void showDistribute() {
        final Review review = new Review();
        final Status status = review.status;
        final List<Coin> sel = review.coins;
        if (sel.isEmpty()) { status.show(Language.msg(l10n_utxo_select_distribute), false); return; }
        if (act.distribute() != null && act.distribute().isBusy()) {
            status.show(Language.msg(l10n_utxo_distribute_busy), false); return;
        }
        final EditText count = input(Language.msg(l10n_utxo_address_number), "20", false);
        final EditText amount = input(Language.msg(l10n_utxo_amount_each), "", true);
        Design.dialog(act)
                .setTitle(Language.msg(l10n_utxo_distribute_title))
                .setView(box(count, amount))
                .setPositiveButton(Language.msg(l10n_utxo_distribute), (d, w) ->
                        doDistribute(review, count.getText().toString(), amount.getText().toString()))
                .setNegativeButton(Language.msg(l10n_utxo_cancel), null)
                .show();
    }

    /** Validate count/amount/decimals/funds, reserve the job slot, fetch addresses, then start. */
    private void doDistribute(Review review, String cStr, String aStr) {
        final Status status = review.status;
        if (!review.accept(true)) return;
        final List<Coin> sel = review.coins;
        int n;
        try { n = Integer.parseInt(cStr.trim()); } catch (Exception e) { status.show(Language.msg(l10n_utxo_valid_number), false); return; }
        if (n < 2 || n > 56) { status.show(Language.msg(l10n_utxo_address_range), false); return; }

        final BigDecimal per;
        try { per = new BigDecimal(aStr.trim()); } catch (Exception e) { status.show(Language.msg(l10n_utxo_valid_amount), false); return; }
        if (per.signum() <= 0) { status.show(Language.msg(l10n_utxo_positive_amount), false); return; }

        final String tokenid = review.tokenid;
        int dec = act.tokenDecimals(tokenid);
        if (dec >= 0 && Util.decimalPlaces(per) > dec) {
            status.show(Language.msg(l10n_utxo_decimals, dec), false);
            return;
        }

        BigDecimal total = sum(sel);
        BigDecimal required = per.multiply(new BigDecimal(n));
        if (required.compareTo(total) > 0) {
            status.show(Language.msg(l10n_utxo_need_selected, Util.tidyAmount(required.toPlainString()), Util.tidyAmount(total.toPlainString())), false);
            return;
        }
        final String perStr = per.stripTrailingZeros().toPlainString();
        final String tokenName = sel.get(0).tokenName;

        // Reserve before the async address fetch so a second tap can't start a parallel job.
        if (!act.distribute().reserve()) { status.show(Language.msg(l10n_utxo_distribute_busy), false); return; }
        status.show(Language.msg(l10n_utxo_fetch_count, n), true);
        TxnUtil.fetchAddresses(act, n, new TxnUtil.AddrList() {
            @Override public void onAddresses(List<String> addrs) {
                if (!review.current(true)) { act.distribute().cancelReserve(); return; }
                act.distribute().start(sel, addrs, perStr, tokenid, tokenName);
                status.show(Language.msg(l10n_utxo_distribute_started), true);
            }
            @Override public void onError(String message) {
                act.distribute().cancelReserve();
                status.show(Language.msg(l10n_utxo_failed, UtxoMessages.message(message)), false);
            }
        });
    }

    // ---- Untrack ----

    /** Confirm, then stop tracking the selected coins (they stay on-chain, just hidden locally). */
    public void untrackSelected() {
        final Review review = new Review();
        final Status status = review.status;
        final List<Coin> sel = review.coins;
        if (sel.isEmpty()) { status.show(Language.msg(l10n_utxo_select_untrack), false); return; }
        Design.dialog(act)
                .setTitle(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_utxo_untrack_title, sel.size()))
                .setMessage(Language.msg(l10n_utxo_untrack_help))
                .setPositiveButton(Language.msg(l10n_utxo_untrack), (d, w) -> { if(review.accept(false)) untrackNext(sel, 0, status); })
                .setNegativeButton(Language.msg(l10n_utxo_cancel), null)
                .show();
    }

    // Untrack one coin at a time, recursing on success — the node has no batch cointrack call.
    /** Sequentially `cointrack enable:false` each selected coin, recursing through the list. */
    private void untrackNext(final List<Coin> sel, final int i, final Status status) {
        if (i >= sel.size()) {
            status.show(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_utxo_untracked, sel.size()), true);
            if(java.util.Arrays.equals(TxnUtil.selectionSnapshot(sel),TxnUtil.selectionSnapshot(act.selectedCoins()))) act.clearSelection();
            act.reload();
            return;
        }
        status.show(Language.msg(l10n_utxo_untracking, i + 1, sel.size()), true);
        // Belt-and-braces: coins are already vetted at ingestion (Coin.isWellFormed), but this id
        // goes straight into a command, so never take that on trust.
        if (!Util.isValidHexId(sel.get(i).coinid)) { untrackNext(sel, i + 1, status); return; }
        act.node().cmd("cointrack enable:false coinid:" + sel.get(i).coinid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { untrackNext(sel, i + 1, status); }
            @Override public void onError(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) message = "The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.";
                status.show(Language.msg(l10n_utxo_untrack_failed, UtxoMessages.message(message)), false);
            }
        });
    }
}
