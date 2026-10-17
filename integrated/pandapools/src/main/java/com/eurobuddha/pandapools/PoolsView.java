package com.eurobuddha.pandapools;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** The Pools tab: discovers every live pool from the registry and lists each with its reserves, spot
 *  price, and product growth. Read-only discovery — swapping lives in the Swap tab, LP in My LP.
 *  An Individual/Combined toggle re-renders the SAME cached pools either as the per-pool list or as one
 *  collective-pool card per token (summed reserves + aggregate price + count + depth — reused from the
 *  router's own aggregation, no rescan, no new pool math). */
public class PoolsView extends BaseView {

    private final TextView indivTab, combinedTab;
    private boolean combined = false;                       // false = per-pool list | true = collective view
    private List<Pool> lastPools = Collections.emptyList(); // cached scan result, re-rendered on toggle (no rescan)

    // Receives the shared PoolRepository's scan result (one scan for the whole app, not one per tab).
    private final PoolBook.Listener poolListener = new PoolBook.Listener() {
        @Override public void onPools(List<Pool> pools) { render(pools); }
        @Override public void onError(String msg) { status(Language.msg(l10n_pools_scan_error, msg)); }
    };

    public PoolsView(MainActivity a) {
        super(a, R.layout.pm_pandapools_view_pools);
        a.pools().subscribe(poolListener);
        TextView refresh = find(R.id.pm_pandapools_poolsRefresh);
        refresh.setTextColor(Design.accent());
        refresh.setOnClickListener(v -> refresh());
        ((TextView) find(R.id.pm_pandapools_poolsSummary)).setTextColor(Design.dim());
        ((TextView) find(R.id.pm_pandapools_poolsStatus)).setTextColor(Design.dim());
        indivTab = find(R.id.pm_pandapools_poolsIndivTab);
        combinedTab = find(R.id.pm_pandapools_poolsCombinedTab);
        indivTab.setOnClickListener(v -> setMode(false));
        combinedTab.setOnClickListener(v -> setMode(true));
        styleTabs();
        status(Language.msg(l10n_pools_scanning));
    }

    private void setMode(boolean comb) {
        if (combined == comb) return;
        combined = comb;
        styleTabs();
        render(lastPools);                                 // re-render cached pools; never triggers a node scan
    }
    private void styleTabs() {
        Ui.chip(indivTab, !combined);
        Ui.chip(combinedTab, combined);
    }

    @Override public void refresh() { act.pools().requestNow(poolListener); }
    @Override public void onShown() { act.pools().requestNow(poolListener); }
    @Override public void onDestroy() { act.pools().unsubscribe(poolListener); }

    private void render(List<Pool> pools) {
        lastPools = pools;
        LinearLayout list = find(R.id.pm_pandapools_poolList);
        list.removeAllViews();
        TextView summary = find(R.id.pm_pandapools_poolsSummary);
        if (pools.isEmpty()) {
            Language.setText(summary, Language.msg(l10n_pools_no_active));
            status(Language.msg(l10n_pools_no_live));
            return;
        }
        BigDecimal aggMinima = VirtualCurve.totalMinima(pools);
        Language.setText(summary, Language.count(com.eurobuddha.minimacore.i18n.R.plurals.l10n_pools_summary, pools.size(), pools.size(), trim(aggMinima)));
        status("");
        find(R.id.pm_pandapools_poolsStatus).setVisibility(View.GONE);
        if (combined) { renderCombined(list, pools); return; }
        for (Pool p : pools) list.addView(poolCard(p));
    }

    /** Combined view — one collective-pool card per token (aggregate ALL pools of a token). Groups by token id
     *  because MINIMA/USDT can't be summed with a MINIMA/otherToken pair; reuses VirtualCurve/PoolRouter aggregates. */
    private void renderCombined(LinearLayout list, List<Pool> pools) {
        LinkedHashMap<String, List<Pool>> groups = new LinkedHashMap<>();
        for (Pool p : pools) if (p.funded()) {
            List<Pool> g = groups.get(p.tok);
            if (g == null) { g = new ArrayList<>(); groups.put(p.tok, g); }
            g.add(p);
        }
        if (groups.isEmpty()) { status(Language.msg(l10n_pools_no_funded)); return; }
        for (List<Pool> g : groups.values()) list.addView(combinedCard(g));
    }

    private View combinedCard(List<Pool> g) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        Ui.card(card);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        String tl = g.get(0).tokenLabel();
        BigDecimal totM = VirtualCurve.totalMinima(g);       // summed MINIMA side
        BigDecimal price = VirtualCurve.aggregatePrice(g);   // reserve-weighted token per MINIMA
        BigDecimal totT = totM.multiply(price);              // summed token side (derived from the two aggregates)
        BigDecimal depth = PoolRouter.aggregateDepth(g);     // MINIMA depth actually routable (deepest MAX_POOLS)
        card.addView(line(Language.msg(l10n_pools_combined_title, tl), Design.accent(), 15, true));
        card.addView(kv(Language.msg(l10n_pools_combined_count), String.valueOf(g.size())));
        card.addView(kv(Language.msg(l10n_pools_total_reserves), trim(totM) + " MINIMA  ·  " + trim(totT) + " " + tl));
        card.addView(kv(Language.msg(l10n_pools_aggregate_price), trim8(price) + " " + tl + " / MINIMA"));
        card.addView(kv(Language.msg(l10n_pools_depth), trim(depth) + " MINIMA"));
        return card;
    }

    private View poolCard(Pool p) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        Ui.card(card);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        String tl = p.tokenLabel();
        card.addView(line("MINIMA / " + tl, Design.heading(), 15, true));
        card.addView(kv(Language.msg(l10n_pools_reserves), trim(p.reserveM) + " MINIMA  ·  " + trim(p.reserveT) + " " + tl));
        card.addView(kv(Language.msg(l10n_pools_spot_price), trim8(p.spotPrice()) + " " + tl + " / MINIMA"));
        BigDecimal fg = p.feeGrowth().multiply(new BigDecimal("100"));
        card.addView(kv(Language.msg(l10n_pools_fees), trim8(fg) + " %"));
        // Address is shown truncated but the FULL value is one tap away (copied verbatim to the clipboard) —
        // never leave an identifier with no way to recover it in full.
        final String fullAddr = p.address;
        View poolRow = kv(Language.msg(l10n_pools_pool), Language.msg(l10n_pools_copy_value, p.address));   // full covenant address: it is what an explorer needs
        Ui.copyable(poolRow, "pool address", fullAddr);
        card.addView(poolRow);
        return card;
    }

    private TextView line(CharSequence s, int color, int sp, boolean bold) {
        TextView t = new TextView(act);
        Language.setText(t, s); t.setTextColor(color); t.setTextSize(sp);
        t.setTypeface(bold ? Design.typefaceBold() : Design.typeface());
        return t;
    }

    private View kv(CharSequence k, CharSequence v) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, 0);
        TextView kk = new TextView(act);
        Language.setText(kk, k); kk.setTextColor(Design.dim()); kk.setTextSize(12);
        // Label keeps its natural width (capped), NOT weight-1: a weighted label was starved to ~1 char
        // (and wrapped one glyph per line) whenever the value string filled the row.
        kk.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        kk.setMaxWidth(dp(190));
        TextView vv = new TextView(act);
        Language.setText(vv, v); vv.setTextColor(Design.text()); vv.setTextSize(12);
        vv.setTypeface(Typeface.MONOSPACE);
        vv.setGravity(Gravity.END);
        vv.setSingleLine(false);   // a long value wraps within its own column instead of overflowing it
        // Value takes the remaining width (weight 1) and right-aligns; small gap from the label.
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vlp.leftMargin = dp(8);
        vv.setLayoutParams(vlp);
        row.addView(kk); row.addView(vv);
        return row;
    }

    private void status(CharSequence s) {
        TextView st = find(R.id.pm_pandapools_poolsStatus);
        st.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
        Language.setText(st, s);
    }

    private static String trim(BigDecimal b) {
        if (b == null) return "—";
        return b.stripTrailingZeros().toPlainString();
    }
    private static String trim8(BigDecimal b) {
        if (b == null) return "—";
        return b.setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }
    private int dp(int v) { return Math.round(v * act.getResources().getDisplayMetrics().density); }
}
