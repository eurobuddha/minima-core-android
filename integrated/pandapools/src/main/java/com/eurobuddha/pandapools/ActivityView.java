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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Activity tab — two scopes (borrowed from MinimaSwap's my-swaps / market toggle):
 *   • MY ACTIVITY — this device's actions with a live lifecycle (in-flight {@link ActivityLog} entries that
 *     say "Confirming n/3…" the moment you post, then the CONFIRMED on-chain transactions from the node's
 *     own history, persisted permanently in {@link HistoryDb} and synced via {@link HistorySync}).
 *   • ALL POOLS — a live feed of every swap hitting the pools (incl. other people's), from {@link GlobalFeed}.
 *
 * Rows + tap-detail mirror the standalone Minima History app. Re-renders instantly from local stores; a
 * foreground poll keeps the "Confirming n/3" countdown + global feed fresh without a tab-switch.
 */
public class ActivityView extends BaseView {

    private static final int SHOW_STEP = 60;
    private int shown = SHOW_STEP;

    private final LinearLayout container;
    private final TextView personalTab, globalTab, refreshTv, statusTv;
    private final HistorySync sync;
    private final PoolHistorySync publicSync;
    private boolean showGlobal = false;
    private boolean polling = false;

    // Every pool covenant address this device has ever seen. MY ACTIVITY keeps only confirmed on-chain rows
    // that touch one of these (and moved my wallet) — so the tab shows my pool activity, not the whole
    // node-wallet history. See PandaAddrBook for the persistence + never-shrink contract; the accounting
    // export shares the same set.
    private final PandaAddrBook addrBook;

    // The shared pool scan feeds the global lifecycle feed (creates/swaps/adds/withdrawals) and keeps the
    // known-address set current. GlobalFeed diffs each scan's reserves against the previous one.
    private final PoolBook.Listener poolListener = new PoolBook.Listener() {
        @Override public void onPools(List<Pool> pools) {
            boolean added = addrBook.addAll(pools);
            if (added) addrBook.persist();
            if (publicSync != null) {
                publicSync.livePools(pools);
                if (visible() && showGlobal) publicSync.start(false);
            }
            // Discovery updates known addresses only. Reserve snapshots are not transaction evidence.
            // repaint ALL POOLS for the new feed rows, or MY ACTIVITY if a newly-known address may now match
            if (visible() && (showGlobal || added)) scheduleRender();
        }
        @Override public void onError(String msg) {}
    };

    public ActivityView(MainActivity a) {
        super(a, R.layout.pm_pandapools_view_activity);
        addrBook    = new PandaAddrBook(a);   // before subscribe() — the pool listener uses it
        container   = find(R.id.pm_pandapools_actContainer);
        personalTab = find(R.id.pm_pandapools_actPersonal);
        globalTab   = find(R.id.pm_pandapools_actGlobal);
        refreshTv   = find(R.id.pm_pandapools_actRefresh);
        statusTv    = find(R.id.pm_pandapools_actStatus);
        a.pools().subscribe(poolListener);
        sync = new HistorySync(a, a.history(), syncListener);
        addrBook.seed();
        publicSync = new PoolHistorySync(a, addrBook, this::scheduleRender);

        refreshTv.setTextColor(Design.accent());
        Language.description(refreshTv, Language.msg(l10n_pools_refresh_activity));
        statusTv.setTextColor(Design.dim());
        personalTab.setOnClickListener(v -> setScope(false));
        globalTab.setOnClickListener(v -> setScope(true));
        refreshTv.setOnClickListener(v -> { syncPersonal(); scanGlobal(); if (showGlobal) publicSync.start(true); });
        styleTabs();
        render();
    }

    private void setScope(boolean global) {
        if (showGlobal == global) return;
        showGlobal = global; shown = SHOW_STEP; styleTabs(); render();
        syncPersonal(); if (global) { scanGlobal(); publicSync.start(true); }
    }

    private void styleTabs() {
        Ui.chip(personalTab, !showGlobal);
        Ui.chip(globalTab, showGlobal);
    }

    @Override public void refresh() { render(); }
    @Override public void onShown() { addrBook.seed(); syncPersonal(); scanGlobal(); if (showGlobal) publicSync.start(false); render(); startPoll(); }
    @Override public void onNewBlock() { syncPersonal(); if (visible()) scheduleRender(); }   // shared per-block scan (MainActivity) feeds the global feed
    @Override public void onStop() { stopPoll(); }
    @Override public void onDestroy() { stopPoll(); publicSync.close(); act.pools().unsubscribe(poolListener); }

    private boolean visible() { return act.currentTab() == MainActivity.TAB_ACTIVITY; }
    /** Coalesce bursty re-renders (per history page, per block) into one, and only while visible. */
    private final Runnable renderTask = this::render;
    private void scheduleRender() { container.removeCallbacks(renderTask); container.postDelayed(renderTask, 150); }

    // ---- data refresh ----

    private void syncPersonal() { if (act.node() != null && !sync.isRunning()) sync.start(); }

    private void scanGlobal() { act.pools().requestNow(poolListener); }

    // ---- personal PandaPools filter ----

    /** A confirmed on-chain row belongs in MY ACTIVITY iff it moved my wallet (non-zero net → not "self")
     *  AND it touches a known PandaPools address. A stranger's swap on a pool I track nets to zero for me
     *  (direction "self") and is excluded; so are plain wallet sends / other dapps (no panda address). */
    private boolean isPersonalPanda(HistoryEntry n) {
        if (n == null) return false;
        if (n.isConsolidation()) return true;
        if ("self".equals(n.direction)) return false;
        return addrBook.hits(n.inputs) || addrBook.hits(n.outputs);
    }

    private final HistorySync.Listener syncListener = new HistorySync.Listener() {
        @Override public void onProgress(int totalNew) { act.runOnUiThread(() -> { if (visible()) scheduleRender(); }); }
        @Override public void onDone(int totalNew, boolean ok) { act.runOnUiThread(() -> { if (visible()) scheduleRender();
            ActivityLog.verify(act, act.node(), act::confirmationsChanged);
            if (showGlobal) publicSync.start(false);
        }); }
    };

    /** Foreground fast-poll so the "Confirming n/3" countdown + global feed self-update. Stops when the
     *  tab isn't visible or the Activity is stopped/destroyed (so it can't leak or reopen a closed DB). */
    private void startPoll() {
        if (polling) return;
        polling = true;
        act.ui().postDelayed(pollTask, 10000);
    }
    private void stopPoll() {
        polling = false;
        ActivityLog.visibleTransactions(java.util.Collections.emptyList());
        act.ui().removeCallbacks(pollTask);
        container.removeCallbacks(renderTask);
    }
    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            polling = false;
            if (!visible()) return;   // left the tab / backgrounded → stop (onShown restarts it)
            ActivityLog.verify(act, act.node(), act::confirmationsChanged);
            if (showGlobal) publicSync.start(false);
            render();
            startPoll();
        }
    };

    // ---- render ----

    private void render() {
        root.setBackgroundColor(Design.bg());
        container.setBackgroundColor(Design.bg());
        CharSequence state = PoolsMessages.message(ActivityLog.checkStatus());
        CharSequence publicState = PoolsMessages.message(publicSync.status());
        if (publicState.length() > 0) state = Language.join(publicState, state.length() == 0 ? "" : Language.join(" · ", state));
        if (sync.isRunning()) state = Language.join(Language.msg(l10n_pools_syncing_history), state.length() == 0 ? "" : Language.join(" · ", state));
        Language.setText(statusTv, state); statusTv.setVisibility(state.length() == 0 ? View.GONE : View.VISIBLE);
        container.removeAllViews();
        if (showGlobal) renderGlobal(); else renderPersonal();
    }

    private List<HistoryEntry> storedHistory() {
        List<HistoryEntry> rows = act.history().listChronological(0, Long.MAX_VALUE);
        java.util.Collections.reverse(rows);
        return rows;
    }

    private void renderPersonal() {
        List<ActivityTimeline.Row> rows = ActivityTimeline.merge(ActivityLog.list(act), storedHistory(), this::isPersonalPanda);
        if (rows.isEmpty()) {
            empty(sync.isRunning() ? Language.msg(l10n_pools_loading_history) : Language.msg(l10n_pools_no_transactions));
            return;
        }
        container.addView(header(Language.msg(l10n_pools_newest_transactions)));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < Math.min(shown, rows.size()); i++) {
            ActivityTimeline.Row row = rows.get(i);
            ids.add(row.receipt != null ? row.receipt.txpowid : row.history.txpowid);
            container.addView(row.receipt != null
                    ? pendingCard(row.receipt, act.chainBlock(), row.history, row.time) : historyRow(row.history));
        }
        if (visible()) ActivityLog.visibleTransactions(ids);
        showMore(rows.size());
    }

    private void renderGlobal() {
        List<PoolActivity.Event> events = new ArrayList<>();
        java.util.Map<String, HistoryEntry> all = new java.util.LinkedHashMap<>();
        for (HistoryEntry tx : act.history().publicList(-1, 0)) all.put(tx.txpowid.toLowerCase(Locale.ROOT), tx);
        for (HistoryEntry tx : storedHistory()) all.put(tx.txpowid.toLowerCase(Locale.ROOT), tx);
        for (HistoryEntry tx : all.values()) events.addAll(PoolActivity.from(tx, addrBook));
        events.sort((a, b) -> Long.compare(b.transaction.timemilli, a.transaction.timemilli));
        container.addView(header(Language.msg(l10n_pools_newest_pool_transactions)));
        if (events.isEmpty()) container.addView(line(sync.isRunning()
                ? Language.msg(l10n_pools_loading_node_transactions) : Language.msg(l10n_pools_no_pool_transactions),
                Design.dim(), 12f, false));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < Math.min(shown, events.size()); i++) {
            container.addView(poolTransactionRow(events.get(i)));
            ids.add(events.get(i).transaction.txpowid);
        }
        if (visible()) ActivityLog.visibleTransactions(ids);
        showMore(events.size());
        // Preserve the old observations, but never present their device-local time as a transaction time.
        List<GlobalFeed.Event> observations = GlobalFeed.list(act);
        if (!observations.isEmpty()) {
            container.addView(header(Language.msg(l10n_pools_previous_observations)));
            container.addView(line(Language.msg(l10n_pools_observations_note),
                    Design.dim(), 12f, false));
            for (GlobalFeed.Event observation : observations) container.addView(globalRow(observation));
        }
    }

    private void showMore(int total) {
        if (total <= shown) return;
        TextView more = line(Language.msg(l10n_pools_show_more, total - shown), Design.accent(), 13f, true);
        more.setGravity(Gravity.CENTER); more.setPadding(0, dp(12), 0, dp(12));
        more.setMinHeight(dp(48));
        more.setOnClickListener(v -> { shown += SHOW_STEP; render(); });
        container.addView(more);
    }

    private View poolTransactionRow(PoolActivity.Event event) {
        HistoryEntry tx = event.transaction;
        String tokenName = Util.tokenNameCached(event.tokenid);
        if (tokenName == null || tokenName.isEmpty()) tokenName = event.tokenid.equals(tx.tokenid)
                ? tx.tokenName : event.tokenid;
        CharSequence action;
        switch (event.kind) {
            case GlobalFeed.CREATE: action = Language.msg(l10n_pools_pool_creation); break;
            case GlobalFeed.ADD: action = Language.msg(l10n_pools_liquidity_addition); break;
            case GlobalFeed.WITHDRAW: action = Language.msg(l10n_pools_liquidity_withdrawal); break;
            case GlobalFeed.SWAP: action = event.minimaIn ? Language.msg(l10n_pools_minima_sale) : Language.msg(l10n_pools_minima_purchase); break;
            default: action = Language.msg(l10n_pools_reserves_changed);
        }
        LinearLayout row = new LinearLayout(act); row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(12), dp(8), dp(12));
        row.addView(line(Language.join(action, " · ", trim(event.minima), " MINIMA / ", trim(event.token), " ", tokenName),
                Design.text(), 14f, true));
        row.addView(line(Language.join(event.pool, " · ", Language.msg(l10n_pools_transaction_when, absolute(tx.timemilli))), Design.dim(), 12f, false));
        row.addView(line(tx.verifiedAt == 0 ? Language.msg(l10n_pools_waiting_node_check) : PoolsMessages.confirmation(tx.verifiedDepth),
                tx.verifiedDepth >= ActivityLog.CONFIRM_BLOCKS ? Design.success() : Design.amber(), 11f, false));
        if (tx.verifiedAt > 0) row.addView(line(Language.msg(l10n_pools_checked_when, relative(tx.verifiedAt)), Design.dim(), 11f, false));
        row.setOnClickListener(v -> showDetail(tx));
        return row;
    }

    // ---- rows ----

    /** An in-flight / just-posted action (from the local ActivityLog). */
    private View pendingCard(ActivityLog.Entry e, int cb, HistoryEntry history, long eventTime) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        Ui.card(card);
        int pad = dp(12);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8); card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = line(prettyType(e.type), Design.heading(), 14f, true);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(title);
        top.addView(statusChip(PoolsMessages.activityStatus(e, cb), e.failed ? Design.red() : e.confirmed(cb) ? Design.success() : Design.amber()));
        card.addView(top);

        card.addView(line(PoolsMessages.activitySummary(e), Design.text(), 13f, false));
        CharSequence meta = history != null && history.timemilli > 0
                ? Language.msg(l10n_pools_transaction_when, absolute(eventTime)) : Language.msg(l10n_pools_submitted_when, absolute(eventTime));
        if (e.failed && e.failMsg != null && !e.failMsg.isEmpty()) meta = Language.join(meta, " · ", PoolsMessages.message(e.failMsg));
        else if (e.txpowid != null && !e.txpowid.isEmpty()) meta = Language.join(e.txpowid, "  ·  ", meta);
        TextView sub = line(meta, Design.dim(), 12f, false);
        sub.setPadding(0, dp(3), 0, 0);
        card.addView(sub);
        if (e.verifiedAt > 0) card.addView(line(Language.msg(l10n_pools_checked_when, relative(e.verifiedAt)), Design.dim(), 11f, false));
        card.setOnClickListener(v -> act.languageDialog()
                .setTitle(PoolsMessages.activityStatus(e, cb))
                .setMessage(Language.join(
                        Language.msg(l10n_pools_receipt_txpow, e.txpowid == null ? Language.msg(l10n_pools_value_unavailable) : e.txpowid),
                        e.originalTxpowid != null && !e.originalTxpowid.equalsIgnoreCase(e.txpowid)
                                ? Language.join("\n\n", Language.msg(l10n_pools_original_id, e.originalTxpowid)) : "",
                        "\n\n", history != null ? Language.msg(l10n_pools_transaction_time, absolute(eventTime)) : Language.msg(l10n_pools_submitted_time, absolute(eventTime)),
                        "\n\n", Language.msg(l10n_pools_transaction_id_detail, e.transactionId.isEmpty() ? Language.msg(l10n_pools_older_id_missing) : e.transactionId),
                        e.verifiedAt > 0 ? Language.join("\n\n", Language.msg(l10n_pools_last_node_check, Language.stamp(e.verifiedAt, "yMMMdHmsz"))) : "",
                        e.transactionId.isEmpty() && e.verifiedDepth < 0 ? Language.join("\n\n", Language.msg(l10n_pools_receipt_match_note)) : ""))
                .setPositiveButton(Language.msg(l10n_pools_ok), null).show());
        return card;
    }

    /** A confirmed on-chain transaction (from the node history) — identical shape to the History app. */
    private View historyRow(final HistoryEntry n) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(11), dp(8), dp(11));

        int color = "received".equals(n.direction) ? 0xFF1EA85A
                : "sent".equals(n.direction) ? Design.red() : Design.dim();
        String g = "received".equals(n.direction) ? "↓" : "sent".equals(n.direction) ? "↑" : "⟲";
        String sign = n.incoming ? "+" : "sent".equals(n.direction) ? "−" : "";

        TextView glyph = new TextView(act);
        glyph.setText(g); glyph.setTextColor(color); glyph.setTextSize(18f); glyph.setWidth(dp(28));
        row.addView(glyph);

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(6), 0, dp(6), 0);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        boolean reshuffle = n.isReshuffle();
        String swapLegs = n.swapLegsDisplay();   // both legs when this tx is a two-token swap
        TextView line1 = new TextView(act);
        Language.setText(line1, swapLegs != null ? swapLegs
                : reshuffle ? n.grossDisplay()
                : (sign + Util.tidyAmount(n.amount) + "  " + n.tokenName));
        line1.setTextColor(color); line1.setTextSize(15f); line1.setTypeface(Design.typefaceBold());
        TextView line2 = new TextView(act);
        String cp = (n.counterparty == null || n.counterparty.isEmpty()) ? "" : n.counterparty + "  ·  ";
        Language.setText(line2, Language.join(reshuffle ? Language.join(PoolsMessages.reshuffle(n), "  ·  ") : cp, absolute(n.timemilli)));
        line2.setTextColor(Design.dim()); line2.setTextSize(12f);
        mid.addView(line1); mid.addView(line2);
        mid.addView(line(n.verifiedAt == 0 ? Language.msg(l10n_pools_waiting_node_check) : PoolsMessages.confirmation(n.verifiedDepth),
                n.verifiedDepth >= ActivityLog.CONFIRM_BLOCKS ? Design.success() : Design.amber(), 11f, false));
        if (n.verifiedAt > 0) mid.addView(line(Language.msg(l10n_pools_checked_when, relative(n.verifiedAt)), Design.dim(), 10f, false));
        row.addView(mid);

        TextView right = new TextView(act);
        right.setText("#" + n.block); right.setTextColor(Design.dim()); right.setTextSize(11f); right.setGravity(Gravity.END);
        row.addView(right);

        row.setOnClickListener(v -> showDetail(n));
        return row;
    }

    /** A global (any-trader) pool lifecycle event detected from reserve movement — create / swap / add /
     *  withdraw. */
    private View globalRow(GlobalFeed.Event ev) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(11), dp(8), dp(11));

        String m = trim(ev.minimaAmt), t = trim8(ev.tokenAmt), tl = ev.tokenLabel;
        String glyphStr;
        CharSequence desc, line2Str;
        int glyphColor;
        CharSequence poolTime = Language.msg(l10n_pools_observation_time, ev.pool, absolute(ev.ts));
        switch (ev.kind) {
            case GlobalFeed.CREATE:
                glyphStr = "✦"; glyphColor = Design.success();
                desc = Language.join(Language.msg(l10n_pools_observed_create), " · ", m, " MINIMA / ", t, " ", tl);
                line2Str = poolTime;
                break;
            case GlobalFeed.ADD:
                glyphStr = "+"; glyphColor = Design.success();
                desc = Language.join(Language.msg(l10n_pools_observed_add), " · +", m, " MINIMA / +", t, " ", tl);
                line2Str = poolTime;
                break;
            case GlobalFeed.WITHDRAW:
                glyphStr = "−"; glyphColor = Design.red();
                desc = Language.join(Language.msg(l10n_pools_observed_withdraw), " · ", m, " MINIMA / ", t, " ", tl);
                line2Str = poolTime;
                break;
            default:   // SWAP
                glyphStr = "⇄"; glyphColor = Design.accent();
                desc = ev.minimaIn ? (Language.join(Language.msg(l10n_pools_observed_sold), " ", m, " MINIMA  →  ", t, " ", tl))
                                   : (Language.join(Language.msg(l10n_pools_observed_bought), " ", m, " MINIMA  ←  ", t, " ", tl));
                line2Str = Language.join(trim8(ev.price), " ", tl, "/MINIMA  ·  ", poolTime);
        }

        TextView glyph = new TextView(act);
        glyph.setText(glyphStr); glyph.setTextColor(glyphColor); glyph.setTextSize(17f); glyph.setWidth(dp(28));
        row.addView(glyph);

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(6), 0, dp(6), 0);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView line1 = new TextView(act);
        Language.setText(line1, desc); line1.setTextColor(Design.text()); line1.setTextSize(14f); line1.setTypeface(Design.typefaceBold());
        TextView line2 = new TextView(act);
        Language.setText(line2, line2Str);
        line2.setTextColor(Design.dim()); line2.setTextSize(12f);
        mid.addView(line1); mid.addView(line2);
        row.addView(mid);
        return row;
    }

    // ---- detail dialog (mirrors the History app) ----

    private void showDetail(HistoryEntry n) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));
        kv(box, Language.msg(l10n_history_direction), PoolsMessages.direction(n.direction));
        kv(box, Language.msg(l10n_history_amount), (n.incoming ? "+" : "sent".equals(n.direction) ? "−" : "") + Util.tidyAmount(n.amount) + " " + n.tokenName);
        kv(box, Language.msg(l10n_history_block), String.valueOf(n.block));
        kv(box, Language.msg(l10n_history_date_time), Language.stamp(n.timemilli, "yMMMdHms"));
        copyRow(box, "Txpow id", n.txpowid);
        if (n.counterparty != null && !n.counterparty.isEmpty()) copyRow(box, n.incoming ? "From" : "To", n.counterparty);
        addBreakdown(box, Language.msg(l10n_history_inputs), n.inputs);
        addBreakdown(box, Language.msg(l10n_history_outputs), n.outputs);
        ScrollView sv = new ScrollView(act);
        sv.addView(box);
        act.languageDialog().setTitle(Language.msg(l10n_history_transaction)).setView(sv).setPositiveButton(Language.msg(l10n_language_close), null).show();
    }

    private void kv(LinearLayout p, CharSequence k, CharSequence v) {
        TextView t = new TextView(act);
        Language.setText(t, Language.join(k, ":  ", v));
        t.setTextColor(Design.text()); t.setTextSize(13f); t.setPadding(0, dp(4), 0, dp(4));
        p.addView(t);
    }

    private void copyRow(LinearLayout p, String k, final String v) {
        p.addView(Ui.identifierRow(act, k, v));
    }

    private void addBreakdown(LinearLayout p, CharSequence title, String json) {
        try {
            JSONArray a = new JSONArray(json);
            if (a.length() == 0) return;
            TextView h = new TextView(act);
            Language.setText(h, title);
            h.setTextColor(Design.accent()); h.setTextSize(12f); h.setTypeface(Design.typefaceBold()); h.setPadding(0, dp(8), 0, dp(2));
            p.addView(h);
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                String tid = c.optString("tokenid", "0x00");
                String tok = Util.isMinima(tid) ? "Minima" : tid;
                TextView t = new TextView(act);
                Language.setText(t, "• " + Util.tidyAmount(c.optString("amount", "")) + " " + tok + "  →  " + c.optString("addr", ""));
                t.setTextColor(Design.dim()); t.setTextSize(12f); t.setPadding(dp(6), dp(1), 0, dp(1));
                p.addView(t);
            }
        } catch (Exception ignored) {}
    }

    // ---- helpers ----

    private TextView header(CharSequence s) {
        TextView t = new TextView(act);
        Language.setText(t, s); t.setAllCaps(true); t.setTextColor(Design.dim()); t.setTextSize(11f);
        t.setLetterSpacing(0.1f); t.setPadding(dp(2), dp(12), 0, dp(4));
        return t;
    }

    private void empty(CharSequence s) {
        TextView t = new TextView(act);
        Language.setText(t, s); t.setTextColor(Design.dim()); t.setGravity(Gravity.CENTER); t.setPadding(dp(8), dp(48), dp(8), 0);
        container.addView(t);
    }

    private TextView statusChip(CharSequence s, int color) {
        TextView t = new TextView(act);
        Language.setText(t, s); t.setTextColor(color); t.setTextSize(11f); t.setTypeface(Design.typefaceBold());
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        t.setMaxWidth(dp(160));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Ui.withAlpha(color, 30)); g.setCornerRadius(dp(100)); g.setStroke(Math.max(1, dp(1)), Ui.withAlpha(color, 120));
        t.setBackground(g);
        return t;
    }

    private TextView line(CharSequence s, int color, float sp, boolean bold) {
        TextView t = new TextView(act);
        Language.setText(t, s); t.setTextColor(color); t.setTextSize(sp);
        t.setTypeface(bold ? Design.typefaceBold() : Design.typeface());
        return t;
    }

    private static CharSequence prettyType(String type) {
        switch (type) {
            case ActivityLog.CONSOLIDATE: return Language.msg(l10n_pools_consolidate_coins);
            case ActivityLog.CREATE:  return Language.msg(l10n_pools_create_pool);
            case ActivityLog.SWAP:    return Language.msg(l10n_pools_swap_action);
            case ActivityLog.DEPOSIT: return Language.msg(l10n_pools_add_liquidity);
            case ActivityLog.MIGRATE: return Language.msg(l10n_pools_migrate_pool);
            case ActivityLog.CLOSE:   return Language.msg(l10n_pools_withdraw_close);
            default:                  return type;
        }
    }

    private static CharSequence absolute(long ms) {
        return ms <= 0 ? Language.msg(l10n_pools_unknown) : Language.stamp(ms, "yMMMdHmsz");
    }
    private static CharSequence relative(long ms) {
        if (ms <= 0) return "";
        return System.currentTimeMillis() - ms < 7 * 86400000L ? Language.since(ms) : Language.stamp(ms, "yMMMd");
    }

    private static String trim(BigDecimal b) { return b == null ? "—" : b.stripTrailingZeros().toPlainString(); }
    private static String trim8(BigDecimal b) { return b == null ? "—" : b.setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString(); }
    private int dp(int v) { return Math.round(v * act.getResources().getDisplayMetrics().density); }
}
