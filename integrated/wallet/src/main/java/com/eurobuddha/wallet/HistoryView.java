package com.eurobuddha.wallet;
import com.eurobuddha.minimacore.i18n.Language;
import com.eurobuddha.minimacore.i18n.LanguageDialog;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import com.eurobuddha.wallet.comms.NodeApi;

/**
 * History tab: on-chain transactions that touched THIS wallet's 64 base addresses, read from the
 * node's {@code history relevant:true} pages and filtered client-side with {@link HistoryTx}
 * (the node's relevant set also contains the node wallet's own transactions — those are dropped).
 * Paged with Language.msg(l10n_wallet_load_older); the page size halves on an over-size reply. Tapping a row opens the
 * full detail (complete txpowid / counterparty address, per-token amounts — nothing truncated).
 */
public class HistoryView extends BaseView {

    private static final int FIRST_PAGE = 16;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.UK);

    private final LinearLayout list;
    private final Button older;
    private final TextView status;
    private final LinearLayout chips;

    private final ArrayList<HistoryTx> mRows = new ArrayList<>();
    private int mOffset = 0;
    private int mPageSize = FIRST_PAGE;
    private boolean mLoading = false;
    private boolean mEnd = false;

    /** Active direction filter: null = all, else "SENT" / "RECEIVED" / "SELF". */
    private String mFilter = null;

    public HistoryView(MainActivity a) {
        super(a, buildRoot(a));
        LinearLayout content = (LinearLayout) ((ScrollView) root).getChildAt(0);
        status = (TextView) content.getChildAt(0);
        chips  = (LinearLayout) content.getChildAt(1);
        list   = (LinearLayout) content.getChildAt(2);
        older  = (Button) content.getChildAt(3);
        older.setOnClickListener(v -> loadPage());
        buildChips();
        refresh();
    }

    private static View buildRoot(MainActivity a) {
        ScrollView sv = new ScrollView(a);
        sv.setFillViewport(true);
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 16));
        content.setBackgroundColor(Design.bg());

        TextView status = new TextView(a);
        status.setTextColor(Design.dim());
        status.setTextSize(12f);
        content.addView(status);

        LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(a, 8), 0, dp(a, 2));
        content.addView(chips);

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);

        Button older = new Button(a);
        Language.setText(older, Language.msg(l10n_wallet_load_older));
        older.setTextColor(Design.accent());
        older.setVisibility(View.GONE);
        content.addView(older);

        sv.addView(content);
        return sv;
    }

    /** Filter chips + export, one row: ALL · SENT · RECEIVED · SELF · (spacer) · EXPORT. */
    private void buildChips() {
        chips.removeAllViews();
        String[][] defs = { {null, "ALL"}, {"SENT", "SENT"}, {"RECEIVED", "RECEIVED"}, {"SELF", "SELF"} };
        for (String[] def : defs) {
            final String value = def[0];
            TextView chip = new TextView(act);
            Language.setText(chip, directionLabel(value));
            chip.setTextSize(11f);
            chip.setLetterSpacing(0.06f);
            chip.setTypeface(Design.typefaceBold(), Typeface.BOLD);
            boolean sel = (mFilter == null && value == null) || (value != null && value.equals(mFilter));
            chip.setTextColor(sel ? Design.accent() : Design.dim());
            chip.setBackgroundColor(sel ? Design.surface() : Design.bg());
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            chip.setOnClickListener(v -> { mFilter = value; buildChips(); render(); });
            chips.addView(chip);
        }
        View spacer = new View(act);
        chips.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView export = new TextView(act);
        Language.setText(export, Language.msg(l10n_wallet_export));
        export.setTextSize(11f);
        export.setLetterSpacing(0.06f);
        export.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        export.setTextColor(Design.accent());
        export.setPadding(dp(10), dp(6), dp(2), dp(6));
        export.setOnClickListener(v -> promptExport());
        chips.addView(export);
    }

    private static CharSequence directionLabel(String direction) {
        if (direction == null) return Language.msg(l10n_wallet_all);
        switch (direction) {
            case "SENT": return Language.msg(l10n_history_sent);
            case "RECEIVED": return Language.msg(l10n_history_received);
            case "SELF": return Language.msg(l10n_history_self);
            default: return direction;
        }
    }

    private static int dp(MainActivity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private int dp(int v) { return dp(act, v); }

    @Override
    public void refresh() {
        if (mRows.isEmpty() && !mLoading) {
            reload();
        } else {
            render();
        }
    }

    @Override
    public void onShown() {
        reload();
    }

    @Override
    public void onNewBlock() {
        // A new block can confirm/append history — refresh only the first page view when idle.
        if (!mLoading) reload();
    }

    private void reload() {
        mRows.clear();
        mOffset = 0;
        mEnd = false;
        mPageSize = FIRST_PAGE;
        loadPage();
    }

    private void loadPage() {
        if (mLoading || mEnd || act.node() == null || !act.isPaired()) { render(); return; }
        mLoading = true;
        Language.setText(status, Language.msg(l10n_history_loading));
        act.node().history(mPageSize, mOffset, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                mLoading = false;
                JSONObject resp = json.optJSONObject("response");
                JSONArray txpows = resp == null ? null : resp.optJSONArray("txpows");
                int raw = txpows == null ? 0 : txpows.length();
                if (txpows != null) {
                    for (int i = 0; i < raw; i++) {
                        JSONObject tp = txpows.optJSONObject(i);
                        if (tp == null) continue;
                        HistoryTx tx = HistoryTx.from(tp, act.addressBook());
                        if (tx.touchesOurs) mRows.add(tx);
                    }
                }
                mOffset += raw;
                if (raw < mPageSize) mEnd = true;
                render();
            }
            @Override public void onError(String message) {
                mLoading = false;
                // Over-size reply: halve the page and retry — a big wallet's history pages can
                // exceed the node's reply cap on nodes without the file hand-off.
                if (message != null && message.toLowerCase().contains("too long") && mPageSize > 1) {
                    mPageSize = Math.max(1, mPageSize / 2);
                    loadPage();
                    return;
                }
                Language.setText(status, Language.msg(l10n_wallet_history_unavailable, message));
            }
        });
    }

    private List<HistoryTx> filtered() {
        if (mFilter == null) return mRows;
        List<HistoryTx> out = new ArrayList<>();
        for (HistoryTx tx : mRows) if (mFilter.equals(tx.direction())) out.add(tx);
        return out;
    }

    private void render() {
        list.removeAllViews();
        List<HistoryTx> shown = filtered();
        if (mRows.isEmpty()) {
            Language.setText(status, act.isPaired()
                    ? (mLoading ? Language.msg(l10n_history_loading) : Language.msg(l10n_wallet_no_transactions))
                    : Language.msg(l10n_wallet_history_node_unavailable));
        } else if (shown.isEmpty()) {
            Language.setText(status, Language.join(Language.msg(l10n_wallet_no_filtered_transactions, directionLabel(mFilter)), mEnd ? "" : Language.join(" ", Language.msg(l10n_wallet_load_older_hint))));
        } else {
            Language.setText(status, Language.msg(l10n_wallet_history_count, shown.size(), directionLabel(mFilter)));
        }
        for (HistoryTx tx : shown) {
            list.addView(row(tx));
        }
        older.setVisibility(mEnd || mRows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---- export ---------------------------------------------------------------------------------

    private void promptExport() {
        List<HistoryTx> shown = filtered();
        if (shown.isEmpty()) {
            android.widget.Toast.makeText(act, Language.msg(l10n_wallet_nothing_to_export), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        new WalletDialog(act)
                .setTitle(Language.msg(l10n_wallet_export_count, shown.size()))
                .setPositiveButton("CSV", (d, w) -> share("minima-wallet-history.csv", exportCsv(shown)))
                .setNeutralButton("JSON", (d, w) -> share("minima-wallet-history.json", exportJson(shown)))
                .setNegativeButton(Language.msg(l10n_common_cancel), null)
                .show();
    }

    private static String csvSafe(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /** Full identifiers in every row — the export exists to be pasted into explorers and sheets. */
    private String exportCsv(List<HistoryTx> zRows) {
        StringBuilder sb = new StringBuilder("time,direction,amount,token,tokenid,counterparty,block,txpowid\n");
        for (HistoryTx tx : zRows) {
            String tid = tx.primaryTokenId();
            sb.append(csvSafe(FMT.format(new Date(tx.timemilli)))).append(',')
              .append(tx.direction()).append(',')
              .append(tid == null ? "" : tx.diff.get(tid).toPlainString()).append(',')
              .append(csvSafe(tid == null ? "" : tx.tokenName(tid))).append(',')
              .append(tid == null ? "" : tid).append(',')
              .append(tx.counterparty).append(',')
              .append(tx.block).append(',')
              .append(tx.txpowid).append('\n');
        }
        return sb.toString();
    }

    private String exportJson(List<HistoryTx> zRows) {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            for (HistoryTx tx : zRows) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("time", FMT.format(new Date(tx.timemilli)));
                o.put("direction", tx.direction());
                org.json.JSONObject amounts = new org.json.JSONObject();
                for (Map.Entry<String, BigDecimal> e : tx.diff.entrySet()) {
                    amounts.put(e.getKey(), e.getValue().toPlainString());
                }
                o.put("amounts", amounts);
                o.put("counterparty", tx.counterparty);
                o.put("block", tx.block);
                o.put("txpowid", tx.txpowid);
                arr.put(o);
            }
            return arr.toString(2);
        } catch (org.json.JSONException e) {
            return arr.toString();
        }
    }

    private void share(String zName, String zBody) {
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_SUBJECT, zName);
        send.putExtra(android.content.Intent.EXTRA_TEXT, zBody);
        act.startActivity(android.content.Intent.createChooser(send, Language.msg(l10n_wallet_export_history)));
    }

    private View row(final HistoryTx tx) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Design.surface());
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        String tid = tx.primaryTokenId();
        String dir = tx.direction();
        BigDecimal amt = tid == null ? BigDecimal.ZERO : tx.diff.get(tid);

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView dirView = new TextView(act);
        Language.setText(dirView, directionLabel(dir));
        dirView.setTextSize(11f);
        dirView.setLetterSpacing(0.08f);
        dirView.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        dirView.setTextColor("RECEIVED".equals(dir) ? Design.accent() : Design.dim());
        top.addView(dirView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView amtView = new TextView(act);
        String sign = amt.compareTo(BigDecimal.ZERO) > 0 ? "+" : (amt.compareTo(BigDecimal.ZERO) < 0 ? "−" : "");
        amtView.setText(tid == null ? "—"
                : sign + Util.tidyAmount(amt.abs().toPlainString()) + "  " + tx.tokenName(tid));
        amtView.setTextColor("RECEIVED".equals(dir) ? Design.accent() : Design.text());
        amtView.setTypeface(Design.typefaceBold(), Typeface.BOLD);
        amtView.setTextSize(14f);
        amtView.setGravity(Gravity.END);
        top.addView(amtView);
        card.addView(top);

        TextView sub = new TextView(act);
        Language.setText(sub, Language.msg(l10n_wallet_date_block, Language.stamp(tx.timemilli, "yMMMdHm"), tx.block));
        sub.setTextColor(Design.dim());
        sub.setTextSize(11f);
        sub.setPadding(0, dp(3), 0, 0);
        card.addView(sub);

        card.setOnClickListener(v -> showDetail(tx));
        return card;
    }

    /** Full detail — complete identifiers, selectable, nothing shortened. */
    private void showDetail(HistoryTx tx) {
        CharSequence detail = Language.join(directionLabel(tx.direction()), "\n",
                Language.msg(l10n_wallet_date_block, Language.stamp(tx.timemilli, "yMMMdHm"), tx.block), "\n\n");
        for (Map.Entry<String, BigDecimal> e : tx.diff.entrySet()) {
            detail = Language.join(detail, Util.tidyAmount(e.getValue().toPlainString()), "  ", tx.tokenName(e.getKey()), "\n");
            if (!Util.isMinima(e.getKey())) detail = Language.join(detail, Language.msg(l10n_history_token), ": ", e.getKey(), "\n");
        }
        if (!tx.counterparty.isEmpty()) detail = Language.join(detail, "\n", Language.msg(l10n_wallet_counterparty), ":\n", tx.counterparty, "\n");
        detail = Language.join(detail, "\n", Language.msg(l10n_history_txpow), ":\n", tx.txpowid);

        TextView msg = new TextView(act);
        Language.setText(msg, detail);
        msg.setTextIsSelectable(true);
        msg.setTextColor(Design.text());
        msg.setTypeface(Typeface.MONOSPACE);
        msg.setTextSize(12f);
        msg.setPadding(dp(20), dp(16), dp(20), dp(8));
        ScrollView wrap = new ScrollView(act);
        wrap.addView(msg);

        new WalletDialog(act)
                .setTitle(Language.msg(l10n_history_transaction))
                .setView(wrap)
                .setPositiveButton(Language.msg(l10n_wallet_copy_txpow), (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("txpowid", tx.txpowid));
                })
                .setNegativeButton(Language.msg(l10n_language_close), null)
                .show();
    }
}
