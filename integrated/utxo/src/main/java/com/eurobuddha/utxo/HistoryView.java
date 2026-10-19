package com.eurobuddha.utxo;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * History tab — a faithful mirror of the standalone Minima History app: the SAME rows (↓received /
 * ↑sent / ⟲self glyph + signed amount + token, counterparty · time, #block) and the SAME tap-detail
 * (per-token effect + full inputs/outputs breakdown). Direction + amount come from the node's
 * details.difference (net per-token effect on the wallet). On-chain history is persisted (nodetx),
 * so it accumulates and shows instantly + offline; pages are adaptive to stay under the node's 256 KB cap.
 */
public class HistoryView extends BaseView {

    private static final int CAP = 1000;       // rows kept + shown
    private static final String EXPLORER_TX = "https://explorer.minima.global/transactions/";

    private final LinearLayout container;
    private static final int PAGE_MAX = 8;      // node page size ceiling
    private static final int SHOW_STEP = 60;    // rows rendered per "Show more" (render cost, not fetch)
    private int pageMax = PAGE_MAX;             // adaptive page size — shrinks ONLY under the 256 KB cap
    private int shrunkAtBlock = -1;             // block at which pageMax last shrank (recovery is tried on a later block)
    private boolean fetching = false;
    private int lastFetchBlock = -1;
    private boolean moreAvailable = false;
    private int shown = SHOW_STEP;              // how many persisted rows are currently rendered
    private static final int AUTO_PAGES_MAX = 5; // extra pages fetched on their own while a send is still "posted"
    private int autoPages = 0;
    // Loaded NodeTx rows survive between renders (render runs every block while visible), so NodeTx's
    // per-instance classification caches actually pay off. Invalidated when a page adds rows or the window grows.
    private List<NodeTx> rowsCache = null;
    private int rowsCacheShown = -1;
    private boolean rowsDirty = true;

    public HistoryView(MainActivity a) {
        super(a, R.layout.pm_utxo_view_history);
        container = find(R.id.pm_utxo_historyContainer);
        render();   // show whatever is already persisted, instantly + offline
    }

    @Override public void refresh() { rowsDirty = true; if (visible() && !fetching) render(); }
    @Override public void onShown() { autoPages = 0; act.history().resolveOpenAgainstStored(); render(); fetch(true); }
    @Override public void onNewBlock() { if (visible()) fetch(false); }
    private boolean visible() { return act.currentTab() == MainActivity.TAB_HISTORY; }

    private void fetch(boolean force) {
        if (fetching) return;
        if (!force && act.chainBlock() <= lastFetchBlock) return;
        // Gentle recovery: a page size shrunk under the reply cap grows back one step per later block,
        // so one oversized page (or a transient shrink) doesn't pin History to 1-tx pages for the session.
        if (pageMax < PAGE_MAX && act.chainBlock() > shrunkAtBlock) pageMax = Math.min(PAGE_MAX, pageMax * 2);
        fetchPage(0);
    }

    private void loadOlder() { if (!fetching) fetchPage(act.history().nodeTxCount()); }

    /** One adaptive `history` page: shrink (8→4→2→1) + retry under the 256 KB cap; persist new rows; re-render. */
    private void fetchPage(final int offset) {
        fetching = true;
        act.node().cmd("history relevant:true max:" + pageMax + " offset:" + offset, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                JSONArray txpows = resp == null ? null : resp.optJSONArray("txpows");
                if (resp == null || txpows == null) {          // status:false arrives via onError since 0.4.9
                    fetching = false; render(); return;
                }
                fetching = false;
                lastFetchBlock = act.chainBlock();
                JSONArray details = resp.optJSONArray("details");
                int got = txpows.length();
                for (int i = 0; i < got; i++) {
                    JSONObject t = txpows.optJSONObject(i);
                    if (t == null || !t.optBoolean("istransaction", false)) continue;
                    NodeTx n = NodeTx.from(t, details != null && i < details.length() ? details.optJSONObject(i) : null);
                    if (!n.txpowid.isEmpty() && act.history().upsertNodeTx(n)) rowsDirty = true;
                }
                moreAvailable = got >= pageMax && act.history().nodeTxCount() < CAP;
                // A posted send whose txpow is older than this page: settle it from what is stored, and keep
                // paging (bounded) while something is still waiting — the user shouldn't have to tap
                // "Load older" to see a confirmed send stop saying "awaiting confirmation".
                act.history().resolveOpenAgainstStored();
                if (moreAvailable && autoPages < AUTO_PAGES_MAX && act.history().hasOpenWaiting()) {
                    autoPages++;
                    render();
                    fetchPage(act.history().nodeTxCount());
                    return;
                }
                render();
            }
            @Override public void onError(String message) {
                // Shrink + retry ONLY for the oversized-reply case. Any other error (node offline, not
                // enabled, rejected) must not ratchet the page size down nor chain immediate retries —
                // that used to cost four back-to-back 30 s timeouts and pin pageMax at 1 for the session.
                if (NodeApi.isTooLong(message) && pageMax > 1) {
                    pageMax = Math.max(1, pageMax / 2);
                    shrunkAtBlock = act.chainBlock();
                    fetchPage(offset);
                    return;
                }
                fetching = false; render();
            }
        });
    }

    // ----- render (identical to the History app's list) -----

    private void render() {
        root.setBackgroundColor(Design.bg());          // was showing dark in light mode
        container.setBackgroundColor(Design.bg());
        container.removeAllViews();
        // This wallet's own postings that aren't matched on-chain yet (or failed): always on top.
        List<HistoryRow> open = act.history().listOpen(200);
        for (HistoryRow r : open) container.addView(localRow(r));
        // Render a bounded window (rows are rebuilt on every block while visible) — "Show more" widens it
        // from the local cache; "Load older" fetches from the node once the cache is exhausted.
        if (rowsDirty || rowsCache == null || rowsCacheShown != shown) {
            rowsCache = act.history().loadNodeTx(shown);
            rowsCacheShown = shown;
            rowsDirty = false;
        }
        List<NodeTx> rows = rowsCache;
        int stored = act.history().nodeTxCount();
        if (rows.isEmpty() && open.isEmpty()) {
            TextView empty = new TextView(act);
            Language.setText(empty, fetching ? Language.msg(l10n_utxo_history_loading) : Language.msg(l10n_utxo_history_empty));
            empty.setTextColor(Design.dim());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, 0);
            container.addView(empty);
            return;
        }
        for (NodeTx n : rows) container.addView(row(n));
        if (stored > rows.size()) {
            TextView more = new TextView(act);
            Language.setText(more, Language.msg(l10n_utxo_history_more, stored - rows.size()));
            more.setTextColor(Design.accentText());
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, dp(12), 0, dp(8));
            more.setOnClickListener(v -> { shown = Math.min(CAP, shown + SHOW_STEP); render(); });
            container.addView(more);
        } else if (moreAvailable) {
            TextView more = new TextView(act);
            Language.setText(more, Language.msg(l10n_utxo_history_older));
            more.setTextColor(Design.accentText());
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, dp(12), 0, dp(8));
            more.setOnClickListener(v -> loadOlder());
            container.addView(more);
        }
    }

    private View row(final NodeTx n) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(11), dp(8), dp(11));

        int color = "received".equals(n.direction) ? 0xFF1EA85A          // green in / red out (both themes)
                : "sent".equals(n.direction) ? Design.red() : Design.dim();
        String g = "received".equals(n.direction) ? "↓" : "sent".equals(n.direction) ? "↑" : "⟲";
        String sign = n.incoming ? "+" : "sent".equals(n.direction) ? "−" : "";

        TextView glyph = new TextView(act);
        Language.setText(glyph, g); glyph.setTextColor(color); glyph.setTextSize(18f); glyph.setWidth(dp(28));
        row.addView(glyph);

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(6), 0, dp(6), 0);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        boolean reshuffle = n.isReshuffle();
        TextView line1 = new TextView(act);
        Language.setText(line1, reshuffle ? n.grossDisplay() : (sign + Util.tidyAmount(n.amount) + "  " + n.tokenName));
        line1.setTextColor(color); line1.setTextSize(15f); line1.setTypeface(Typeface.DEFAULT_BOLD);
        TextView line2 = new TextView(act);
        String cp = (n.counterparty == null || n.counterparty.isEmpty()) ? "" : Util.shorten(n.counterparty) + "  ·  ";
        Language.setText(line2, Language.join(reshuffle ? Language.join(n.reshuffleLabel(), "  ·  ") : cp, relative(n.timemilli)));
        line2.setTextColor(Design.dim()); line2.setTextSize(12f);
        mid.addView(line1); mid.addView(line2);
        row.addView(mid);

        TextView right = new TextView(act);
        Language.setText(right, "#" + n.block); right.setTextColor(Design.dim()); right.setTextSize(11f); right.setGravity(Gravity.END);
        row.addView(right);

        row.setOnClickListener(v -> showDetail(n));
        return row;
    }

    // ----- this wallet's own postings (local audit rows) -----

    private static boolean isFailed(HistoryRow r) { return HistoryDb.STATUS_ERROR.equals(r.status); }

    private static CharSequence statusWord(HistoryRow r) {
        switch (r.status == null ? "" : r.status) {
            case HistoryDb.STATUS_POSTING: return Language.msg(l10n_utxo_status_posting);
            case HistoryDb.STATUS_POSTED:  return Language.msg(l10n_utxo_status_posted);
            case HistoryDb.STATUS_UNKNOWN: return Language.msg(l10n_utxo_status_unknown);
            case HistoryDb.STATUS_ERROR:   return Language.msg(l10n_utxo_status_failed);
            default: return r.status;
        }
    }

    private View localRow(final HistoryRow r) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(11), dp(8), dp(11));
        boolean failed = isFailed(r);
        int color = failed ? Design.red() : Design.amber();

        TextView glyph = new TextView(act);
        Language.setText(glyph, failed ? "✗" : "⏳"); glyph.setTextColor(color); glyph.setTextSize(18f); glyph.setWidth(dp(28));
        row.addView(glyph);

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(6), 0, dp(6), 0);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView line1 = new TextView(act);
        Language.setText(line1, "−" + Util.tidyAmount(r.amount) + "  " + r.tokenName);
        line1.setTextColor(color); line1.setTextSize(15f); line1.setTypeface(Typeface.DEFAULT_BOLD);
        TextView line2 = new TextView(act);
        // Recipient is either an address (tap the row for the full, copyable value) or a tool label.
        CharSequence to = r.recipient == null ? "" : (Util.isValidAddress(r.recipient) ? Util.shorten(r.recipient) : UtxoMessages.message(r.recipient));
        Language.setText(line2, Language.join(to, "  ·  ", statusWord(r), "  ·  ", relative(r.ts)));
        line2.setTextColor(Design.dim()); line2.setTextSize(12f);
        mid.addView(line1); mid.addView(line2);
        row.addView(mid);

        TextView right = new TextView(act);
        Language.setText(right, Language.msg(l10n_utxo_local)); right.setTextColor(Design.dim()); right.setTextSize(11f); right.setGravity(Gravity.END);
        row.addView(right);

        row.setOnClickListener(v -> showLocalDetail(r));
        return row;
    }

    /** Everything recorded before signing: status, the node's error, recipient, inputs (coinids), outputs, change, burn. */
    private void showLocalDetail(final HistoryRow r) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));
        box.setBackgroundColor(Design.bg());
        kv(box, Language.msg(l10n_utxo_status), statusWord(r));
        if (r.note != null && !r.note.isEmpty()) kv(box, isFailed(r) ? Language.msg(l10n_utxo_error) : Language.msg(l10n_utxo_note), UtxoMessages.message(r.note));
        kv(box, Language.msg(l10n_history_amount), "−" + Util.tidyAmount(r.amount) + " " + r.tokenName);
        kv(box, Language.msg(l10n_history_date_time), Language.stamp(r.ts, "yMMMdHms"));
        if (r.recipient != null && Util.isValidAddress(r.recipient)) copyRow(box, Language.msg(l10n_history_to), r.recipient);
        else if (r.recipient != null && !r.recipient.isEmpty()) kv(box, Language.msg(l10n_utxo_action), UtxoMessages.message(r.recipient));
        if (!Util.isMinima(r.tokenid)) copyRow(box, Language.msg(l10n_history_token), r.tokenid);
        if (r.changeaddr != null && !r.changeaddr.isEmpty()) copyRow(box, Language.msg(l10n_utxo_history_change), r.changeaddr);
        if (r.burn != null && !r.burn.isEmpty()) kv(box, Language.msg(l10n_utxo_history_burn), Util.tidyAmount(r.burn) + " Minima");
        addLocalCoins(box, Language.msg(l10n_utxo_spent_inputs), r.inputs, "coinid");
        addLocalCoins(box, Language.msg(l10n_history_outputs), r.outputs, null);
        ScrollView sv = new ScrollView(act);
        sv.addView(box);
        AlertDialog.Builder b = Design.dialog(act).setTitle(isFailed(r) ? Language.msg(l10n_utxo_failed_transaction) : Language.msg(l10n_utxo_pending_transaction))
                .setView(sv).setPositiveButton(Language.msg(l10n_utxo_close), null);
        // Dismiss: failed rows, and any open row older than a day (something the resolver could never match).
        boolean stale = System.currentTimeMillis() - r.ts > 24L * 60 * 60 * 1000;
        if (isFailed(r) || stale) b.setNegativeButton(Language.msg(l10n_utxo_dismiss), (d, w) -> { act.history().delete(r.internalid); render(); });
        b.show();
    }

    /** [{coinid,address,amount}] or [{address,amount}] → bullets with full, copyable identifiers. */
    private void addLocalCoins(LinearLayout p, CharSequence title, String json, String idKey) {
        try {
            JSONArray a = new JSONArray(json == null ? "[]" : json);
            if (a.length() == 0) return;
            sectionHeader(p, title);
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                bullet(p, "• " + Util.tidyAmount(c.optString("amount", "")));
                if (idKey != null && !c.optString(idKey, "").isEmpty()) copyRow(p, "coinid".equals(idKey) ? Language.msg(l10n_utxo_coin_id) : idKey, c.optString(idKey, ""), 14);
                copyRow(p, Language.msg(l10n_history_address), c.optString("address", ""), 14);
            }
        } catch (Exception ignored) {}
    }

    // ----- detail dialog (identical to the History app) -----

    private void showDetail(NodeTx n) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));
        box.setBackgroundColor(Design.bg());
        kv(box, Language.msg(l10n_history_direction), directionLabel(n.direction));
        kv(box, Language.msg(l10n_history_amount), (n.incoming ? "+" : "sent".equals(n.direction) ? "−" : "") + Util.tidyAmount(n.amount) + " " + n.tokenName);
        kv(box, Language.msg(l10n_history_block), String.valueOf(n.block));
        kv(box, Language.msg(l10n_history_date_time), Language.stamp(n.timemilli, "yMMMdHms"));
        copyRow(box, Language.msg(l10n_history_txpow), n.txpowid);
        if (!Util.isMinima(n.tokenid)) copyRow(box, Language.msg(l10n_history_token), n.tokenid);
        if (n.counterparty != null && !n.counterparty.isEmpty()) copyRow(box, n.incoming ? Language.msg(l10n_history_from) : Language.msg(l10n_history_to), n.counterparty);
        addDeltas(box, n.deltas);
        addBreakdown(box, Language.msg(l10n_history_inputs), n.inputs);
        addBreakdown(box, Language.msg(l10n_history_outputs), n.outputs);
        ScrollView sv = new ScrollView(act);
        sv.addView(box);
        Design.dialog(act).setTitle(Language.msg(l10n_history_transaction)).setView(sv).setPositiveButton(Language.msg(l10n_utxo_close), null)
                .setNeutralButton(Language.msg(l10n_utxo_explorer), (d, w) -> {
                    try {
                        act.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(EXPLORER_TX + n.txpowid)));
                    } catch (Exception e) { Toast.makeText(act, Language.msg(l10n_utxo_no_browser), Toast.LENGTH_SHORT).show(); }
                }).show();
    }

    private void kv(LinearLayout p, CharSequence k, CharSequence v) {
        TextView t = new TextView(act);
        Language.setText(t, Language.join(k, ":  ", v));
        t.setTextColor(Design.text()); t.setTextSize(13f); t.setPadding(0, dp(4), 0, dp(4));
        t.setTextIsSelectable(true);
        p.addView(t);
    }

    private void copyRow(LinearLayout p, CharSequence k, final String v) { copyRow(p, k, v, 0); }

    /** Full value, never shortened; the whole row taps to copy the complete value. */
    private void copyRow(LinearLayout p, CharSequence k, final String v, int indentDp) {
        TextView t = new TextView(act);
        Language.setText(t, Language.msg(l10n_history_copy_row, k, v));
        t.setTextColor(Design.dim()); t.setTextSize(12f); t.setTypeface(Typeface.MONOSPACE); t.setPadding(dp(indentDp), dp(4), 0, dp(4));
        t.setOnClickListener(view -> {
            ((ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(k, v));
            Toast.makeText(act, Language.msg(l10n_history_copied), Toast.LENGTH_SHORT).show();
        });
        p.addView(t);
    }

    private void sectionHeader(LinearLayout p, CharSequence title) {
        TextView h = new TextView(act);
        Language.setText(h, title);
        h.setTextColor(Design.accentText()); h.setTextSize(12f); h.setTypeface(Typeface.DEFAULT_BOLD); h.setPadding(0, dp(8), 0, dp(2));
        p.addView(h);
    }

    private void bullet(LinearLayout p, String text) {
        TextView t = new TextView(act);
        Language.setText(t, text);
        t.setTextColor(Design.text()); t.setTextSize(12f); t.setPadding(dp(6), dp(4), 0, 0);
        t.setTextIsSelectable(true);
        p.addView(t);
    }

    private void addBreakdown(LinearLayout p, CharSequence title, String json) {
        try {
            JSONArray a = new JSONArray(json);
            if (a.length() == 0) return;
            sectionHeader(p, title);
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                String tid = c.optString("tokenid", "0x00");
                bullet(p, "• " + Util.tidyAmount(c.optString("amount", "")) + (Util.isMinima(tid) ? "  Minima" : ""));
                if (!Util.isMinima(tid)) copyRow(p, Language.msg(l10n_history_token), tid, 14);
                copyRow(p, Language.msg(l10n_history_address), c.optString("addr", ""), 14);
            }
        } catch (Exception ignored) {}
    }

    /** Per-token net effect — one entry per token, full tokenid shown and copyable. */
    private void addDeltas(LinearLayout p, String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (o.length() == 0) { kv(p, Language.msg(l10n_history_per_token), "—"); return; }
            sectionHeader(p, Language.msg(l10n_history_per_token));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String tid = it.next();
                bullet(p, "• " + Util.tidyAmount(o.optString(tid, "")) + (Util.isMinima(tid) ? "  Minima" : ""));
                if (!Util.isMinima(tid)) copyRow(p, Language.msg(l10n_history_token), tid, 14);
            }
        } catch (Exception e) { kv(p, Language.msg(l10n_history_per_token), "—"); }
    }

    private static CharSequence relative(long ms) {
        if (ms <= 0) return "";
        return System.currentTimeMillis() - ms < 7 * 86400000L
                ? Language.since(ms) : Language.stamp(ms, "yMMMd");
    }
    private static CharSequence directionLabel(String direction) {
        switch (direction) {
            case "received": return Language.msg(l10n_history_received);
            case "sent": return Language.msg(l10n_history_sent);
            case "self": return Language.msg(l10n_history_self);
            default: return direction;
        }
    }

    private int dp(int v) { return (int) (v * act.getResources().getDisplayMetrics().density); }
}
