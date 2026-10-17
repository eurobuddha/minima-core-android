package com.eurobuddha.utxo;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.List;

/** Balances tab: per-token aggregates with icon graphics and full token/coin data. */
public class BalancesView extends BaseView {

    private final LinearLayout container;

    /** Inflates the balances container and renders the first listing. */
    public BalancesView(MainActivity a) {
        super(a, R.layout.pm_utxo_view_balances);
        container = find(R.id.pm_utxo_balancesContainer);
        root.setBackgroundColor(Design.bg());
        refresh();
    }

    /** Rebuilds one rich card per token from the aggregated balances. */
    @Override
    public void refresh() {
        container.removeAllViews();
        List<TokenBalance> balances = act.balances();
        if (balances.isEmpty()) {
            TextView tv = new TextView(act);
            Language.setText(tv, Language.msg(l10n_utxo_no_balances));
            tv.setTextColor(Design.dim());
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(40), 0, 0);
            container.addView(tv);
            return;
        }

        for (TokenBalance b : balances) container.addView(buildCard(b));
    }

    private final Runnable refreshTask = this::refresh;

    /** Coalesce the async web-validation callbacks (one per token) into a single re-render. */
    private void scheduleRefresh() {
        container.removeCallbacks(refreshTask);
        container.postDelayed(refreshTask, 120);
    }

    // ---------- one balance card, matching the utxoWallet dapp exactly (square, bordered, mono) ----------

    private static final int MP = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WC = LinearLayout.LayoutParams.WRAP_CONTENT;

    private View buildCard(TokenBalance b) {
        boolean nativeCoin = b.isMinima();

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));            // dapp: 12px V / 14px H
        card.setBackground(nativeCoin
                ? leftAccentBox(Design.bg(), Design.border(), Design.accent())
                : borderBox(Design.bg(), Design.border()));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MP, WC);
        clp.bottomMargin = dp(8);
        card.setLayoutParams(clp);

        // top row: [40dp icon] [symbol/name] [amount/count →]
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout slot = new FrameLayout(act);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(40), dp(40));
        slp.rightMargin = dp(12);
        slot.setLayoutParams(slp);
        if (nativeCoin) {
            slot.setBackground(borderBox(0xFFFFFFFF, Design.border()));         // official coin: white tile, black M
            ImageView g = new ImageView(act);
            g.setLayoutParams(new FrameLayout.LayoutParams(MP, MP));
            g.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int mp = dp(8); g.setPadding(mp, mp, mp, mp);
            g.setImageResource(R.drawable.pm_utxo_minima_coin);                        // official Minima M (MediaKit shape), brand-black
            slot.addView(g);
            ImageView badge = new ImageView(act);                              // native coin is the official verified coin
            int bs = dp(15);
            badge.setLayoutParams(new FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM | Gravity.END));
            badge.setImageBitmap(Identicon.checkBadge(bs));
            slot.addView(badge);
        } else {
            slot.setBackground(borderBox(Design.surface(), Design.border()));
            ImageView icon = new ImageView(act);
            icon.setLayoutParams(new FrameLayout.LayoutParams(MP, MP));
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);          // object-fit: cover
            icon.setImageBitmap(Identicon.forToken(b.tokenid, dp(40)));  // deterministic base
            slot.addView(icon);
            ImageLoader.loadOver(act, b.meta.iconUrl, icon, null);   // updates this ImageView in place — no full re-render
        }

        // Web-validation checkmark: the token's webvalidate URL hosts its tokenid (domain-ownership proof).
        if (!nativeCoin && notEmpty(b.meta.webvalidate)) {
            WebValidate.ensure(act, b.tokenid, b.meta.webvalidate, this::scheduleRefresh);
            if (Boolean.TRUE.equals(WebValidate.status(b.tokenid))) {
                ImageView badge = new ImageView(act);
                int bs = dp(15);
                badge.setLayoutParams(new FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM | Gravity.END));
                badge.setImageBitmap(Identicon.checkBadge(bs));
                slot.addView(badge);
            }
        }
        top.addView(slot);

        LinearLayout ident = new LinearLayout(act);
        ident.setOrientation(LinearLayout.VERTICAL);
        String symbol = notEmpty(b.meta.ticker) ? b.meta.ticker : b.name;
        TextView sym = mono(Language.upper(notEmpty(b.meta.ticker) ? b.meta.ticker : UtxoText.token(b)), 13f, Design.heading(), true);
        sym.setLetterSpacing(0.115f); sym.setMaxLines(1); sym.setEllipsize(TextUtils.TruncateAt.END);
        ident.addView(sym);
        CharSequence secondary = (notEmpty(b.name) && !b.name.equalsIgnoreCase(symbol)) ? UtxoText.token(b) : null;
        if (b.isNft()) secondary = (secondary == null ? "NFT" : Language.join(secondary, "  ·  NFT"));
        if (secondary != null) {
            TextView nm = mono(secondary, 11f, b.isNft() ? Design.accentText() : Design.dim(), false);
            nm.setMaxLines(1); nm.setEllipsize(TextUtils.TruncateAt.END); nm.setPadding(0, dp(2), 0, 0);
            ident.addView(nm);
        }
        top.addView(ident, new LinearLayout.LayoutParams(0, WC, 1f));

        LinearLayout amtCol = new LinearLayout(act);
        amtCol.setOrientation(LinearLayout.VERTICAL);
        amtCol.setGravity(Gravity.END);
        TextView amt = mono(Util.tidyAmount(b.sendable), 17f, Design.heading(), true);
        amt.setGravity(Gravity.END);
        amtCol.addView(amt);
        TextView cnt = mono(Language.upper(Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_utxo_coin_count, b.coins)), 10f, Design.dim(), false);
        cnt.setLetterSpacing(0.04f); cnt.setGravity(Gravity.END); cnt.setPadding(0, dp(3), 0, 0);
        amtCol.addView(cnt);
        top.addView(amtCol, new LinearLayout.LayoutParams(WC, WC));

        card.addView(top);

        // sendable / locked / pending split (only when there's a locked or pending part)
        String locked = lockedAmount(b);
        if (positive(locked) || positive(b.unconfirmed)) {
            card.addView(divider());
            card.addView(splitRow(Language.msg(l10n_utxo_sendable_caps), Util.tidyAmount(b.sendable), Design.accentText()));
            if (positive(locked)) card.addView(splitRow(Language.msg(l10n_utxo_locked_caps), Util.tidyAmount(locked), Design.dim()));
            if (positive(b.unconfirmed)) card.addView(splitRow(Language.msg(l10n_utxo_pending_caps), Util.tidyAmount(b.unconfirmed), Design.dim()));
        }

        card.setOnClickListener(v -> showTokenDetail(b));
        return card;
    }

    private TextView mono(CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(act);
        Language.setText(t, s); t.setTextColor(color); t.setTextSize(sp);
        t.setTypeface(Design.typeface(), bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private View divider() {
        View v = new View(act);
        v.setBackgroundColor(Design.border());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, Math.max(1, dp(1)));
        lp.topMargin = dp(10); lp.bottomMargin = dp(8);
        v.setLayoutParams(lp);
        return v;
    }

    private View splitRow(CharSequence label, String value, int labelColor) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(2), 0, dp(2));
        TextView l = mono(label, 10f, labelColor, true); l.setLetterSpacing(0.08f);
        TextView val = mono(value, 13f, Design.heading(), true); val.setGravity(Gravity.END);
        r.addView(l, new LinearLayout.LayoutParams(0, WC, 1f));
        r.addView(val, new LinearLayout.LayoutParams(0, WC, 1f));
        return r;
    }

    /** Square, filled, 1px-bordered box (radius 0) — the dapp's card/slot look. */
    private GradientDrawable borderBox(int bg, int border) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setStroke(Math.max(1, dp(1)), border);
        return d;
    }

    /** Same, plus a 3dp accent left rule (the .bal-card.native marker for the Minima coin). */
    private Drawable leftAccentBox(int bg, int border, int accent) {
        LayerDrawable ld = new LayerDrawable(new Drawable[]{ borderBox(bg, border), new ColorDrawable(accent) });
        ld.setLayerGravity(1, Gravity.LEFT);
        ld.setLayerWidth(1, dp(3));
        return ld;
    }

    /** Full-detail dialog for one token: large icon (tap → full-res), all metadata, and a Receive action. */
    private void showTokenDetail(TokenBalance b) {
        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(16));
        box.setBackgroundColor(Design.bg());
        sv.addView(box);

        ImageView big = new ImageView(act);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(128), dp(128));
        ip.gravity = Gravity.CENTER_HORIZONTAL; ip.bottomMargin = dp(6); big.setLayoutParams(ip);
        if (b.isMinima()) { big.setBackgroundColor(0xFFFFFFFF); int mp = dp(20); big.setPadding(mp, mp, mp, mp); big.setImageResource(R.drawable.pm_utxo_minima_coin); big.setScaleType(ImageView.ScaleType.FIT_CENTER); }
        else { big.setImageBitmap(Identicon.forToken(b.tokenid, dp(128))); ImageLoader.loadOver(act, b.meta.iconUrl, big, null); }
        box.addView(big);
        if (b.hasIcon()) {
            big.setOnClickListener(v -> showImageFull(b.meta.iconUrl));
            TextView hint = new TextView(act);
            Language.setText(hint, b.isNft() ? Language.msg(l10n_utxo_full_resolution) : Language.msg(l10n_utxo_enlarge_image));
            hint.setTextColor(Design.dim()); hint.setTextSize(11f); hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, 0, 0, dp(8)); box.addView(hint);
        }

        TextView title = new TextView(act);
        Language.setText(title, Language.join(UtxoText.token(b), b.isNft() ? "   ·  NFT" : ""));
        title.setTextColor(Design.accentText()); title.setTextSize(18f);
        title.setGravity(Gravity.CENTER); title.setTypeface(Design.typeface(), android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(8)); box.addView(title);

        addKv(box, Language.msg(l10n_utxo_sendable), Util.tidyAmount(b.sendable));
        addKv(box, Language.msg(l10n_utxo_confirmed), Util.tidyAmount(b.confirmed));
        if (positive(b.unconfirmed)) addKv(box, Language.msg(l10n_utxo_pending), Util.tidyAmount(b.unconfirmed));
        String locked = lockedAmount(b);
        if (positive(locked)) addKv(box, Language.msg(l10n_utxo_locked), Util.tidyAmount(locked));
        addKv(box, Language.msg(l10n_utxo_coins_label), String.valueOf(b.coins));
        String supply = (b.isMinima() && !act.circulatingSupply().isEmpty())
                ? wholeNumber(act.circulatingSupply()) : Util.tidyAmount(b.total);
        addKv(box, Language.msg(l10n_utxo_supply), supply);
        if (b.meta.ticker != null && !b.meta.ticker.isEmpty()) addKv(box, Language.msg(l10n_utxo_ticker), b.meta.ticker);
        if (b.meta.decimals != null && !b.meta.decimals.isEmpty()) addKv(box, Language.msg(l10n_utxo_decimals_label), b.meta.decimals);
        if (notEmpty(b.meta.owner)) addKv(box, Language.msg(l10n_utxo_owner), b.meta.owner);

        box.addView(sectionLabel(Language.msg(l10n_utxo_token_id)));
        TextView idv = new TextView(act);
        Language.setText(idv, b.tokenid); idv.setTextColor(Design.text()); idv.setTextSize(12f);
        idv.setTextIsSelectable(true); idv.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.addView(idv);

        if (notEmpty(b.meta.description)) {
            box.addView(sectionLabel(Language.msg(l10n_utxo_description)));
            TextView d = new TextView(act);
            Language.setText(d, b.meta.description); d.setTextColor(Design.dim()); d.setTextSize(13f);
            box.addView(d);
        }
        // Token metadata is minter-controlled: only offer to open real web urls, never tel:/sms:/custom schemes.
        if (isHttp(b.meta.externalUrl)) box.addView(linkRow(Language.msg(l10n_utxo_website), b.meta.externalUrl));
        if (isHttp(b.meta.webvalidate)) box.addView(linkRow(Language.msg(l10n_utxo_web_validation), b.meta.webvalidate));

        Design.dialog(act)
                .setView(sv)
                .setPositiveButton(Language.msg(l10n_utxo_receive), (d, w) -> act.goToTab(MainActivity.TAB_RECEIVE))
                .setNegativeButton(Language.msg(l10n_utxo_close), null)
                .show();
    }

    /** Full-screen, full-resolution image (for NFT art). Tap to dismiss. */
    private void showImageFull(String url) {
        ImageView iv = new ImageView(act);
        iv.setAdjustViewBounds(true);
        iv.setBackgroundColor(0xFF000000);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageLoader.loadFull(act, url, iv, R.drawable.pm_utxo_ic_coin_placeholder);
        androidx.appcompat.app.AlertDialog dlg = Design.dialog(act).setView(iv).create();
        iv.setOnClickListener(v -> dlg.dismiss());
        dlg.show();
    }

    private void addKv(LinearLayout box, CharSequence label, String value) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(4), 0, dp(4));
        TextView l = new TextView(act); Language.setText(l, label); l.setTextColor(Design.dim()); l.setTextSize(13f);
        TextView v = new TextView(act); Language.setText(v, value); v.setTextColor(Design.text()); v.setTextSize(13f); v.setGravity(Gravity.END);
        r.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f));
        box.addView(r);
    }

    private TextView sectionLabel(CharSequence s) {
        TextView t = new TextView(act);
        Language.setText(t, s); t.setAllCaps(true); t.setTextColor(Design.dim()); t.setTextSize(11f);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private LinearLayout linkRow(CharSequence label, String url) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(8), 0, dp(8));
        TextView l = new TextView(act); Language.setText(l, label); l.setTextColor(Design.dim()); l.setTextSize(13f);
        TextView v = new TextView(act); Language.setText(v, Language.msg(l10n_utxo_open)); v.setTextColor(Design.accentText()); v.setTextSize(13f); v.setGravity(Gravity.END);
        r.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.setOnClickListener(x -> {
            try { act.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
            catch (Exception ignore) {}
        });
        return r;
    }

    private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

    private static boolean isHttp(String u) {
        return u != null && (u.regionMatches(true, 0, "http://", 0, 7) || u.regionMatches(true, 0, "https://", 0, 8));
    }

    /** True if the amount string parses to a strictly positive number; false on null/garbage. */
    private boolean positive(String amt) {
        try { return new BigDecimal(amt).signum() > 0; } catch (Exception e) { return false; }
    }

    /** Whole-number format with thousands separators and no decimals (e.g. 999,978,181). */
    private static String wholeNumber(String amt) {
        try {
            java.math.BigInteger bi = new BigDecimal(amt).toBigInteger();
            return java.text.NumberFormat.getInstance(java.util.Locale.US).format(bi);
        } catch (Exception e) { return amt; }
    }

    /** Contract-locked / watch-only balance = max(0, confirmed − sendable); "0" on parse failure. */
    private static String lockedAmount(TokenBalance b) {
        try {
            BigDecimal l = new BigDecimal(b.confirmed).subtract(new BigDecimal(b.sendable));
            return l.signum() > 0 ? l.stripTrailingZeros().toPlainString() : "0";
        } catch (Exception e) { return "0"; }
    }

    /** Converts density-independent pixels to raw pixels for this device. */
    private int dp(int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density);
    }
}
