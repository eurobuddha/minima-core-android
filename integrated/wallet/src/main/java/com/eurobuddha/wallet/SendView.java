package com.eurobuddha.wallet;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.minima.objects.Token;
import org.minima.objects.base.MiniData;
import org.minima.objects.base.MiniNumber;
import org.minima.utils.json.JSONObject;
import com.eurobuddha.wallet.comms.NodeApi;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Send / Split / Consolidate tab — builds a locally-signed transaction with {@link TxnFactory} and
 * publishes it through the non-admin {@link com.eurobuddha.wallet.comms.NodeLink}.
 *
 * <h3>Ordering (why we do NOT build before the review)</h3>
 * Building a transaction SIGNS it, which consumes a one-time WOTS leaf and advances the keyuses counter.
 * So the review dialog is computed from a dry-run (coin selection + amount/change math only) and shows
 * the signature number that WILL be used per signing key ({@code keyuses.currentUses(i)}); only on the user's explicit
 * confirm do we (1) re-check the fail-safe gate, (2) actually build+sign, (3) snapshot keyuses into the
 * vault, then (4) publish. A wrong-then-cancelled review therefore never burns a leaf.
 *
 * <h3>Units</h3>
 * Native Minima ({@code 0x00}) display == raw. For a custom token the user types the DISPLAY amount and
 * we convert to raw via {@code Token.getScaledMinimaAmount} before selection/build (the factory itself
 * does no scaling). Token burns are unsupported in v1 (burn forced 0 for tokens).
 */
public class SendView extends BaseView {

    private enum Mode { SEND, SPLIT, CONSOLIDATE }

    private final LinearLayout container;
    private final java.util.function.BooleanSupplier session;
    private Mode mMode = Mode.SEND;
    private String mTokenId = Util.MINIMA_TOKENID;   // selected token

    // live field refs (rebuilt per mode)
    private EditText mRecipient, mAmount, mBurn, mSplitN, mConsolidateCount;

    public SendView(MainActivity a) {
        super(a, makeRoot(a));
        session = a.sessionAuthorization();
        ScrollView sv = (ScrollView) root;
        container = (LinearLayout) sv.getChildAt(0);
        refresh();
    }

    private static View makeRoot(MainActivity a) {
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(Design.bg());
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(a, 18), dp(a, 14), dp(a, 18), dp(a, 24));
        sv.addView(col, new ScrollView.LayoutParams(MP(), WC()));
        return sv;
    }

    @Override
    public void onShown() { refresh(); }

    @Override
    public void refresh() {
        container.removeAllViews();

        // Fail-safe banner: if keyuses is untrusted, block and point to Settings.
        if (!act.vault().isKeyUsesTrusted()) {
            container.addView(blockedBanner());
            return;
        }

        container.addView(modeBar());
        container.addView(tokenPicker());

        switch (mMode) {
            case SEND:        buildSendForm();        break;
            case SPLIT:       buildSplitForm();       break;
            case CONSOLIDATE: buildConsolidateForm(); break;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Mode + token pickers
    // ---------------------------------------------------------------------------------------------

    private View modeBar() {
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(modeChip(Language.msg(l10n_wallet_send), Mode.SEND));
        bar.addView(modeChip(Language.msg(l10n_wallet_split), Mode.SPLIT));
        bar.addView(modeChip(Language.msg(l10n_wallet_consolidate), Mode.CONSOLIDATE));
        return bar;
    }

    private View modeChip(CharSequence label, Mode m) {
        TextView t = new TextView(act);
        Language.setText(t, label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(act, 10), 0, dp(act, 10));
        t.setTextSize(13f);
        boolean sel = mMode == m;
        t.setTextColor(sel ? Design.onAccent() : Design.dim());
        t.setBackgroundColor(sel ? Design.accent() : Design.surface());
        t.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WC(), 1f);
        lp.rightMargin = dp(act, 2);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> { mMode = m; refresh(); });
        return t;
    }

    private View tokenPicker() {
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(act, 14), 0, dp(act, 4));
        col.addView(label(Language.msg(l10n_wallet_token)));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        List<TokenBalance> bals = act.balances();
        if (bals.isEmpty()) {
            col.addView(hint(Language.msg(l10n_wallet_fund_address)));
        }
        for (TokenBalance b : bals) {
            row.addView(tokenChip(b));
        }
        ScrollView.LayoutParams lp = new ScrollView.LayoutParams(MP(), WC());
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(act);
        hs.addView(row);
        col.addView(hs, lp);
        return col;
    }

    private View tokenChip(TokenBalance b) {
        LinearLayout chip = new LinearLayout(act);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setPadding(dp(act, 12), dp(act, 8), dp(act, 12), dp(act, 8));
        boolean sel = b.tokenid.equals(mTokenId) || (b.isMinima() && Util.isMinima(mTokenId));
        chip.setBackgroundColor(sel ? Design.accentSoft() : Design.surface());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WC(), WC());
        lp.rightMargin = dp(act, 6);
        chip.setLayoutParams(lp);
        TextView name = new TextView(act);
        name.setText(b.isMinima() ? "MINIMA" : (b.meta != null && b.meta.ticker != null && !b.meta.ticker.isEmpty() ? b.meta.ticker : b.name));
        name.setTextColor(sel ? Design.accent() : Design.text());
        name.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        name.setTextSize(12f);
        TextView amt = new TextView(act);
        amt.setText(Util.tidyAmount(b.sendable));
        amt.setTextColor(Design.dim());
        amt.setTextSize(11f);
        chip.addView(name);
        chip.addView(amt);
        chip.setOnClickListener(v -> { mTokenId = b.tokenid; refresh(); });
        return chip;
    }

    // ---------------------------------------------------------------------------------------------
    // Forms
    // ---------------------------------------------------------------------------------------------

    private void buildSendForm() {
        container.addView(label(Language.msg(l10n_wallet_recipient)));
        mRecipient = field(InputType.TYPE_CLASS_TEXT);
        container.addView(mRecipient);

        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(smallButton(Language.msg(l10n_wallet_paste), v -> paste(mRecipient)));
        btns.addView(smallButton(Language.msg(l10n_wallet_scan_qr), v -> act.scanQr(text -> {
            if (text != null) mRecipient.setText(text.trim());
        })));
        container.addView(btns);

        // Raw 0x addresses carry no checksum → no typo protection. Warn prominently when one is entered.
        final TextView rawWarn = rawAddrWarning();
        container.addView(rawWarn);
        mRecipient.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                boolean raw = Util.isRaw0xAddress(e.toString().trim());
                rawWarn.setVisibility(raw ? View.VISIBLE : View.GONE);
            }
        });

        container.addView(label(Language.msg(l10n_history_amount)));
        mAmount = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        container.addView(mAmount);
        container.addView(smallButton(Language.msg(l10n_wallet_max), v -> mAmount.setText(maxDisplay())));

        if (isMinima()) {
            container.addView(label(Language.msg(l10n_wallet_burn_optional)));
            mBurn = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            mBurn.setText("0");
            container.addView(mBurn);
        }

        container.addView(reviewButton(Language.msg(l10n_wallet_review_send), this::reviewSend));
    }

    private void buildSplitForm() {
        container.addView(hint(Language.msg(l10n_wallet_split_body)));
        container.addView(label(Language.msg(l10n_wallet_amount_to_split)));
        mAmount = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        container.addView(mAmount);
        container.addView(smallButton(Language.msg(l10n_wallet_max), v -> mAmount.setText(maxDisplay())));

        container.addView(label(Language.msg(l10n_wallet_split_count, TxnFactory.MAX_SPLIT_COINS)));
        mSplitN = field(InputType.TYPE_CLASS_NUMBER);
        mSplitN.setText("2");
        container.addView(mSplitN);

        if (isMinima()) {
            container.addView(label(Language.msg(l10n_wallet_burn_optional)));
            mBurn = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            mBurn.setText("0");
            container.addView(mBurn);
        }
        container.addView(reviewButton(Language.msg(l10n_wallet_review_split), this::reviewSplit));
    }

    private void buildConsolidateForm() {
        int available = coinsOfToken().size();
        container.addView(hint(Language.msg(l10n_wallet_consolidate_body, available)));
        container.addView(label(Language.msg(l10n_wallet_consolidate_count, CoinSelector.MAX_INPUTS)));
        mConsolidateCount = field(InputType.TYPE_CLASS_NUMBER);
        mConsolidateCount.setText(String.valueOf(Math.min(Math.max(available, 3), CoinSelector.MAX_INPUTS)));
        container.addView(mConsolidateCount);

        if (isMinima()) {
            container.addView(label(Language.msg(l10n_wallet_burn_optional)));
            mBurn = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            mBurn.setText("0");
            container.addView(mBurn);
        }
        container.addView(reviewButton(Language.msg(l10n_wallet_review_consolidate), this::reviewConsolidate));
    }

    // ---------------------------------------------------------------------------------------------
    // Review (dry run) → confirm → build+sign → publish
    // ---------------------------------------------------------------------------------------------

    private void reviewSend() {
        try {
            if (!session.getAsBoolean()) return;
            final TxnFactory factory = act.factory();
            final MiniData tokenId = tokenIdData();
            String recipient = mRecipient.getText().toString().trim();
            if (!Util.isValidAddress(recipient)) throw new IllegalArgumentException(Language.text(act, l10n_wallet_invalid_recipient));

            MiniNumber burn = parseBurn();
            Token token = tokenDescriptor();
            MiniNumber amountRaw = toRaw(mAmount.getText().toString(), token);
            MiniNumber required = amountRaw.add(isMinima() ? burn : MiniNumber.ZERO);

            List<JSONObject> sel = selectToCover(required);
            MiniNumber inSum = sumRaw(sel);
            MiniNumber change = inSum.sub(required);

            CharSequence rev = Language.join(Language.msg(l10n_wallet_token), ": ", tokenLabel(), "\n",
                    Language.msg(l10n_history_to), ": ", recipient, "\n",
                    Language.msg(l10n_history_amount), ": ", mAmount.getText().toString(), "\n",
                    Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_wallet_review_inputs, sel.size(), sel.size(), inSum.toString()), "\n",
                    change.isMore(MiniNumber.ZERO) ? Language.join(Language.msg(l10n_wallet_review_change, change.toString()), "\n") : "",
                    isMinima() ? Language.join(Language.msg(l10n_wallet_review_burn, burn.toString()), "\n") : "");

            withResolvedInputs(sel, inputs -> confirmAndSend(rev, sel, () ->
                    factory.buildSend(inputs, recipient, amountRaw, tokenId, burn, newId())));
        } catch (Exception e) {
            err(e);
        }
    }

    private void reviewSplit() {
        try {
            if (!session.getAsBoolean()) return;
            final TxnFactory factory = act.factory();
            final MiniData tokenId = tokenIdData();
            int n = Integer.parseInt(mSplitN.getText().toString().trim());
            if (n < 1 || n > TxnFactory.MAX_SPLIT_COINS) throw new IllegalArgumentException(Language.text(act, l10n_wallet_split_range, TxnFactory.MAX_SPLIT_COINS));
            MiniNumber burn = parseBurn();
            Token token = tokenDescriptor();
            MiniNumber amountRaw = toRaw(mAmount.getText().toString(), token);
            MiniNumber required = amountRaw.add(isMinima() ? burn : MiniNumber.ZERO);

            List<JSONObject> sel = selectToCover(required);
            MiniNumber inSum = sumRaw(sel);
            MiniNumber change = inSum.sub(required);

            CharSequence rev = Language.join(Language.msg(l10n_wallet_token), ": ", tokenLabel(), "\n",
                    Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_wallet_review_split_line, n, mAmount.getText().toString(), n), "\n",
                    Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_wallet_review_inputs, sel.size(), sel.size(), inSum.toString()), "\n",
                    change.isMore(MiniNumber.ZERO) ? Language.join(Language.msg(l10n_wallet_review_change, change.toString()), "\n") : "",
                    isMinima() ? Language.join(Language.msg(l10n_wallet_review_burn, burn.toString()), "\n") : "");

            final int fn = n;
            withResolvedInputs(sel, inputs -> confirmAndSend(rev, sel, () ->
                    factory.buildSplit(inputs, amountRaw, fn, tokenId, burn, newId())));
        } catch (Exception e) {
            err(e);
        }
    }

    private void reviewConsolidate() {
        try {
            if (!session.getAsBoolean()) return;
            final TxnFactory factory = act.factory();
            final MiniData tokenId = tokenIdData();
            int count = Integer.parseInt(mConsolidateCount.getText().toString().trim());
            if (count < 3) throw new IllegalArgumentException(Language.text(act, l10n_wallet_consolidate_minimum));
            MiniNumber burn = parseBurn();

            List<JSONObject> sel = CoinSelector.selectSmallest(act.coins(), mTokenId, count);
            MiniNumber inSum = sumRaw(sel);
            MiniNumber out = isMinima() ? inSum.sub(burn) : inSum;

            CharSequence rev = Language.join(Language.msg(l10n_wallet_token), ": ", tokenLabel(), "\n",
                    Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_wallet_review_merge_line, sel.size(), sel.size(), inSum.toString()), "\n",
                    Language.msg(l10n_wallet_review_output, out.toString()), "\n",
                    isMinima() ? Language.join(Language.msg(l10n_wallet_review_burn, burn.toString()), "\n") : "");

            withResolvedInputs(sel, inputs -> confirmAndSend(rev, sel, () ->
                    factory.buildConsolidate(inputs, tokenId, burn, newId())));
        } catch (Exception e) {
            err(e);
        }
    }

    /** Show the review dialog, and on confirm run the fail-safe gate, build+sign, snapshot, publish. */
    private void confirmAndSend(CharSequence review, List<JSONObject> sel,
                                java.util.concurrent.Callable<TxnFactory.BuiltTxn> builder) {
        if (!session.getAsBoolean()) return;
        final java.util.function.BooleanSupplier current = act.sessionAuthorization();
        CharSequence full = Language.join(review, keyUsageLines(sel), "\n\n", Language.msg(l10n_wallet_irreversible));

        new WalletDialog(act)
                .setTitle(Language.msg(l10n_wallet_confirm_transaction))
                .setMessage(full)
                .setPositiveButton(Language.msg(l10n_wallet_sign_broadcast), (d, w) -> {
                    if (!session.getAsBoolean() || !current.getAsBoolean()) {
                        Toast.makeText(act, Language.msg(l10n_wallet_review_again), Toast.LENGTH_LONG).show(); return;
                    }
                    act.runSigningWork(builder, this::publish, error -> {
                        if (error instanceof SeedVault.SigningNotAllowedException)
                            Toast.makeText(act, WalletMessages.error(error), Toast.LENGTH_LONG).show();
                        else err(error);
                    });
                })
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    /** One line per distinct signing key in the selection: the signature number that WILL be used. */
    private CharSequence keyUsageLines(List<JSONObject> zSel) {
        CharSequence lines = "";
        List<Integer> seen = new ArrayList<>();
        for (JSONObject c : zSel) {
            int ki = keyIndexOf(c);
            if (seen.contains(ki)) continue;
            seen.add(ki);
            lines = Language.join(lines, "\n", Language.msg(l10n_wallet_key_usage, ki, act.keyUses().currentUses(ki), Util.WOTS_MAX_USES));
        }
        return lines;
    }

    private void publish(TxnFactory.BuiltTxn built) {
        if (!session.getAsBoolean()) return;
        Toast.makeText(act, Language.msg(l10n_wallet_broadcasting), Toast.LENGTH_SHORT).show();
        act.node().publish(built, new NodeApi.Cb() {
            @Override public void onResult(org.json.JSONObject json) {
                if (!session.getAsBoolean()) return;
                String txpowid = Util.extractTxpowid(json, built.getID());
                Toast.makeText(act, Language.msg(l10n_wallet_broadcast_sent, txpowid), Toast.LENGTH_LONG).show();
                act.reload();
            }
            @Override public void onError(String message) {
                if (!session.getAsBoolean()) return;
                Toast.makeText(act, Language.msg(l10n_wallet_broadcast_failed, WalletMessages.message(message)), Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Coin / token helpers
    // ---------------------------------------------------------------------------------------------

    private boolean isMinima() { return Util.isMinima(mTokenId); }

    private MiniData tokenIdData() { return new MiniData(isMinima() ? "0x00" : mTokenId); }

    private String tokenLabel() {
        for (TokenBalance b : act.balances()) {
            if (b.tokenid.equals(mTokenId)) return b.isMinima() ? "Minima" : b.name;
        }
        return isMinima() ? "Minima" : mTokenId;
    }

    /** All raw coins of the selected token from the last node load. */
    private List<JSONObject> coinsOfToken() {
        List<JSONObject> out = new ArrayList<>();
        org.minima.utils.json.JSONArray all = act.coins();
        if (all == null) return out;
        for (Object o : all) {
            JSONObject c = (JSONObject) o;
            String tid = String.valueOf(c.get("tokenid"));
            if (isMinima() ? Util.isMinima(tid) : mTokenId.equals(tid)) out.add(c);
        }
        return out;
    }

    private List<JSONObject> selectToCover(MiniNumber requiredRaw) {
        return CoinSelector.selectToCover(act.coins(), mTokenId, requiredRaw);
    }

    private MiniNumber sumRaw(List<JSONObject> zSel) { return CoinSelector.sumRaw(zSel); }

    /**
     * Hand {@code zGo} the InputCoins for this selection SAFELY: native Minima converts locally,
     * custom-token coins are fetched BYTE-EXACT from the node (coinexport) — the coins-JSON token
     * descriptor is lossy for object names and consensus rejects the mismatch silently after
     * txnpost. Nothing signs here; signing stays behind the user's confirm.
     */
    private void withResolvedInputs(List<JSONObject> zSel,
                                    java.util.function.Consumer<List<TxnFactory.InputCoin>> zGo) {
        if (!session.getAsBoolean()) return;
        if (isMinima()) { zGo.accept(toInputs(zSel)); return; }
        act.resolveInputs(zSel, new MainActivity.InputsCb() {
            @Override public void onResolved(List<TxnFactory.InputCoin> inputs) { if (session.getAsBoolean()) zGo.accept(inputs); }
            @Override public void onError(String message) {
                if (!session.getAsBoolean()) return;
                Toast.makeText(act, Language.msg(l10n_wallet_prepare_coins_failed, WalletMessages.message(message)), Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Map selected bundled-JSON coins → factory InputCoins, each at its OWN wallet key index. */
    private List<TxnFactory.InputCoin> toInputs(List<JSONObject> zSel) {
        List<TxnFactory.InputCoin> in = new ArrayList<>();
        for (JSONObject c : zSel) in.add(TxnFactory.fromCoinJson(c, keyIndexOf(c)));
        return in;
    }

    /** The wallet key index owning this coin's address (coins come pre-filtered to ours). */
    private int keyIndexOf(JSONObject zCoin) {
        String addr = String.valueOf(zCoin.get("address"));
        Integer ki = act.keyIndexForAddress(addr);
        if (ki == null) throw new IllegalStateException(Language.text(act, l10n_wallet_foreign_coin, addr));
        return ki;
    }

    /** The Token descriptor for the selected custom token (null for native Minima). */
    private Token tokenDescriptor() {
        if (isMinima()) return null;
        List<JSONObject> pool = coinsOfToken();
        if (pool.isEmpty()) throw new IllegalStateException(Language.text(act, l10n_wallet_no_token_coins));
        return TxnFactory.fromCoinJson(pool.get(0), keyIndexOf(pool.get(0))).getToken();
    }

    /** Convert a human display amount to RAW units (native == display; token scaled down). */
    private MiniNumber toRaw(String zDisplay, Token zToken) {
        MiniNumber disp = new MiniNumber(zDisplay.trim());
        if (disp.isLessEqual(MiniNumber.ZERO)) throw new IllegalArgumentException(Language.text(act, l10n_wallet_positive_amount));
        if (isMinima() || zToken == null) return disp;
        return zToken.getScaledMinimaAmount(disp);
    }

    private MiniNumber parseBurn() {
        if (!isMinima() || mBurn == null) return MiniNumber.ZERO;
        String s = mBurn.getText().toString().trim();
        if (s.isEmpty()) return MiniNumber.ZERO;
        MiniNumber b = new MiniNumber(s);
        if (b.isLess(MiniNumber.ZERO)) throw new IllegalArgumentException(Language.text(act, l10n_wallet_negative_burn));
        return b;
    }

    /**
     * The MAX display amount = the selected token's FULL sendable balance (no burn headroom is
     * subtracted). For native Minima a non-zero burn added ON TOP of MAX would exceed the balance and
     * the coin selection would then report "insufficient" at review — so to send-all WITH a burn, lower
     * the amount by the burn. (Keeping MAX at the full sendable is intentional: most sends use burn=0.)
     */
    private String maxDisplay() {
        for (TokenBalance b : act.balances()) {
            if (b.tokenid.equals(mTokenId)) return Util.tidyAmount(b.sendable);
        }
        return "0";
    }

    private String newId() {
        return Util.newTxnId();
    }

    // ---------------------------------------------------------------------------------------------
    // Small view builders
    // ---------------------------------------------------------------------------------------------

    private View blockedBanner() {
        LinearLayout b = new LinearLayout(act);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackgroundColor(Design.redSoft());
        b.setPadding(dp(act, 14), dp(act, 14), dp(act, 14), dp(act, 14));
        TextView t = new TextView(act);
        Language.setText(t, Language.msg(l10n_wallet_sending_blocked));
        t.setTextColor(Design.red());
        t.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        t.setTextSize(16f);
        TextView d = new TextView(act);
        Language.setText(d, Language.msg(l10n_wallet_sending_blocked_body));
        d.setTextColor(Design.text());
        d.setTextSize(13f);
        d.setPadding(0, dp(act, 8), 0, dp(act, 12));
        Button go = new Button(act);
        Language.setText(go, Language.msg(l10n_wallet_open_settings));
        go.setAllCaps(false);
        go.setTextColor(Design.onAccent());
        go.setBackgroundColor(Design.accent());
        go.setOnClickListener(v -> act.goToTab(MainActivity.TAB_SETTINGS));
        b.addView(t); b.addView(d); b.addView(go);
        return b;
    }

    private TextView label(CharSequence s) {
        TextView t = new TextView(act);
        Language.setText(t, s);
        t.setTextColor(Design.dim());
        t.setTextSize(11f);
        t.setLetterSpacing(0.1f);
        t.setPadding(0, dp(act, 14), 0, dp(act, 4));
        return t;
    }

    private TextView hint(CharSequence s) {
        TextView t = new TextView(act);
        Language.setText(t, s); t.setTextColor(Design.dim2()); t.setTextSize(12f);
        t.setPadding(0, dp(act, 6), 0, dp(act, 4));
        return t;
    }

    /** Inline (initially hidden) warning shown when the recipient is a raw, checksum-less 0x address. */
    private TextView rawAddrWarning() {
        TextView t = new TextView(act);
        Language.setText(t, Language.msg(l10n_wallet_raw_address_warning));
        t.setTextColor(Design.red());
        t.setTextSize(12f);
        t.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        t.setBackgroundColor(Design.redSoft());
        t.setPadding(dp(act, 10), dp(act, 8), dp(act, 10), dp(act, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP(), WC());
        lp.topMargin = dp(act, 8);
        t.setLayoutParams(lp);
        t.setVisibility(View.GONE);
        return t;
    }

    private EditText field(int inputType) {
        EditText e = new EditText(act);
        e.setInputType(inputType);
        e.setTextColor(Design.text());
        e.setHintTextColor(Design.dim());
        e.setBackgroundColor(Design.surface());
        e.setPadding(dp(act, 10), dp(act, 10), dp(act, 10), dp(act, 10));
        e.setTextSize(14f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP(), WC());
        e.setLayoutParams(lp);
        return e;
    }

    private Button smallButton(CharSequence label, View.OnClickListener l) {
        Button b = new Button(act);
        Language.setText(b, label);
        b.setAllCaps(false);
        b.setTextColor(Design.accent());
        b.setBackgroundColor(Design.surface());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WC(), WC());
        lp.topMargin = dp(act, 6); lp.rightMargin = dp(act, 6);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private Button reviewButton(CharSequence label, Runnable r) {
        Button b = new Button(act);
        Language.setText(b, label);
        b.setAllCaps(false);
        b.setTextColor(Design.onAccent());
        b.setBackgroundColor(Design.accent());
        b.setTextSize(15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP(), WC());
        lp.topMargin = dp(act, 20);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> r.run());
        return b;
    }

    private void paste(EditText into) {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
            if (t != null) into.setText(t.toString().trim());
        }
    }

    private void err(Exception e) {
        Toast.makeText(act, WalletMessages.error(e), Toast.LENGTH_LONG).show();
    }

    private static int MP() { return LinearLayout.LayoutParams.MATCH_PARENT; }
    private static int WC() { return LinearLayout.LayoutParams.WRAP_CONTENT; }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }
}
