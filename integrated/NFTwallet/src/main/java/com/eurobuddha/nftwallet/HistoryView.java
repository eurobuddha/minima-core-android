package com.eurobuddha.nftwallet;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;


import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
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

    private final LinearLayout container;
    private int pageMax = 8;                    // adaptive page size — shrinks under the 256 KB cap
    private boolean fetching = false;
    private int lastFetchBlock = -1;
    private boolean moreAvailable = false;
    /** Row filter: null = all, else "sent" / "received" / "self". */
    private String filter = null;
    /** Node-side offset reached so far — counts RAW txpows (incl. skipped non-transactions),
     *  so "Load older" never re-requests a page whose entries were all filtered out. */
    private int nextOffset = 0;

    public HistoryView(MainActivity a) {
        super(a, R.layout.pm_nftwallet_view_history);
        container = find(R.id.pm_nftwallet_historyContainer);
        render();   // show whatever is already persisted, instantly + offline
    }

    @Override public void refresh() { if (visible() && !fetching) render(); }
    @Override public void onShown() { render(); fetch(true); }
    @Override public void onNewBlock() { if (visible()) fetch(false); }
    private boolean visible() { return act.currentTab() == MainActivity.TAB_HISTORY; }

    private void fetch(boolean force) {
        if (fetching) return;
        if (!force && act.chainBlock() <= lastFetchBlock) return;
        fetchPage(0);
    }

    private void loadOlder() { if (!fetching) fetchPage(nextOffset); }

    /** One adaptive `history` page: shrink (8→4→2→1) + retry under the 256 KB cap; persist new rows; re-render. */
    private void fetchPage(final int offset) {
        fetching = true;
        act.node().cmd("history relevant:true max:" + pageMax + " offset:" + offset, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject resp = json.optJSONObject("response");
                JSONArray txpows = resp == null ? null : resp.optJSONArray("txpows");
                if (resp == null || txpows == null || !json.optBoolean("status", true)) {
                    if (pageMax > 1) { pageMax = Math.max(1, pageMax / 2); fetchPage(offset); return; }
                    fetching = false; render(); return;
                }
                fetching = false;
                lastFetchBlock = act.chainBlock();
                JSONArray details = resp.optJSONArray("details");
                int got = txpows.length();
                if (offset + got > nextOffset) nextOffset = offset + got;
                for (int i = 0; i < got; i++) {
                    JSONObject t = txpows.optJSONObject(i);
                    if (t == null || !t.optBoolean("istransaction", false)) continue;
                    NodeTx n = NodeTx.from(t, details != null && i < details.length() ? details.optJSONObject(i) : null);
                    if (!n.txpowid.isEmpty()) act.history().upsertNodeTx(n);
                }
                moreAvailable = got >= pageMax && act.history().nodeTxCount() < CAP;
                render();
            }
            @Override public void onError(String message) {
                if (pageMax > 1) { pageMax = Math.max(1, pageMax / 2); fetchPage(offset); return; }
                fetching = false; render();
            }
        });
    }

    // ----- render (identical to the History app's list) -----

    private void render() {
        root.setBackgroundColor(Design.bg());          // was showing dark in light mode
        container.setBackgroundColor(Design.bg());
        container.removeAllViews();
        List<NodeTx> rows = act.history().loadNodeTx(CAP);
        List<LocalHistory.Entry> local=LocalHistory.visible(LocalHistory.read(act.history(),CAP),rows);
        if(!local.isEmpty()) {
            localHeading(container,Language.msg(l10n_nft_history_local_activity));
            for(LocalHistory.Entry entry:local)container.addView(localRow(entry));
            localHeading(container,Language.msg(l10n_nft_history_node_history));
        }
        container.addView(filterBar());
        if (rows.isEmpty()) {
            TextView empty = new TextView(act);
            Language.setText(empty, fetching ? Language.msg(l10n_utxo_history_loading) : Language.msg(l10n_utxo_history_empty));
            empty.setTextColor(Design.dim());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, 0);
            container.addView(empty);
            return;
        }
        int shown = 0;
        for (NodeTx n : rows) {
            if (filter != null && !filter.equals(n.direction)) continue;
            container.addView(row(n));
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(act);
            Language.setText(empty, Language.msg(l10n_nft_history_empty_filter));
            empty.setTextColor(Design.dim());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(30), 0, 0);
            container.addView(empty);
        }
        if (moreAvailable) {
            TextView more = new TextView(act);
            Language.setText(more, Language.msg(l10n_utxo_history_older));
            more.setTextColor(Design.accentText());
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, dp(12), 0, dp(8));
            more.setOnClickListener(v -> loadOlder());
            container.addView(more);
        }
    }

    /** All / Sent / Received / Self chips + the CSV/JSON export action. */
    private View filterBar() {
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(4), dp(2), dp(4), dp(8));
        String[][] opts = {{"ALL", null}, {"SENT", "sent"}, {"RECEIVED", "received"}, {"SELF", "self"}};
        for (String[] o : opts) {
            final String value = o[1];
            TextView c = new TextView(act);
            Language.setText(c, value == null ? Language.upper(Language.msg(l10n_wallet_all)) : Language.upper(directionLabel(value)));
            c.setTextSize(10f);
            c.setSingleLine(true);
            c.setMinHeight(dp(48));
            c.setGravity(Gravity.CENTER);
            c.setLetterSpacing(0.08f);
            c.setTypeface(Design.typefaceBold());
            c.setPadding(dp(10), dp(6), dp(10), dp(6));
            boolean on = (filter == null && value == null) || (filter != null && filter.equals(value));
            c.setTextColor(on ? Design.onAccent() : Design.dim());
            c.setBackgroundColor(on ? Design.accent() : Design.surface2());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(4);
            c.setLayoutParams(lp);
            c.setOnClickListener(v -> { filter = value; render(); });
            bar.addView(c);
        }
        View spacer = new View(act);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView export = new TextView(act);
        Language.setText(export, Language.msg(l10n_nft_history_export));
        export.setTextSize(10f);
        export.setSingleLine(true);
        export.setMinHeight(dp(48));
        export.setGravity(Gravity.CENTER);
        export.setLetterSpacing(0.08f);
        export.setTypeface(Design.typefaceBold());
        export.setTextColor(Design.accentText());
        export.setPadding(dp(8), dp(6), dp(4), dp(6));
        export.setOnClickListener(v -> exportDialog());
        bar.addView(export);
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(act);
        scroll.setHorizontalScrollBarEnabled(true);
        scroll.setFillViewport(true);
        scroll.addView(bar);
        return scroll;
    }

    /** Share the (filtered) history as CSV or JSON via the system share sheet. */
    private void exportDialog() {
        Sheet.create(act, Language.msg(l10n_wallet_export_history))
                .subtitle(Language.msg(l10n_nft_history_export_help))
                .action("CSV", Sheet.Style.SECONDARY, () -> shareExport(true))
                .action("JSON", Sheet.Style.PRIMARY, () -> shareExport(false))
                .show();
    }

    private void shareExport(boolean csv) {
        List<NodeTx> rows = act.history().loadNodeTx(CAP);
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
        try {
            if (csv) {
                sb.append("time,direction,amount,token,counterparty,block,txpowid\n");
                for (NodeTx n : rows) {
                    if (filter != null && !filter.equals(n.direction)) continue;
                    sb.append(fmt.format(new Date(n.timemilli))).append(',')
                      .append(n.direction).append(',')
                      .append(Util.tidyAmount(n.amount)).append(',')
                      .append(csvSafe(n.tokenName)).append(',')
                      .append(csvSafe(n.counterparty)).append(',')
                      .append(n.block).append(',')
                      .append(n.txpowid).append('\n');
                }
            } else {
                JSONArray arr = new JSONArray();
                for (NodeTx n : rows) {
                    if (filter != null && !filter.equals(n.direction)) continue;
                    JSONObject o = new JSONObject();
                    o.put("time", fmt.format(new Date(n.timemilli)));
                    o.put("direction", n.direction);
                    o.put("amount", Util.tidyAmount(n.amount));
                    o.put("token", n.tokenName);
                    o.put("counterparty", n.counterparty == null ? "" : n.counterparty);
                    o.put("block", n.block);
                    o.put("txpowid", n.txpowid);
                    arr.put(o);
                }
                sb.append(arr.toString());
            }
        } catch (Exception e) {
            Toast.makeText(act, Language.msg(l10n_filez_export_failed), Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_SUBJECT,
                Language.msg(l10n_nft_history_export_subject, csv ? "CSV" : "JSON").toString());
        send.putExtra(android.content.Intent.EXTRA_TEXT, sb.toString());
        try { act.startActivity(android.content.Intent.createChooser(send, Language.msg(l10n_wallet_export_history))); }
        catch (Exception e) { Toast.makeText(act, Language.msg(l10n_nft_history_no_export_app), Toast.LENGTH_SHORT).show(); }
    }

    /** Quote a CSV field when it carries a comma/quote; escape embedded quotes. */
    private static String csvSafe(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
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
        Language.setText(line1, reshuffle ? n.grossDisplay() : (sign + Format.amount(n.amount) + "  " + n.tokenName));
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

    private static CharSequence localStatus(LocalHistory.Entry entry) {
        if(entry.attempt==null)return Language.msg(l10n_nft_history_unverified_status);
        switch(entry.phase()) {
            case HistoryDb.PREPARED:return Language.msg(l10n_nft_history_preparing);
            case HistoryDb.POSTING:return Language.msg(l10n_utxo_status_posting);
            case HistoryDb.POSTED:return Language.msg(l10n_utxo_status_posted);
            case HistoryDb.CONFIRMED:return Language.msg(l10n_nft_history_confirmed);
            case HistoryDb.NOT_SUBMITTED:return Language.msg(l10n_nft_history_not_submitted);
            default:return Language.msg(l10n_nft_history_unverified_status);
        }
    }
    private void localHeading(LinearLayout parent,CharSequence title) {
        TextView heading=new TextView(act);Language.setText(heading,title);
        heading.setTextColor(Design.accentText());heading.setTextSize(12f);heading.setTypeface(Design.typefaceBold());
        heading.setPadding(dp(8),dp(14),dp(8),dp(6));parent.addView(heading);
    }

    private View localRow(final LocalHistory.Entry entry) {
        HistoryRow r=entry.row;
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(11), dp(8), dp(11));
        boolean failed = HistoryDb.NOT_SUBMITTED.equals(entry.phase());
        int color = entry.confirmed() ? Design.success() : failed ? Design.red() : Design.amber();

        TextView glyph = new TextView(act);
        Language.setText(glyph, entry.confirmed() ? "✓" : failed ? "✗" : "⏳"); glyph.setTextColor(color); glyph.setTextSize(18f); glyph.setWidth(dp(28));
        row.addView(glyph);

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(6), 0, dp(6), 0);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView line1 = new TextView(act);
        Language.setText(line1, Util.tidyAmount(r.amount) + "  " + r.tokenName);
        line1.setTextColor(color); line1.setTextSize(15f); line1.setTypeface(Typeface.DEFAULT_BOLD);
        TextView line2 = new TextView(act);
        // Recipient is either an address (tap the row for the full, copyable value) or a tool label.
        CharSequence to = r.recipient == null ? "" : (Util.isValidAddress(r.recipient) ? Util.shorten(r.recipient) : NftMessages.message(r.recipient));
        Language.setText(line2, Language.join(to, "  ·  ", localStatus(entry), "  ·  ", relative(r.ts)));
        line2.setTextColor(Design.dim()); line2.setTextSize(12f);
        mid.addView(line1); mid.addView(line2);
        row.addView(mid);

        TextView right = new TextView(act);
        Language.setText(right, Language.msg(l10n_utxo_local)); right.setTextColor(Design.dim()); right.setTextSize(11f); right.setGravity(Gravity.END);
        row.addView(right);

        row.setOnClickListener(v -> {
            for(LocalHistory.Entry current:LocalHistory.read(act.history(),CAP))
                if(java.util.Objects.equals(current.row.internalid,r.internalid)){showLocalDetail(current);return;}
        });
        return row;
    }

    /** Everything recorded before signing: status, the node's error, recipient, inputs (coinids), outputs, change, burn. */
    private void showLocalDetail(final LocalHistory.Entry entry) {
        HistoryRow r=entry.row;
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));
        box.setBackgroundColor(Design.bg());
        kv(box, Language.msg(l10n_utxo_status), localStatus(entry));
        if (!entry.note().isEmpty()) kv(box, Language.msg(l10n_utxo_note), NftMessages.message(entry.note()));
        copyRow(box, Language.msg(l10n_nft_history_local_id), r.internalid);
        if(!entry.txpowid().isEmpty())copyRow(box, Language.msg(l10n_history_txpow), entry.txpowid());
        kv(box, Language.msg(l10n_nft_history_requested_amount), Util.tidyAmount(r.amount) + " " + r.tokenName);
        kv(box, Language.msg(l10n_history_date_time), Language.stamp(r.ts, "yMMMdHms"));
        if (r.recipient != null && Util.isValidAddress(r.recipient)) copyRow(box, Language.msg(l10n_history_to), r.recipient);
        else if (r.recipient != null && !r.recipient.isEmpty()) kv(box, Language.msg(l10n_utxo_action), NftMessages.message(r.recipient));
        if (!Util.isMinima(r.tokenid)) copyRow(box, Language.msg(l10n_history_token), r.tokenid);
        if (r.changeaddr != null && !r.changeaddr.isEmpty()) copyRow(box, Language.msg(l10n_utxo_history_change), r.changeaddr);
        if (r.burn != null && !r.burn.isEmpty()) kv(box, Language.msg(l10n_utxo_history_burn), Util.tidyAmount(r.burn) + " Minima");
        addLocalCoins(box, Language.msg(l10n_history_inputs), r.inputs, "coinid");
        addLocalCoins(box, Language.msg(l10n_history_outputs), r.outputs, null);
        Sheet.create(act, Language.msg(l10n_history_transaction)).body(box)
                .action(Language.msg(l10n_utxo_close), Sheet.Style.SECONDARY, null).show();
    }

    /** [{coinid,address,amount}] or [{address,amount}] → bullets with full, copyable identifiers. */
    private void addLocalCoins(LinearLayout p, CharSequence title, String json, String idKey) {
        try {
            JSONArray a = new JSONArray(json == null ? "[]" : json);
            if (a.length() == 0) return;
            localHeading(p, title);
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                kv(p, Language.msg(l10n_history_amount), Util.tidyAmount(c.optString("amount", "")));
                if (idKey != null && !c.optString(idKey, "").isEmpty()) copyRow(p, "coinid".equals(idKey) ? Language.msg(l10n_utxo_coin_id) : idKey, c.optString(idKey, ""));
                copyRow(p, Language.msg(l10n_history_address), c.optString("address", ""));
            }
        } catch (Exception ignored) {}
    }

    // ----- detail dialog (identical to the History app) -----

    private void showDetail(NodeTx n) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        kv(box, Language.msg(l10n_history_direction), directionLabel(n.direction));
        kv(box, Language.msg(l10n_history_amount), (n.incoming ? "+" : "sent".equals(n.direction) ? "−" : "") + Util.tidyAmount(n.amount) + " " + n.tokenName);
        kv(box, Language.msg(l10n_history_block), String.valueOf(n.block));
        kv(box, Language.msg(l10n_history_date_time), Language.stamp(n.timemilli, "yMMMdHms"));
        copyRow(box, Language.msg(l10n_history_txpow), n.txpowid);
        if (n.counterparty != null && !n.counterparty.isEmpty()) {
            addressRow(box, n.incoming ? Language.msg(l10n_history_from) : Language.msg(l10n_history_to), n.counterparty);
        }
        kv(box, Language.msg(l10n_history_per_token), prettyDeltas(n.deltas));
        addBreakdown(box, Language.msg(l10n_history_inputs), n.inputs);
        addBreakdown(box, Language.msg(l10n_history_outputs), n.outputs);
        Sheet.create(act, Language.msg(l10n_history_transaction))
                .body(box)
                .action(Language.msg(l10n_utxo_close), Sheet.Style.SECONDARY, null)
                .show();
    }

    private void kv(LinearLayout p, CharSequence k, CharSequence v) {
        TextView t = new TextView(act);
        Language.setText(t, Language.join(k, ":  ", v));
        t.setTextColor(Design.text()); t.setTextSize(13f); t.setPadding(0, dp(4), 0, dp(4));
        p.addView(t);
    }

    private void copyRow(LinearLayout p, CharSequence k, final String v) {
        TextView l = new TextView(act);
        Language.setText(l, k);
        l.setTextColor(Design.dim()); l.setTextSize(11.5f); l.setPadding(0, dp(7), 0, dp(1));
        p.addView(l);
        TextView t = new TextView(act);
        Language.setText(t, v);
        t.setTextColor(Design.text()); t.setTextSize(11f); t.setTypeface(Typeface.MONOSPACE);
        t.setOnClickListener(view -> CoinDetailDialog.copy(act, k, v));
        p.addView(t);
    }

    private void addBreakdown(LinearLayout p, CharSequence title, String json) {
        try {
            JSONArray a = new JSONArray(json);
            if (a.length() == 0) return;
            TextView h = new TextView(act);
            Language.setText(h, title);
            h.setTextColor(Design.accentText()); h.setTextSize(12f); h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setPadding(0, dp(14), 0, dp(2));
            p.addView(h);
            for (int i = 0; i < a.length(); i++) {
                JSONObject c = a.optJSONObject(i);
                if (c == null) continue;
                String tid = c.optString("tokenid", "0x00");
                String tok = Util.isMinima(tid) ? "Minima" : Util.shorten(tid);
                addressRow(p, Util.tidyAmount(c.optString("amount", "")) + "  " + tok,
                        c.optString("addr", ""));
            }
        } catch (Exception ignored) {}
    }

    /**
     * One address, in full, tap-to-copy, with an answer to the only question that matters about a
     * change output: is it mine?
     *
     * Truncating these made the detail useless — you could not read where your change went, let
     * alone check it. The local address pool answers instantly for the common case; anything it
     * doesn't recognise is put to the node, whose {@code checkaddress relevant} flag is the
     * authoritative answer (the local pool is refreshed rarely and misses newaddress-minted ones).
     */
    private void addressRow(LinearLayout p, CharSequence label, final String addr) {
        if (addr == null || addr.isEmpty()) return;

        TextView l = new TextView(act);
        Language.setText(l, label);
        l.setTextColor(Design.dim());
        l.setTextSize(11.5f);
        l.setPadding(0, dp(7), 0, dp(1));
        p.addView(l);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView a = new TextView(act);
        Language.setText(a, addr);                       // FULL — never shortened
        a.setTextColor(Design.text());
        a.setTextSize(11f);
        a.setTypeface(Typeface.MONOSPACE);
        a.setOnClickListener(v -> CoinDetailDialog.copy(act, Language.msg(l10n_history_address), addr));
        row.addView(a, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView badge = new TextView(act);
        badge.setTextSize(9.5f);
        badge.setTypeface(Design.typefaceBold());
        badge.setLetterSpacing(0.06f);
        badge.setPadding(dp(6), 0, 0, 0);
        row.addView(badge);

        p.addView(row);
        markOwnership(addr, badge);
    }

    /** Check each opened sheet against the current node; ownership must not outlive a wallet session. */
    private void markOwnership(final String addr, final TextView badge) {
        if (!Util.isValidAddress(addr)) { Language.setText(badge, ""); return; }
        Language.setText(badge, Language.msg(l10n_future_checking));
        badge.setTextColor(Design.dim());
        act.node().cmd("checkaddress address:" + addr, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                JSONObject r = json == null ? null : json.optJSONObject("response");
                Object relevant = r == null ? null : r.opt("relevant");
                boolean matches = r != null && (addr.startsWith("0x")
                        ? r.opt("0x") instanceof String && addr.equalsIgnoreCase(r.optString("0x"))
                        : r.opt("Mx") instanceof String && addr.equals(r.optString("Mx")));
                if (json == null || !Boolean.TRUE.equals(json.opt("status")) || !matches || !(relevant instanceof Boolean)) {
                    onError("Incomplete address evidence");
                    return;
                }
                boolean mine = (Boolean) relevant;
                setBadge(badge, mine, mine ? Language.msg(l10n_history_yours) : Language.msg(l10n_nft_history_not_yours));
            }
            @Override public void onError(String message) {
                Language.setText(badge, Language.msg(l10n_nft_history_unverified));
                badge.setTextColor(Design.amber());
            }
        });
    }

    private void setBadge(TextView badge, boolean mine, CharSequence text) {
        Language.setText(badge, text);
        badge.setTextColor(mine ? Design.success() : Design.dim());
    }

    private String prettyDeltas(String json) {
        try {
            JSONObject o = new JSONObject(json);
            StringBuilder sb = new StringBuilder();
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String tid = it.next();
                if (sb.length() > 0) sb.append(", ");
                sb.append(Util.tidyAmount(o.optString(tid, ""))).append(" ").append(Util.isMinima(tid) ? "Minima" : Util.shorten(tid));
            }
            return sb.length() > 0 ? sb.toString() : "—";
        } catch (Exception e) { return "—"; }
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
