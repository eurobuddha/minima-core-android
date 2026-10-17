package com.eurobuddha.pandadex;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** CHART tab: interval pills + candles + a crosshair OHLCV readout. */
@SuppressLint("ViewConstructor")
public final class ChartTab extends LinearLayout {

    private static final long[] INTERVALS = {Candles.M15, Candles.H1, Candles.H4, Candles.D1};
    private static final String[] LABELS = {"15m", "1H", "4H", "1D"};

    private final MainActivity act;
    private final CandleView chart;
    private final TextView readout;
    private final TextView empty;
    private final LinearLayout pills;
    private int sel = 1;

    public ChartTab(MainActivity act) {
        super(act);
        this.act = act;
        setOrientation(VERTICAL);
        int pad = Design.dp(act, 12);
        setPadding(pad, pad, pad, pad);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(VERTICAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(act);
        title.setText("MINIMA / MxUSD");
        title.setTextColor(Design.TEXT());
        title.setTypeface(Design.sansBold());
        title.setTextSize(13f);
        head.addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        pills = new LinearLayout(act);
        for (int i = 0; i < LABELS.length; i++) {
            final int idx = i;
            TextView t = new TextView(act);
            Language.setText(t, DexMessages.message(LABELS[i]));
            t.setMinHeight(Design.dp(act, 48));
            t.setGravity(Gravity.CENTER);
            t.setTextSize(10f);
            t.setTypeface(Design.mono());
            t.setPadding(Design.dp(act, 8), Design.dp(act, 4), Design.dp(act, 8), Design.dp(act, 4));
            t.setOnClickListener(v -> { sel = idx; restyle(); render(); });
            pills.addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        head.addView(pills, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(head);

        readout = new TextView(act);
        readout.setTextColor(Design.DIM());
        readout.setTypeface(Design.mono());
        readout.setTextSize(10f);
        readout.setPadding(0, Design.dp(act, 6), 0, Design.dp(act, 6));
        addView(readout);

        chart = new CandleView(act);
        chart.setOnSelect(this::showReadout);
        android.widget.FrameLayout plot = new android.widget.FrameLayout(act);
        plot.addView(chart, new android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        empty = new TextView(act);
        Language.setText(empty, Language.msg(l10n_dex_chart_empty));
        empty.setTextColor(Design.DIM2());
        empty.setTextSize(12f);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(pad, pad, pad, pad);
        plot.addView(empty, new android.widget.FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        addView(plot, new LayoutParams(LayoutParams.MATCH_PARENT, Design.dp(act, 300)));

        TextView note = new TextView(act);
        Language.setText(note, Language.msg(l10n_dex_chart_note));
        note.setTextColor(Design.DIM2());
        note.setTypeface(Design.sans());
        note.setTextSize(10f);
        note.setPadding(0, Design.dp(act, 10), 0, 0);
        addView(note);

        restyle();
    }

    private void restyle() {
        for (int i = 0; i < pills.getChildCount(); i++) {
            TextView t = (TextView) pills.getChildAt(i);
            t.setTextColor(i == sel ? Design.ON_ACCENT() : Design.DIM2());
            t.setBackground(i == sel ? Design.roundBg(getContext(), Design.ACCENT(), 8) : null);
        }
    }

    private void showReadout(Candles.Candle c) {
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

    public void render() {
        long interval = INTERVALS[sel];
        long window = interval * 120;              // ~120 candles of history
        long since = System.currentTimeMillis() - window;
        List<Candles.Fill> fills = act.db().raw().fills(since);
        List<Candles.Candle> cs = Candles.aggregate(fills, interval);
        if (cs.size() > 120) cs = cs.subList(cs.size() - 120, cs.size());
        chart.setData(cs, interval);
        empty.setVisibility(cs.isEmpty() ? VISIBLE : GONE);
        if (cs.isEmpty()) {
            showReadout(null);
            Language.description(chart, Language.msg(l10n_dex_chart_empty));
        }
        if (!cs.isEmpty() && chart.selected() == null) showReadout(cs.get(cs.size() - 1));
    }
}
