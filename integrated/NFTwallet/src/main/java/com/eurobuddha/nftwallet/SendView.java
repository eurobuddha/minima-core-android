package com.eurobuddha.nftwallet;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Send tab — two modes behind one form, like the old Wallet's send suite:
 *
 *  QUICK SEND    — pick a token, recipient, amount (+ max), optional burn and split:N; the node
 *                  chooses the input coins ({@code send} command). This is the 2.47.2-era flow.
 *  COIN CONTROL  — full UTXO transaction construction from the coins selected on the Coins tab:
 *                  FROM list, editable change address (+ next), burn; manual txn build/sign/post.
 *
 * Both modes share recipient/amount/burn fields, validation notes, the QR scan button, and the
 * Confirm-breakdown dialog before anything is signed or posted.
 */
public class SendView extends BaseView {

    private final LinearLayout fromList;
    private final EditText addrInput, amountInput, changeInput, burnInput, splitInput;
    private final TextView addrNote, amountNote, changeNote, burnNote, splitNote, statusView;
    private final Button previewBtn;
    private final TextView modeQuick, modeCoins, tokenLabel, tokenBal;
    private final View tokenBlock, fromBlock, splitBlock, changeBlock;

    /** true = QUICK SEND (token-level, node picks coins); false = COIN CONTROL (manual txn). */
    private boolean quickMode = true;
    /** Quick-mode selected token (tokenid); defaults to Minima. */
    private String quickTokenid = Util.MINIMA_TOKENID;

    /** True while a send/build-sign-post is in flight — refresh() must not re-enable the button. */
    private boolean sending = false;

    private boolean changeTouched = false;     // user edited the change field
    private boolean settingChange = false;     // guard for programmatic change-field writes
    private long changeGeneration, reviewGeneration;
    private boolean reviewPending;
    private boolean changeFetching = false;

    /** Binds the form fields, wires Max/Next/Preview/Scan and the mode segment. */
    public SendView(MainActivity a) {
        super(a, R.layout.pm_nftwallet_view_send);
        fromList = find(R.id.pm_nftwallet_sendFromList);
        addrInput = find(R.id.pm_nftwallet_sendAddress);
        amountInput = find(R.id.pm_nftwallet_sendAmount);
        changeInput = find(R.id.pm_nftwallet_sendChange);
        burnInput = find(R.id.pm_nftwallet_sendBurn);
        splitInput = find(R.id.pm_nftwallet_sendSplit);
        addrNote = find(R.id.pm_nftwallet_sendAddrNote);
        amountNote = find(R.id.pm_nftwallet_sendAmountNote);
        changeNote = find(R.id.pm_nftwallet_sendChangeNote);
        burnNote = find(R.id.pm_nftwallet_sendBurnNote);
        splitNote = find(R.id.pm_nftwallet_sendSplitNote);
        statusView = find(R.id.pm_nftwallet_sendStatus);
        statusView.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                view.post(() -> {
                    if (view.isAttachedToWindow() && act.currentTab() == MainActivity.TAB_SEND
                            && view.getVisibility() == View.VISIBLE)
                        view.requestRectangleOnScreen(new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight()), true);
                }));
        previewBtn = find(R.id.pm_nftwallet_previewBtn);
        modeQuick = find(R.id.pm_nftwallet_modeQuickBtn);
        modeCoins = find(R.id.pm_nftwallet_modeCoinsBtn);
        tokenLabel = find(R.id.pm_nftwallet_sendTokenLabel);
        tokenBal = find(R.id.pm_nftwallet_sendTokenBal);
        tokenBlock = find(R.id.pm_nftwallet_sendTokenBlock);
        fromBlock = find(R.id.pm_nftwallet_sendFromBlock);
        splitBlock = find(R.id.pm_nftwallet_sendSplitBlock);
        changeBlock = find(R.id.pm_nftwallet_sendChangeBlock);

        ((TextView) find(R.id.pm_nftwallet_maxBtn)).setOnClickListener(v -> onMax());
        ((TextView) find(R.id.pm_nftwallet_nextBtn)).setOnClickListener(v -> fetchChange(true));
        ((TextView) find(R.id.pm_nftwallet_scanBtn)).setOnClickListener(v -> act.scanQr(text -> {
            if (text != null && !text.isEmpty()) Language.setText(addrInput, text.trim());
        }));
        previewBtn.setOnClickListener(v -> onPreview());
        modeQuick.setOnClickListener(v -> setMode(true));
        modeCoins.setOnClickListener(v -> setMode(false));
        find(R.id.pm_nftwallet_sendTokenRow).setOnClickListener(v -> showTokenPicker());

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
        for (EditText e : new EditText[]{addrInput, amountInput, changeInput, burnInput, splitInput}) {
            e.setBackgroundColor(Design.surface2());
            e.setTextColor(Design.text());
            e.setHintTextColor(Design.dim());
        }
        find(R.id.pm_nftwallet_sendTokenRow).setBackgroundColor(Design.surface2());
        tokenLabel.setTextColor(Design.text());
        tokenBal.setTextColor(Design.dim());
        previewBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Design.accent()));
        previewBtn.setTextColor(Design.onAccent());
        for (int id : new int[]{R.id.pm_nftwallet_scanBtn, R.id.pm_nftwallet_maxBtn, R.id.pm_nftwallet_nextBtn})
            ((TextView)find(id)).setTextColor(Design.accentText());
        paintModeSegment();
    }

    private void paintModeSegment() {
        modeQuick.setTextColor(quickMode ? Design.onAccent() : Design.dim());
        modeQuick.setBackgroundColor(quickMode ? Design.accent() : Design.surface2());
        modeCoins.setTextColor(!quickMode ? Design.onAccent() : Design.dim());
        modeCoins.setBackgroundColor(!quickMode ? Design.accent() : Design.surface2());
    }

    private void setMode(boolean quick) {
        quickMode = quick;
        paintModeSegment();
        refresh();
    }

    /** Balances detail "Send" jumps here with the token preselected in quick mode. */
    public void preselectToken(String tokenid) {
        quickTokenid = (tokenid == null || tokenid.isEmpty()) ? Util.MINIMA_TOKENID : tokenid;
        setMode(true);
    }

    private void themeLabels(View view) {
        if (view instanceof TextView && !(view instanceof EditText) && !(view instanceof Button))
            ((TextView)view).setTextColor(Design.dim());
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)themeLabels(group.getChildAt(i));
        }
    }

    /** Rebuilds the mode-dependent blocks; gates burn; prefills change (expert mode). */
    @Override
    public void refresh() {
        tokenBlock.setVisibility(quickMode ? View.VISIBLE : View.GONE);
        splitBlock.setVisibility(quickMode ? View.VISIBLE : View.GONE);
        fromBlock.setVisibility(quickMode ? View.GONE : View.VISIBLE);
        changeBlock.setVisibility(quickMode ? View.GONE : View.VISIBLE);

        if (quickMode) {
            TokenBalance b = quickToken();
            if (b == null && !Util.isMinima(quickTokenid)) {
                // token vanished from balances (e.g. fully sent) — fall back to Minima
                quickTokenid = Util.MINIMA_TOKENID;
                b = quickToken();
            }
            CharSequence name = b == null ? "Minima" : displayName(b);
            Language.setText(tokenLabel, name);
            Language.setText(tokenBal, b == null ? "" : Format.amount(b.sendable));
            boolean minima = Util.isMinima(quickTokenid);
            burnInput.setEnabled(true);   // burn rides alongside any send in quick mode
            burnNote.setVisibility(View.GONE);
            previewBtn.setEnabled(!sending && !reviewPending);
            if (!minima && b != null && b.isNft()) {
                setNote(splitNote, Language.msg(l10n_nft_send_indivisible), R.color.pm_nftwallet_ux_subtext);
            } else {
                splitNote.setVisibility(View.GONE);
            }
            return;
        }

        List<Coin> sel = act.selectedCoins();
        fromList.removeAllViews();

        if (sel.isEmpty()) {
            TextView t = new TextView(act);
            Language.setText(t, Language.msg(l10n_nft_send_choose_coins));
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
            fromList.addView(row("• " + Util.shorten(c.coinid), Util.tidyAmount(c.amount), false));
        }
        View totalRow = row(Language.msg(l10n_utxo_total_in), Language.join(Util.tidyAmount(total.toPlainString()), " ", NftText.token(sel.get(0))), true);
        fromList.addView(totalRow);

        // In expert mode burn is spent from the selected Minima coins, so it needs a Minima selection.
        burnInput.setEnabled(minima);
        if (!minima) {
            Language.setText(burnInput, "");
            setNote(burnNote, Language.msg(l10n_utxo_burn_only), R.color.pm_nftwallet_ux_subtext);
        } else {
            burnNote.setVisibility(View.GONE);
        }

        prefillChangeIfNeeded();
    }

    /** Snapshot the typed fields — used to survive a theme recreate(). */
    public String[] fieldValues() {
        return new String[]{ addrInput.getText().toString(), amountInput.getText().toString(),
                changeInput.getText().toString(), burnInput.getText().toString(),
                splitInput.getText().toString(), quickMode ? "q" : "c", quickTokenid };
    }

    /** Restore a fieldValues() snapshot after recreate(). */
    public void setFieldValues(String[] v) {
        if (v == null || v.length < 4) return;
        Language.setText(addrInput, v[0]); Language.setText(amountInput, v[1]);
        Language.setText(changeInput, v[2]); Language.setText(burnInput, v[3]);
        if (v.length >= 7) {
            Language.setText(splitInput, v[4]);
            quickMode = !"c".equals(v[5]);
            quickTokenid = v[6] == null || v[6].isEmpty() ? Util.MINIMA_TOKENID : v[6];
            paintModeSegment();
            refresh();   // re-apply mode block visibilities after a theme recreate()
        }
    }

    // ----- quick mode: token picker -----

    private TokenBalance quickToken() {
        for (TokenBalance b : act.balances()) if (b.tokenid.equals(quickTokenid)) return b;
        return null;
    }

    private CharSequence displayName(TokenBalance b) {
        CharSequence n = NftText.token(b);
        if (b.meta != null && b.meta.ticker != null && !b.meta.ticker.isEmpty()
                && !b.meta.ticker.equalsIgnoreCase(b.name)) n = Language.join(n, "  ·  ", b.meta.ticker.toUpperCase(java.util.Locale.ROOT));
        return n;
    }

    /** Theme-styled token chooser listing every balance with its sendable amount. */
    private void showTokenPicker() {
        final List<TokenBalance> all = new ArrayList<>(act.balances());
        if (all.isEmpty()) { status(Language.msg(l10n_nft_send_no_balances), false); return; }
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Design.bg());
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        final Sheet pick = Sheet.create(act, Language.msg(l10n_nft_send_which_token));
        pick.body(box);
        pick.action(Language.msg(l10n_utxo_cancel), Sheet.Style.SECONDARY, null);
        for (final TokenBalance b : all) {
            LinearLayout r = new LinearLayout(act);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(12), dp(12), dp(12), dp(12));
            TextView n = new TextView(act);
            Language.setText(n, displayName(b));
            n.setTextColor(Design.text()); n.setTextSize(14f); n.setTypeface(Design.typefaceBold());
            TextView amt = new TextView(act);
            Language.setText(amt, Format.amount(b.sendable));
            amt.setTextColor(Design.dim()); amt.setTextSize(13f);
            amt.setTypeface(android.graphics.Typeface.MONOSPACE);
            amt.setGravity(Gravity.END);
            r.addView(n, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(amt);
            r.setOnClickListener(v -> {
                quickTokenid = b.tokenid;
                pick.dismiss();
                refresh();
            });
            box.addView(r);
        }
        pick.show();
    }

    // ----- field helpers -----

    /** Fills the amount with the full spendable total; for Minima the burn is reserved first. */
    private void onMax() {
        BigDecimal burn = parseBurn();
        if (quickMode) {
            TokenBalance b = quickToken();
            if (b == null) return;
            BigDecimal total;
            try { total = new BigDecimal(b.sendable); } catch (Exception e) { return; }
            BigDecimal max = Util.isMinima(quickTokenid) && burn.signum() > 0 ? total.subtract(burn) : total;
            if (max.signum() < 0) max = BigDecimal.ZERO;
            Language.setText(amountInput, Util.tidyAmount(max.toPlainString()));
            return;
        }
        List<Coin> sel = act.selectedCoins();
        if (sel.isEmpty()) return;
        BigDecimal total = sum(sel);
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
        if (generation != reviewGeneration || sending) return false;
        reviewPending = false;
        previewBtn.setEnabled(true);
        if (!matchesDraft(fields, coins)) {
            status(Language.msg(l10n_nft_send_review_again), false);
            return false;
        }
        return true;
    }

    // ----- preview / validate -----

    /** Validates every field, then asks the node to confirm the recipient before Confirm. */
    private void onPreview() {
        if (sending) return;
        if (quickMode) { onPreviewQuick(); return; }

        final List<Coin> sel = act.selectedCoins();
        if (sel.isEmpty()) { status(Language.msg(l10n_nft_send_select_first), false); return; }

        final String recipient = addrInput.getText().toString().trim();
        if (recipient.isEmpty()) { setNote(addrNote, Language.msg(l10n_utxo_recipient_required), R.color.pm_nftwallet_ux_error); return; }
        if (!Util.isValidAddress(recipient)) { setNote(addrNote, Language.msg(l10n_utxo_invalid_address), R.color.pm_nftwallet_ux_error); return; }
        addrNote.setVisibility(View.GONE);

        final String tokenid = act.selectedTokenid();
        final boolean minima = Util.isMinima(tokenid);
        final String tokenName = sel.get(0).tokenName;
        final BigDecimal total = sum(sel);
        final BigDecimal burn = minima ? parseBurn() : BigDecimal.ZERO;
        if (burn.signum() < 0) { setNote(burnNote, Language.msg(l10n_utxo_invalid_burn), R.color.pm_nftwallet_ux_error); return; }
        burnNote.setVisibility(minima ? View.GONE : burnNote.getVisibility());

        BigDecimal maxSpendable = minima ? total.subtract(burn) : total;
        if (maxSpendable.signum() <= 0) { setNote(amountNote, Language.msg(l10n_utxo_burn_exceeds), R.color.pm_nftwallet_ux_error); return; }

        final BigDecimal amount;
        try { amount = new BigDecimal(amountInput.getText().toString().trim()); }
        catch (Exception e) { setNote(amountNote, Language.msg(l10n_utxo_valid_amount), R.color.pm_nftwallet_ux_error); return; }
        if (amount.signum() <= 0) { setNote(amountNote, Language.msg(l10n_utxo_positive_amount), R.color.pm_nftwallet_ux_error); return; }
        if (amount.compareTo(maxSpendable) > 0) { setNote(amountNote, Language.msg(l10n_utxo_max_amount, Util.tidyAmount(maxSpendable.toPlainString())), R.color.pm_nftwallet_ux_error); return; }
        int dec = act.tokenDecimals(tokenid);
        if (dec >= 0 && Util.decimalPlaces(amount) > dec) { setNote(amountNote, Language.msg(l10n_utxo_decimals, dec), R.color.pm_nftwallet_ux_error); return; }
        amountNote.setVisibility(View.GONE);

        // Change = inputs − amount − burn; only require/validate a change address when there's any left.
        final BigDecimal change = total.subtract(amount).subtract(burn);
        final boolean hasChange = change.signum() > 0;
        final String changeStr = hasChange ? change.stripTrailingZeros().toPlainString() : null;
        final String changeAddr = changeInput.getText().toString().trim();
        if (hasChange) {
            if (changeAddr.isEmpty()) { setNote(changeNote, Language.msg(l10n_utxo_change_required), R.color.pm_nftwallet_ux_error); return; }
            if (!Util.isValidAddress(changeAddr)) { setNote(changeNote, Language.msg(l10n_utxo_invalid_change), R.color.pm_nftwallet_ux_error); return; }
        }
        changeNote.setVisibility(View.GONE);

        final String burnStr = burn.signum() > 0 ? burn.stripTrailingZeros().toPlainString() : null;

        // Confirm the recipient with the node before showing the breakdown.
        final long generation = ++reviewGeneration;
        final String[] reviewedFields = fieldValues();
        final String[] reviewedCoins = quickMode ? null : selectionSnapshot();
        reviewPending = true;
        previewBtn.setEnabled(false);
        status(Language.msg(l10n_utxo_checking), true);
        act.node().cmd("checkaddress address:" + recipient, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!acceptReview(generation, reviewedFields, reviewedCoins)) return;
                if (!json.optBoolean("status", false)) {
                    setNote(addrNote, Language.msg(l10n_vestr_invalid_address), R.color.pm_nftwallet_ux_error);
                    statusView.setVisibility(View.GONE);
                    return;
                }
                statusView.setVisibility(View.GONE);
                showConfirm(sel, recipient, amount.stripTrailingZeros().toPlainString(),
                        hasChange ? changeAddr : null, changeStr, burnStr, tokenid, tokenName, total, generation, reviewedFields, reviewedCoins);
            }
            @Override public void onError(String message) {
                if (!acceptReview(generation, reviewedFields, reviewedCoins)) return;
                setNote(addrNote, NodeApi.ERR_NOT_ENABLED.equals(message)
                        ? Language.msg(l10n_explorer_node_retry) : Language.msg(l10n_nft_send_validate_failed), R.color.pm_nftwallet_ux_error);
                statusView.setVisibility(View.GONE);
            }
        });
    }

    /** Quick-mode validation → checkaddress → Confirm dialog → node `send`. */
    private void onPreviewQuick() {
        final String tokenid = quickTokenid;
        final TokenBalance tok = quickToken();
        final boolean minima = Util.isMinima(tokenid);
        if (tok == null && !minima) { status(Language.msg(l10n_nft_send_pick_token), false); return; }

        final String recipient = addrInput.getText().toString().trim();
        if (recipient.isEmpty()) { setNote(addrNote, Language.msg(l10n_utxo_recipient_required), R.color.pm_nftwallet_ux_error); return; }
        if (!Util.isValidAddress(recipient)) { setNote(addrNote, Language.msg(l10n_utxo_invalid_address), R.color.pm_nftwallet_ux_error); return; }
        addrNote.setVisibility(View.GONE);

        final BigDecimal burn = parseBurn();
        if (burn.signum() < 0) { setNote(burnNote, Language.msg(l10n_utxo_invalid_burn), R.color.pm_nftwallet_ux_error); return; }
        burnNote.setVisibility(View.GONE);

        final BigDecimal amount;
        try { amount = new BigDecimal(amountInput.getText().toString().trim()); }
        catch (Exception e) { setNote(amountNote, Language.msg(l10n_utxo_valid_amount), R.color.pm_nftwallet_ux_error); return; }
        if (amount.signum() <= 0) { setNote(amountNote, Language.msg(l10n_utxo_positive_amount), R.color.pm_nftwallet_ux_error); return; }
        BigDecimal sendable = BigDecimal.ZERO;
        try { if (tok != null) sendable = new BigDecimal(tok.sendable); } catch (Exception ignored) {}
        // For Minima the burn is spent from the same balance — amount + burn must fit.
        BigDecimal needed = minima ? amount.add(burn) : amount;
        if (tok != null && needed.compareTo(sendable) > 0) {
            setNote(amountNote, minima && burn.signum() > 0
                    ? Language.msg(l10n_nft_send_amount_burn_max, Util.tidyAmount(tok.sendable))
                    : Language.msg(l10n_utxo_max_amount, Util.tidyAmount(tok.sendable)), R.color.pm_nftwallet_ux_error); return;
        }
        int dec = act.tokenDecimals(tokenid);
        if (dec >= 0 && Util.decimalPlaces(amount) > dec) { setNote(amountNote, Language.msg(l10n_utxo_decimals, dec), R.color.pm_nftwallet_ux_error); return; }
        amountNote.setVisibility(View.GONE);

        final int split = parseSplit();
        if (split < 1 || split > 20) { setNote(splitNote, Language.msg(l10n_nft_send_split_range), R.color.pm_nftwallet_ux_error); return; }
        splitNote.setVisibility(View.GONE);

        final CharSequence tokenName = tok == null ? "Minima" : NftText.token(tok);
        final String amountStr = amount.stripTrailingZeros().toPlainString();
        final String burnStr = burn.signum() > 0 ? burn.stripTrailingZeros().toPlainString() : null;

        final long generation = ++reviewGeneration;
        final String[] reviewedFields = fieldValues();
        final String[] reviewedCoins = quickMode ? null : selectionSnapshot();
        reviewPending = true;
        previewBtn.setEnabled(false);
        status(Language.msg(l10n_utxo_checking), true);
        act.node().cmd("checkaddress address:" + recipient, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!acceptReview(generation, reviewedFields, reviewedCoins)) return;
                statusView.setVisibility(View.GONE);
                if (!json.optBoolean("status", false)) {
                    setNote(addrNote, Language.msg(l10n_vestr_invalid_address), R.color.pm_nftwallet_ux_error);
                    return;
                }
                showQuickConfirm(recipient, amountStr, burnStr, split, tokenName, tokenid, generation, reviewedFields);
            }
            @Override public void onError(String message) {
                if (!acceptReview(generation, reviewedFields, reviewedCoins)) return;
                setNote(addrNote, NodeApi.ERR_NOT_ENABLED.equals(message)
                        ? Language.msg(l10n_explorer_node_retry) : Language.msg(l10n_nft_send_validate_failed), R.color.pm_nftwallet_ux_error);
                statusView.setVisibility(View.GONE);
            }
        });
    }

    /** Quick-mode confirm: recipient / amount / burn / split breakdown before the node send. */
    private void showQuickConfirm(final String recipient, final String amountStr,
                                  final String burnStr, final int split, final CharSequence tokenName, final String tokenid, final long generation, final String[] reviewedFields) {
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(Design.bg());
        int pad = dp(18);
        body.setPadding(pad, dp(8), pad, 0);

        body.addView(sectionLabel(Language.upper(Language.msg(l10n_utxo_send))));
        body.addView(row(Language.msg(l10n_wallet_token), tokenName, true));
        body.addView(idRow(Language.msg(l10n_utxo_to), recipient, isMine(recipient) ? "(yours)" : "",
                Language.join(Util.tidyAmount(amountStr), " ", tokenName)));
        if (burnStr != null) body.addView(row(Language.msg(l10n_utxo_burn), Util.tidyAmount(burnStr) + " MINIMA", false));
        if (split > 1) body.addView(row(Language.msg(l10n_wallet_split), Language.msg(l10n_nft_send_equal_coins, split), false));
        body.addView(row(Language.msg(l10n_wallet_coins), Language.msg(l10n_nft_send_node_chosen), false));

        TextView warn = new TextView(act);
        Language.setText(warn, Language.msg(l10n_nft_send_warning_quick));
        warn.setTextColor(Design.red());
        warn.setTextSize(12f);
        warn.setPadding(0, dp(14), 0, 0);
        body.addView(warn);

        Sheet.create(act, Language.msg(l10n_utxo_confirm))
                .body(body)
                .action(Language.msg(l10n_utxo_back), Sheet.Style.SECONDARY, null)
                .action(Language.msg(l10n_utxo_send_action), Sheet.Style.PRIMARY,
                        () -> { if (acceptReview(generation, reviewedFields, null)) runQuickSend(recipient, amountStr, burnStr, split, tokenName, tokenid); })
                .show();
    }

    /** Prepare the node-selected transaction, persist its intent, then sign and post it. */
    private void runQuickSend(final String recipient, final String amountStr,
                              final String burnStr, final int split, final CharSequence tokenName, final String tokenid) {
        if (Util.isValidHexId(tokenid) && !TxnUtil.canUseNativeFunds(act,tokenid,burnStr)) { status(NftMessages.message(TransactionEvidence.RECORD_FAILED),false);return; }
        reviewGeneration++; reviewPending = false;
        final String[] submittedFields = fieldValues();
        final String[] submittedCoins = quickMode ? null : selectionSnapshot();
        sending = true;
        previewBtn.setEnabled(false);
        status(Language.msg(l10n_chat_sending), true);
        // The tokenid rides straight into the command. Balance rows are vetted at ingestion, but
        // this is the one place a bad id would become a chained command — never take it on trust.
        if (!Util.isValidHexId(tokenid)) {
            sending = false;
            previewBtn.setEnabled(true);
            status(Language.msg(l10n_nft_send_bad_token_id), false);
            return;
        }
        new PreparedSend(act,recipient,amountStr,burnStr,split,tokenid,tokenName.toString(),new TxnBuilder.Done() {
            @Override public void onPosted(String txpowid,List<TxnBuilder.OutCoin> outputs) {
                sending=false;previewBtn.setEnabled(true);
                status(Language.msg(l10n_nft_send_sent,Util.tidyAmount(amountStr),tokenName,recipient),true);
                if(matchesDraft(submittedFields,submittedCoins)) {
                    Language.setText(addrInput,"");Language.setText(amountInput,"");Language.setText(burnInput,"");Language.setText(splitInput,"");
                }
                act.reload();
            }
            @Override public void onFailed(String message) {
                sending=false;previewBtn.setEnabled(true);
                if(NodeApi.ERR_NOT_ENABLED.equals(message))message="The embedded node is unavailable. Open Minima Core from the menu and check its status, then retry.";
                status(Language.msg(l10n_utxo_failed,NftMessages.message(message)),false);
            }
            @Override public void onUnknown(String message) {
                sending=false;previewBtn.setEnabled(true);
                status(NftMessages.message(message),false);act.reload();
            }
        }).run();
    }

    // ----- expert confirm breakdown -----

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
        body.addView(row(Language.msg(l10n_utxo_total_in), Language.join(Util.tidyAmount(total.toPlainString()), " ", NftText.token(sel.get(0))), true));

        body.addView(sectionLabel(Language.msg(l10n_utxo_outputs)));
        body.addView(idRow(Language.msg(l10n_utxo_to), recipient, isMine(recipient) ? "(yours)" : "",
                Language.join(Util.tidyAmount(amountStr), " ", NftText.token(sel.get(0)))));
        if (changeStr != null) {
            // Flag a change address we don't recognise as ours with "(verify)" — guards against typos
            // in a hand-edited change field sending the remainder to a stranger.
            body.addView(idRow(Language.msg(l10n_utxo_change), changeAddr, isMine(changeAddr) ? "(yours)" : "(verify)",
                    Language.join(Util.tidyAmount(changeStr), " ", NftText.token(sel.get(0)))));
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

        Sheet.create(act, Language.msg(l10n_utxo_confirm))
                .body(body)
                .action(Language.msg(l10n_utxo_back), Sheet.Style.SECONDARY, null)
                .action(Language.msg(l10n_utxo_sign_post), Sheet.Style.PRIMARY, () ->
                        { if (acceptReview(generation, reviewedFields, reviewedCoins)) buildSend(sel, recipient, amountStr, changeAddr, changeStr, burnStr, tokenid, tokenName); })
                .show();
    }

    /** Records a History row, builds/signs/posts the txn, then updates that row by outcome. */
    private void buildSend(List<Coin> sel, final String recipient, final String amountStr,
                           String changeAddr, String changeStr, String burnStr,
                           final String tokenid, final String tokenName) {
        reviewGeneration++; reviewPending = false;
        final String[] submittedFields = fieldValues();
        final String[] submittedCoins = quickMode ? null : selectionSnapshot();
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
                status(Language.msg(l10n_nft_send_posted, Util.tidyAmount(amountStr), NftText.token(sel.get(0))), true);
                if (matchesDraft(submittedFields, submittedCoins)) {
                    Language.setText(addrInput, ""); Language.setText(amountInput, ""); Language.setText(burnInput, "");
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
                status(Language.msg(l10n_utxo_failed, NftMessages.message(message)), false);
                sending = false;
                previewBtn.setEnabled(true);
            }
            @Override public void onUnknown(String message) {
                act.history().update(internalid, HistoryDb.STATUS_UNKNOWN, null, message);
                status(NftMessages.message(message), false);
                sending = false;
                previewBtn.setEnabled(true);
                act.reload();
            }
        }).record(internalid).onProgress(label -> status(NftMessages.message(label), true)).run();
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
            CoinDetailDialog.copy(act, label, value);
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

    /** Parses the split field: empty = 1; unparseable = -1 (rejected). */
    private int parseSplit() {
        String s = splitInput.getText().toString().trim();
        if (s.isEmpty()) return 1;
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }

    /** Shows an inline validation note under a field; colors track the active Design mode. */
    private void setNote(TextView note, CharSequence msg, int colorRes) {
        note.setVisibility(View.VISIBLE);
        Language.setText(note, msg);
        note.setTextColor(colorRes == R.color.pm_nftwallet_ux_error ? Design.red() : Design.dim());
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
