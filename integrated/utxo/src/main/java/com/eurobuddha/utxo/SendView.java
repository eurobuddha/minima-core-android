package com.eurobuddha.utxo;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Send tab — full UTXO transaction construction, mirroring the dapp's Send + Confirm flow:
 * FROM (the selected input coins), recipient, amount (+ max), an editable change address (+ next,
 * pre-filled by rotating the node's default addresses), and an optional burn (Minima only). Preview
 * opens a Confirm breakdown (Inputs / Outputs / Change / Burn) before signing & posting.
 */
public class SendView extends BaseView {

    private final LinearLayout fromList;
    private final EditText addrInput, amountInput, changeInput, burnInput;
    private final TextView addrNote, amountNote, changeNote, burnNote, statusView;
    private final Button previewBtn;

    private boolean changeTouched = false;     // user edited the change field
    private boolean settingChange = false;     // guard for programmatic change-field writes
    private long changeGeneration, reviewGeneration;
    private boolean sending, reviewPending;
    private boolean changeFetching = false;

    /** Binds the form fields, wires Max/Next/Preview, and tracks manual edits to the change field. */
    public SendView(MainActivity a) {
        super(a, R.layout.pm_utxo_view_send);
        fromList = find(R.id.pm_utxo_sendFromList);
        addrInput = find(R.id.pm_utxo_sendAddress);
        amountInput = find(R.id.pm_utxo_sendAmount);
        changeInput = find(R.id.pm_utxo_sendChange);
        burnInput = find(R.id.pm_utxo_sendBurn);
        addrNote = find(R.id.pm_utxo_sendAddrNote);
        amountNote = find(R.id.pm_utxo_sendAmountNote);
        changeNote = find(R.id.pm_utxo_sendChangeNote);
        burnNote = find(R.id.pm_utxo_sendBurnNote);
        statusView = find(R.id.pm_utxo_sendStatus);
        previewBtn = find(R.id.pm_utxo_previewBtn);

        ((TextView) find(R.id.pm_utxo_maxBtn)).setOnClickListener(v -> onMax());
        ((TextView) find(R.id.pm_utxo_nextBtn)).setOnClickListener(v -> fetchChange(true));
        previewBtn.setOnClickListener(v -> onPreview());

        // Mark the change field "touched" once the user types, so we stop auto-prefilling it.
        changeInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { if (!settingChange) { changeTouched = true; changeGeneration++; changeFetching = false; } }
        });
        applyDesign();
        refresh();
    }

    /** Paints the view and all input fields with the active theme. */
    private void applyDesign() {
        root.setBackgroundColor(Design.bg());
        themeLabels(root);
        fromList.setBackgroundColor(Design.surface());
        for (EditText e : new EditText[]{addrInput, amountInput, changeInput, burnInput}) {
            e.setBackgroundColor(Design.surface2());
            e.setTextColor(Design.text());
            e.setHintTextColor(Design.dim());
        }
        previewBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
        previewBtn.setTextColor(Design.onAccent());
        ((TextView)find(R.id.pm_utxo_maxBtn)).setTextColor(Design.accentText());
        ((TextView)find(R.id.pm_utxo_nextBtn)).setTextColor(Design.accentText());
    }

    private void themeLabels(View view) {
        if (view instanceof TextView && !(view instanceof EditText) && !(view instanceof Button))
            ((TextView)view).setTextColor(Design.dim());
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)themeLabels(group.getChildAt(i));
        }
    }

    /** Rebuilds the FROM (selected-input) list and total, gates burn to Minima, and prefills change. */
    @Override
    public void refresh() {
        List<Coin> sel = act.selectedCoins();
        fromList.removeAllViews();

        if (sel.isEmpty()) {
            TextView t = new TextView(act);
            Language.setText(t, Language.msg(l10n_utxo_choose_coins));
            t.setTextColor(Design.dim());
            t.setTextSize(13f);
            fromList.addView(t);
            previewBtn.setEnabled(false);
            return;
        }
        previewBtn.setEnabled(!sending && !reviewPending);

        String tokenName = sel.get(0).tokenName;
        boolean minima = Util.isMinima(act.selectedTokenid());

        // FROM list: one row per selected input coin, accumulating the spendable total as we go.
        BigDecimal total = BigDecimal.ZERO;
        for (Coin c : sel) {
            try { total = total.add(new BigDecimal(c.amount)); } catch (Exception ignored) {}
            fromList.addView(idRow(Language.msg(l10n_utxo_coin), c.coinid, "", Util.tidyAmount(c.amount)));
        }
        View totalRow = row(Language.msg(l10n_utxo_total_in), Language.join(Util.tidyAmount(total.toPlainString()), " ", UtxoText.token(sel.get(0))), true);
        fromList.addView(totalRow);

        // Burn is paid in Minima, so it's only offered when sending the Minima token.
        burnInput.setEnabled(minima);
        if (!minima) {
            Language.setText(burnInput, "");
            setNote(burnNote, Language.msg(l10n_utxo_burn_only), R.color.pm_utxo_ux_subtext);
        } else {
            burnNote.setVisibility(View.GONE);
        }

        prefillChangeIfNeeded();
    }

    /** Snapshot the typed fields (recipient, amount, change, burn) — used to survive a theme recreate(). */
    public String[] fieldValues() {
        return new String[]{ addrInput.getText().toString(), amountInput.getText().toString(),
                changeInput.getText().toString(), burnInput.getText().toString() };
    }

    /** Restore a fieldValues() snapshot after recreate(). */
    public void setFieldValues(String[] v) {
        if (v == null || v.length < 4) return;
        Language.setText(addrInput, v[0]); Language.setText(amountInput, v[1]);
        Language.setText(changeInput, v[2]); Language.setText(burnInput, v[3]);
    }

    // ----- field helpers -----

    /** Fills the amount with the full spendable total; for Minima sends the burn is reserved first. */
    private void onMax() {
        List<Coin> sel = act.selectedCoins();
        if (sel.isEmpty()) return;
        BigDecimal total = sum(sel);
        BigDecimal burn = parseBurn();
        // Minima: max = total − burn (burn is spent from the same coins); other tokens: whole total.
        BigDecimal max = Util.isMinima(act.selectedTokenid()) ? total.subtract(burn) : total;
        if (max.signum() < 0) max = BigDecimal.ZERO;
        Language.setText(amountInput, Util.tidyAmount(max.toPlainString()));
    }

    /** Auto-fills the change address once, unless the user has edited it or a fetch is in flight. */
    private void prefillChangeIfNeeded() {
        if (changeTouched || changeFetching) return;
        if (!changeInput.getText().toString().trim().isEmpty()) return;
        fetchChange(false);
    }

    /**
     * Rotate: fetch the node's next default address into the change field.
     * userInitiated true = the NEXT button (a deliberate rotation, so it counts as "touched");
     * false = the silent prefill (stays a default, so prefill can still re-run later).
     */
    private void fetchChange(boolean userInitiated) {
        if (act.selectedCoins().isEmpty()) return;
        final long generation = ++changeGeneration;
        if (userInitiated) changeTouched = true;
        changeFetching = true;
        act.node().cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (generation != changeGeneration) return;
                changeFetching = false;
                JSONObject r = json.optJSONObject("response");
                String addr = r == null ? "" : r.optString("miniaddress", r.optString("address", ""));
                if (!addr.isEmpty()) {
                    settingChange = true;
                    Language.setText(changeInput, addr);
                    settingChange = false;
                    if (!userInitiated) changeTouched = false;   // still a default
                }
            }
            @Override public void onError(String message) { if (generation == changeGeneration) changeFetching = false; }
        });
    }

    private String[] selectionSnapshot() { return TxnUtil.selectionSnapshot(act.selectedCoins()); }
    private boolean matchesDraft(String[] fields, String[] coins) {
        return java.util.Arrays.equals(fields, fieldValues())
                && (coins == null || java.util.Arrays.equals(coins, selectionSnapshot()));
    }
    private boolean acceptReview(long generation, String[] fields, String[] coins) {
        if (generation != reviewGeneration || sending || act.isFinishing() || act.isDestroyed()) return false;
        reviewPending = false;
        previewBtn.setEnabled(true);
        if (!matchesDraft(fields, coins)) {
            status(Language.msg(l10n_nft_send_review_again), false);
            return false;
        }
        return true;
    }

    private List<Coin> freezeSelection() {
        List<Coin> selected=act.selectedCoins(), coins=new ArrayList<>();
            for (Coin source : selected) {
                if (source == null) { coins.add(null); continue; }
                Coin c = new Coin();
                c.coinid=source.coinid;c.tokenid=source.tokenid;c.amount=source.amount;c.rawAmount=source.rawAmount;
                c.address=source.address;c.miniaddress=source.miniaddress;
                c.confirmed=source.confirmed;c.sendable=source.sendable;c.spent=source.spent;
                c.tokenName=source.tokenName;c.tokenNameProvided=source.tokenNameProvided;
                coins.add(c);
            }
        return coins;
    }

    // ----- preview / validate -----

    /** Validates every field, computes change, then asks the node to confirm the recipient before Confirm. */
    private void onPreview() {
        if (sending || act.isFinishing() || act.isDestroyed()) return;
        final List<Coin> sel = freezeSelection();
        if (sel.isEmpty()) { status(Language.msg(l10n_utxo_select_first), false); return; }

        final String recipient = addrInput.getText().toString().trim();
        if (recipient.isEmpty()) { setNote(addrNote, Language.msg(l10n_utxo_recipient_required), R.color.pm_utxo_ux_error); return; }
        if (!Util.isValidAddress(recipient)) { setNote(addrNote, Language.msg(l10n_utxo_invalid_address), R.color.pm_utxo_ux_error); return; }
        addrNote.setVisibility(View.GONE);

        final String tokenid = act.selectedTokenid();
        final boolean minima = Util.isMinima(tokenid);
        final String tokenName = sel.get(0).tokenName;
        final BigDecimal total = sum(sel);
        final BigDecimal burn = minima ? parseBurn() : BigDecimal.ZERO;
        if (burn.signum() < 0) { setNote(burnNote, Language.msg(l10n_utxo_invalid_burn), R.color.pm_utxo_ux_error); return; }
        burnNote.setVisibility(minima ? View.GONE : burnNote.getVisibility());

        BigDecimal maxSpendable = minima ? total.subtract(burn) : total;
        if (maxSpendable.signum() <= 0) { setNote(amountNote, Language.msg(l10n_utxo_burn_exceeds), R.color.pm_utxo_ux_error); return; }

        final BigDecimal amount;
        try { amount = new BigDecimal(amountInput.getText().toString().trim()); }
        catch (Exception e) { setNote(amountNote, Language.msg(l10n_utxo_valid_amount), R.color.pm_utxo_ux_error); return; }
        if (amount.signum() <= 0) { setNote(amountNote, Language.msg(l10n_utxo_positive_amount), R.color.pm_utxo_ux_error); return; }
        if (amount.compareTo(maxSpendable) > 0) { setNote(amountNote, Language.msg(l10n_utxo_max_amount, Util.tidyAmount(maxSpendable.toPlainString())), R.color.pm_utxo_ux_error); return; }
        int dec = act.tokenDecimals(tokenid);
        if (dec >= 0 && Util.decimalPlaces(amount) > dec) { setNote(amountNote, Language.msg(l10n_utxo_decimals, dec), R.color.pm_utxo_ux_error); return; }
        amountNote.setVisibility(View.GONE);

        // Change = inputs − amount − burn; only require/validate a change address when there's any left.
        final BigDecimal change = total.subtract(amount).subtract(burn);
        final boolean hasChange = change.signum() > 0;
        final String changeStr = hasChange ? change.stripTrailingZeros().toPlainString() : null;
        final String changeAddr = changeInput.getText().toString().trim();
        if (hasChange) {
            if (changeAddr.isEmpty()) { setNote(changeNote, Language.msg(l10n_utxo_change_required), R.color.pm_utxo_ux_error); return; }
            if (!Util.isValidAddress(changeAddr)) { setNote(changeNote, Language.msg(l10n_utxo_invalid_change), R.color.pm_utxo_ux_error); return; }
        }
        changeNote.setVisibility(View.GONE);

        final String burnStr = burn.signum() > 0 ? burn.stripTrailingZeros().toPlainString() : null;

        // Confirm the recipient with the node before showing the breakdown.
        final long generation = ++reviewGeneration;
        final String[] reviewedFields = fieldValues();
        final String[] reviewedCoins = selectionSnapshot();
        reviewPending = true;
        previewBtn.setEnabled(false);
        status(Language.msg(l10n_utxo_checking), true);
        act.node().cmd("checkaddress address:" + recipient, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!acceptReview(generation, reviewedFields, reviewedCoins)) return;
                statusView.setVisibility(View.GONE);
                showConfirm(sel, recipient, amount.stripTrailingZeros().toPlainString(),
                        hasChange ? changeAddr : null, changeStr, burnStr, tokenid, tokenName, total, generation, reviewedFields, reviewedCoins);
            }
            @Override public void onError(String message) {
                if (!acceptReview(generation, reviewedFields, reviewedCoins)) return;
                // NodeApi turns the node's status:false (invalid address) into onError with its message.
                setNote(addrNote, NodeApi.ERR_NOT_ENABLED.equals(message)
                        ? Language.msg(l10n_explorer_node_retry)
                        : Language.msg(l10n_utxo_check_failed, UtxoMessages.message(message)), R.color.pm_utxo_ux_error);
                statusView.setVisibility(View.GONE);
            }
        });
    }

    // ----- confirm breakdown -----

    /** The Confirm step: a read-only Inputs / Outputs / Change / Burn breakdown before signing. */
    private void showConfirm(final List<Coin> sel, final String recipient, final String amountStr,
                             final String changeAddr, final String changeStr, final String burnStr,
                             final String tokenid, final String tokenName, BigDecimal total, final long generation,
                             final String[] reviewedFields, final String[] reviewedCoins) {
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Design.bg());   // match the current theme (dialog frame is fixed; content must adapt)
        int pad = dp(18);
        body.setPadding(pad, dp(8), pad, 0);

        body.addView(sectionLabel(Language.msg(l10n_utxo_inputs)));
        for (Coin c : sel) body.addView(idRow(Language.msg(l10n_utxo_coin), c.coinid, "", Util.tidyAmount(c.amount)));
        body.addView(row(Language.msg(l10n_utxo_total_in), Language.join(Util.tidyAmount(total.toPlainString()), " ", UtxoText.token(sel.get(0))), true));

        body.addView(sectionLabel(Language.msg(l10n_utxo_outputs)));
        body.addView(idRow(Language.msg(l10n_utxo_to), recipient, isMine(recipient) ? "(yours)" : "",
                Language.join(Util.tidyAmount(amountStr), " ", UtxoText.token(sel.get(0)))));
        if (changeStr != null) {
            // Flag a change address we don't recognise as ours with "(verify)" — guards against typos
            // in a hand-edited change field sending the remainder to a stranger.
            body.addView(idRow(Language.msg(l10n_utxo_change), changeAddr, isMine(changeAddr) ? "(yours)" : "(verify)",
                    Language.join(Util.tidyAmount(changeStr), " ", UtxoText.token(sel.get(0)))));
        }
        if (burnStr != null) {
            body.addView(row(Language.msg(l10n_utxo_burn), Util.tidyAmount(burnStr) + " MINIMA", false));
        }

        TextView warn = new TextView(act);
        Language.setText(warn, Language.msg(l10n_utxo_warning));
        warn.setTextColor(Design.red());
        warn.setTextSize(12f);
        warn.setPadding(0, dp(14), 0, 0);
        body.addView(warn);

        ScrollView sv = new ScrollView(act);
        sv.addView(body);

        Design.dialog(act)
                .setTitle(Language.msg(l10n_utxo_confirm))
                .setView(sv)
                .setNegativeButton(Language.msg(l10n_utxo_back), null)
                .setPositiveButton(Language.msg(l10n_utxo_sign_post), (d, w) ->
                        { if (acceptReview(generation, reviewedFields, reviewedCoins)) buildSend(sel, recipient, amountStr, changeAddr, changeStr, burnStr, tokenid, tokenName); })
                .show();
    }

    /** Records a History row, builds/signs/posts the txn, then updates that row by outcome. */
    private void buildSend(List<Coin> sel, final String recipient, final String amountStr,
                           String changeAddr, String changeStr, String burnStr,
                           final String tokenid, final String tokenName) {
        reviewGeneration++; reviewPending = false;
        final String[] submittedFields = fieldValues();
        final String[] submittedCoins = selectionSnapshot();
        sending = true;
        previewBtn.setEnabled(false);
        status(Language.msg(l10n_utxo_recording), true);

        // Outputs = recipient, plus the change output when there's any change to return.
        List<TxnBuilder.Out> outs = new ArrayList<>();
        outs.add(new TxnBuilder.Out(recipient, amountStr));
        if (changeStr != null && changeAddr != null && !changeAddr.isEmpty()) {
            outs.add(new TxnBuilder.Out(changeAddr, changeStr));
        }
        // Persist the inputs/outputs to History up-front (status PENDING) so the send is auditable
        // even if signing/posting fails; internalid links this row to the outcome callbacks below.
        final String internalid = TxnUtil.recordPosting(act, recipient, amountStr, tokenid, tokenName,
                sel, outs, (changeStr != null ? changeAddr : null), burnStr);

        new TxnBuilder(act, sel, outs, tokenid, burnStr, new TxnBuilder.Done() {
            @Override public void onPosted(String txpowid, List<TxnBuilder.OutCoin> outputs) {
                // Do NOT store the txnsign-response txpowid — it is not the on-chain id. The resolver
                // fills the real txpowid (and marks confirmed) once the transaction is mined.
                act.history().update(internalid, HistoryDb.STATUS_POSTED, null, "");
                status(Language.msg(l10n_utxo_posted, Util.tidyAmount(amountStr), UtxoText.token(sel.get(0))), true);
                if (matchesDraft(submittedFields, submittedCoins)) {
                    Language.setText(addrInput, "");
                    Language.setText(amountInput, "");
                    Language.setText(burnInput, "");
                    changeGeneration++; changeFetching = false;
                    settingChange = true; Language.setText(changeInput, ""); settingChange = false;
                    changeTouched = false;
                    act.clearSelection();
                }
                sending = false;
                previewBtn.setEnabled(true);
                act.reload();
            }
            @Override public void onFailed(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) message = "The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.";
                act.history().update(internalid, HistoryDb.STATUS_ERROR, null, message);
                status(Language.msg(l10n_utxo_failed, UtxoMessages.message(message)), false);
                sending = false;
                previewBtn.setEnabled(true);
            }
            @Override public void onUnknown(String message) {
                // Not a failure: the node may still post. Keep the typed fields so a settled "not posted"
                // can be retried; the row sits at the top of History as "posted?" until the coins decide.
                act.history().update(internalid, HistoryDb.STATUS_UNKNOWN, null, "node reply timed out — awaiting settlement");
                status(Language.msg(l10n_utxo_unknown), false);
                sending = false;
                previewBtn.setEnabled(true);
                act.reload();
            }
        }).record(internalid).onProgress(label -> status(UtxoMessages.message(label), true)).run();
    }

    // ----- small helpers -----

    /** Builds a monospaced left-label / right-value row; bold marks a total/heading. */
    private View row(CharSequence left, CharSequence right, boolean bold) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(4), 0, dp(4));

        TextView l = new TextView(act);
        Language.setText(l, left);
        l.setTextColor(bold ? Design.text() : Design.dim());
        l.setTextSize(12f);
        l.setTypeface(android.graphics.Typeface.MONOSPACE, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(l);

        TextView rt = new TextView(act);
        Language.setText(rt, right);
        rt.setTextColor(Design.text());
        rt.setTextSize(12f);
        rt.setTypeface(android.graphics.Typeface.MONOSPACE, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        rt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        rt.setGravity(Gravity.END);
        r.addView(rt);
        return r;
    }

    /**
     * A row for an identifier (coinid / address): a header line with the label, an optional tag
     * ("(yours)" / "(verify)") and the amount, then the FULL value on its own wrapping line. Never
     * shortened — this is the screen where the user is asked to verify the address, so every
     * character must be visible. Tapping the value copies the complete string.
     */
    private View idRow(CharSequence label, String value, String tag, CharSequence amount) {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(4), 0, dp(4));

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = new TextView(act);
        Language.setText(l, tag.isEmpty() ? label : Language.join(label, "  ", "(verify)".equals(tag) ? Language.msg(l10n_utxo_verify) : Language.msg(l10n_utxo_yours)));
        l.setTextColor("(verify)".equals(tag) ? Design.red() : Design.dim());
        l.setTextSize(12f);
        l.setTypeface(android.graphics.Typeface.MONOSPACE);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(l);
        TextView rt = new TextView(act);
        Language.setText(rt, amount);
        rt.setTextColor(Design.text());
        rt.setTextSize(12f);
        rt.setTypeface(android.graphics.Typeface.MONOSPACE);
        rt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        rt.setGravity(Gravity.END);
        head.addView(rt);
        col.addView(head);

        TextView v = new TextView(act);
        Language.setText(v, value);
        v.setTextColor(Design.text());
        v.setTextSize(11f);
        v.setTypeface(android.graphics.Typeface.MONOSPACE);
        v.setPadding(dp(12), dp(1), 0, 0);
        v.setOnClickListener(x -> {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(label, value));
            Toast.makeText(act, Language.msg(l10n_utxo_copied), Toast.LENGTH_SHORT).show();
        });
        col.addView(v);
        return col;
    }

    /** Builds a small dim, letter-spaced section heading (INPUTS / OUTPUTS) for the Confirm dialog. */
    private TextView sectionLabel(CharSequence text) {
        TextView t = new TextView(act);
        Language.setText(t, text);
        t.setTextColor(Design.dim());
        t.setTextSize(11f);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    /** True if the address (hex or miniaddress form) belongs to this wallet. */
    private boolean isMine(String addr) {
        for (String[] a : act.myAddresses()) if (addr.equals(a[0]) || addr.equals(a[1])) return true;
        return false;
    }

    /** Sums coin amounts, skipping any that fail to parse. */
    private BigDecimal sum(List<Coin> coins) {
        BigDecimal t = BigDecimal.ZERO;
        for (Coin c : coins) { try { t = t.add(new BigDecimal(c.amount)); } catch (Exception ignored) {} }
        return t;
    }

    /** Parses the burn field: empty = 0; unparseable = -1 (a sentinel callers reject as invalid). */
    private BigDecimal parseBurn() {
        String s = burnInput.getText().toString().trim();
        if (s.isEmpty()) return BigDecimal.ZERO;
        try { return new BigDecimal(s); } catch (Exception e) { return new BigDecimal("-1"); }
    }

    /** Shows an inline validation note under a field in the given color. */
    private void setNote(TextView note, CharSequence msg, int colorRes) {
        note.setVisibility(View.VISIBLE);
        Language.setText(note, msg);
        note.setTextColor(colorRes == R.color.pm_utxo_ux_error ? Design.red() : Design.dim());
    }

    /** Shows the bottom status line; green on ok, red on failure. */
    private void status(CharSequence msg, boolean ok) {
        statusView.setVisibility(View.VISIBLE);
        Language.setText(statusView, msg);
        statusView.setTextColor(ok ? Design.success() : Design.red());
    }

    /** Converts density-independent pixels to raw pixels for this device. */
    private int dp(int v) { return (int) (v * act.getResources().getDisplayMetrics().density); }
}
