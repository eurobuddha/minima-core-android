"""Translate PandaDEX chart/tape display; keep retained selection and raw evidence intact."""
from pathlib import Path

def adapt(root):
 base=root/'pandadex/src/main/java/com/eurobuddha/pandadex'
 def change(s,old,new,count=1):
  assert s.count(old)==count,('PandaDEX history source drift',old,s.count(old))
  return s.replace(old,new)
 imports='package com.eurobuddha.pandadex;\nimport com.eurobuddha.minimacore.i18n.Language;\nimport static com.eurobuddha.minimacore.i18n.R.string.*;'
 p=base/'ChartTab.java';s=p.read_text();s=change(s,'package com.eurobuddha.pandadex;',imports)
 s=change(s,'    private final TextView readout;','    private final TextView readout;\n    private final TextView empty;')
 s=change(s,'        head.setGravity(Gravity.CENTER_VERTICAL);','        head.setOrientation(VERTICAL);\n        head.setGravity(Gravity.CENTER_VERTICAL);')
 s=change(s,'head.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));','head.addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));')
 s=change(s,'            t.setText(LABELS[i]);','            Language.setText(t, DexMessages.message(LABELS[i]));\n            t.setMinHeight(Design.dp(act, 48));\n            t.setGravity(Gravity.CENTER);')
 s=change(s,'            pills.addView(t);','            pills.addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));')
 s=change(s,'        head.addView(pills);','        head.addView(pills, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));')
 s=change(s,'        addView(chart, new LayoutParams(LayoutParams.MATCH_PARENT, Design.dp(act, 300)));','''        android.widget.FrameLayout plot = new android.widget.FrameLayout(act);
        plot.addView(chart, new android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        empty = new TextView(act);
        Language.setText(empty, Language.msg(l10n_dex_chart_empty));
        empty.setTextColor(Design.DIM2());
        empty.setTextSize(12f);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(pad, pad, pad, pad);
        plot.addView(empty, new android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        addView(plot, new LayoutParams(LayoutParams.MATCH_PARENT, Design.dp(act, 300)));''')
 s=change(s,'''        note.setText("Charts use this device's saved trade records. Recovery may add trades from history retained by your node; "
                + "older or pruned transactions may be missing. Export records for their time basis and chain evidence.");''','        Language.setText(note, Language.msg(l10n_dex_chart_note));')
 start=s.index('    private void showReadout(');end=s.index('    public void render()',start)
 s=s[:start]+'''    private void showReadout(Candles.Candle c) {
        CharSequence value = candleReadout(c);
        Language.setText(readout, value);
        Language.description(chart, value);
    }

    static CharSequence candleReadout(Candles.Candle c) {
        if (c == null) return "";
        return Language.msg(l10n_dex_candle_readout, Language.stamp(c.openMs, "MMMdHm"),
                PriceMath.fmt(c.open), PriceMath.fmt(c.high), PriceMath.fmt(c.low),
                PriceMath.fmt(c.close), PriceMath.fmt(c.volume));
    }

'''+s[end:]
 s=change(s,'        chart.setData(cs, interval);','''        chart.setData(cs, interval);
        empty.setVisibility(cs.isEmpty() ? VISIBLE : GONE);
        if (cs.isEmpty()) {
            showReadout(null);
            Language.description(chart, Language.msg(l10n_dex_chart_empty));
        }''')
 for line in ['import java.math.BigDecimal;\n','import java.text.SimpleDateFormat;\n','import java.util.Date;\n','import java.util.Locale;\n']:s=s.replace(line,'')
 p.write_text(s)
 p=base/'CandleView.java';s=p.read_text();s=change(s,'package com.eurobuddha.pandadex;',imports)
 s=change(s,'''        if (candles.isEmpty()) {
            text.setColor(Design.DIM2());
            c.drawText("No trades observed yet — the tape fills as the network trades",
                    padL + Design.dp(getContext(), 8), h / 2f, text);
            return;
        }''','''        // ChartTab owns the wrapping, language-bound empty-state TextView.
        if (candles.isEmpty()) return;''')
 s=change(s,'    @Override protected void onDraw(Canvas c) {','''    @Override protected void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        // Same retained candles and crosshair; do not call setData on a language change.
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {''')
 s=change(s,'        SimpleDateFormat fmt = new SimpleDateFormat(intervalMs >= Candles.D1 ? "dd MMM" : "dd HH:mm", Locale.US);','        String skeleton = intervalMs >= Candles.D1 ? "MMMd" : "dHm";')
 s=change(s,'''            c.drawText(fmt.format(new Date(candles.get(m).openMs)), Math.max(padL, cx - Design.dp(getContext(), 14)),
                    h - Design.dp(getContext(), 4), text);''','''            String label = Language.date(candles.get(m).openMs, skeleton);
            float x = Math.max(padL, Math.min(w - padL - text.measureText(label), cx - text.measureText(label) / 2f));
            c.drawText(label, x, h - Design.dp(getContext(), 4), text);''')
 for line in ['import java.text.SimpleDateFormat;\n','import java.util.Date;\n','import java.util.Locale;\n']:s=s.replace(line,'')
 p.write_text(s)
 p=base/'TapeTab.java';s=p.read_text();s=change(s,'package com.eurobuddha.pandadex;',imports)
 s=change(s,'    private final SimpleDateFormat fmt = new SimpleDateFormat("dd MMM HH:mm", Locale.US);\n','')
 s=change(s,'private TextView tv(String s,','private TextView tv(CharSequence s,')
 s=change(s,'        t.setText(s);','        Language.setText(t, DexMessages.message(s));')
 s=change(s,'private TextView mono(String s,','private TextView mono(CharSequence s,')
 s=change(s,'            t.setGravity(Gravity.CENTER);','            t.setGravity(Gravity.CENTER);\n            t.setMinHeight(Design.dp(act, 48));')
 s=change(s,'        export.setGravity(Gravity.CENTER);','        export.setGravity(Gravity.CENTER);\n        export.setMinHeight(Design.dp(act, 48));')
 s=change(s,'''"Rows " + mine.size()
                    + "   Net " + PriceMath.fmt(totals.netM) + " MINIMA"
                    + "   " + PriceMath.fmt(totals.netU) + " MxUSD"''','Language.msg(l10n_dex_trade_totals, mine.size(), PriceMath.fmt(totals.netM), PriceMath.fmt(totals.netU))')
 s=change(s,'(r.buy ? "BUY " : "SELL ") + PriceMath.fmt(r.sizeMinima)','Language.join(DexMessages.message(r.buy ? "BUY" : "SELL"), " ", PriceMath.fmt(r.sizeMinima))')
 s=change(s,'''TradeExport.timeLabel(r) + " " + fmt.format(new Date(r.timeMs)) + "   "
                    + (r.maker ? "MAKER" : "TAKER") + "   "
                    + label(r.sourceKind) + "   " + label(r.verificationStatus)''','tradeMetadata(r)')
 s=change(s,'mono(evidence, Design.DIM2(), 8.5f)','mono(Language.join(evidence), Design.DIM2(), 8.5f)')
 s=change(s,'fmt.format(new Date(t)) + (mine ? "  YOU" : "")','Language.join(Language.stamp(t, "MMMdHm"), mine ? Language.join("  ", DexMessages.message("YOU")) : "")')
 s=change(s,'"Corrected "+fmt.format(new Date(correction.getLong("corrected_at")))','Language.msg(l10n_dex_corrected_at, Language.stamp(correction.getLong("corrected_at"), "MMMdHm"))')
 s=change(s,'''"Earlier record: "+original.optString("size","unknown")+" MINIMA @ "+original.optString("price","unknown")
                        +" · "+(original.optLong("timems",0)>0?fmt.format(new Date(original.optLong("timems"))):"timestamp not recorded")''','earlierRecord(original)')
 s=change(s,'''"Source "+correction.getString("coinid")+"\\nEarlier TxPoW "+correction.getString("old_txpowid")
                        +"\\nVerified replacement "+correction.getString("new_txpowid")''','correctionIds(correction)')
 s=change(s,'    private static String label(String s) {','''    static CharSequence tradeMetadata(TradeExport.TradeRow r) {
        return Language.join(DexMessages.message(TradeExport.timeLabel(r)), " ", Language.stamp(r.timeMs, "MMMdHm"), "   ",
                DexMessages.message(r.maker ? "MAKER" : "TAKER"), "   ",
                DexMessages.message(label(r.sourceKind)), "   ", DexMessages.message(label(r.verificationStatus)));
    }

    static CharSequence earlierRecord(org.json.JSONObject original) {
        return Language.msg(l10n_dex_earlier_record,
                !original.isNull("size") ? original.optString("size", "") : Language.msg(l10n_dex_history_unknown),
                !original.isNull("price") ? original.optString("price", "") : Language.msg(l10n_dex_history_unknown),
                original.optLong("timems", 0) > 0 ? Language.stamp(original.optLong("timems"), "MMMdHm") : Language.msg(l10n_dex_timestamp_missing));
    }

    static CharSequence correctionIds(org.json.JSONObject correction) throws org.json.JSONException {
        return Language.msg(l10n_dex_correction_ids, correction.getString("coinid"),
                correction.getString("old_txpowid"), correction.getString("new_txpowid"));
    }

    private static String label(String s) {''')
 for line in ['import java.util.Date;\n','import java.util.Locale;\n','import java.text.SimpleDateFormat;\n']:s=s.replace(line,'')
 p.write_text(s)
