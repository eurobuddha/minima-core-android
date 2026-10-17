package com.eurobuddha.vestr;
import com.eurobuddha.minimacore.i18n.Language;
import static com.eurobuddha.minimacore.i18n.R.string.*;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.TimeUnit;

/** Shared rendering for a vesting-contract list card (Creator + Collector tabs), faithful to the dapp. */
public final class ContractUi {

    private ContractUi() {} // utility holder — never instantiated

    /** Build one tappable contract card (header icon/amount + two-column body) that opens the detail screen on click. */
    public static View card(final MainActivity act, final Contract c) {
        int pad = dp(act, 14);
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(VestrDesign.CARD);
        bg.setCornerRadius(dp(act, 8));
        card.setBackground(bg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(act, 12);
        card.setLayoutParams(clp);

        // ---- header: icon + nickname + amount ----
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(VestrDesign.CARD_2);
        head.setPadding(pad, pad, pad, pad);

        // Avatar glyph: "M" for native Minima, else first letter of the token name (or "?" when unnamed).
        TextView icon = new TextView(act);
        Language.setText(icon, c.isMinima() ? "M" : (c.tokenName.isEmpty() ? "?" : c.tokenName.substring(0, 1).toUpperCase()));
        icon.setTextColor(VestrDesign.ON_YELLOW);
        icon.setTextSize(18f);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(VestrDesign.YELLOW);
        ib.setCornerRadius(dp(act, 6));
        icon.setBackground(ib);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(act, 44), dp(act, 44));
        ilp.rightMargin = dp(act, 12);
        icon.setLayoutParams(ilp);
        head.addView(icon);

        LinearLayout titleCol = new LinearLayout(act);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // No user nickname in the model — label by the truncated coin UID instead.
        TextView nick = new TextView(act);
        Language.setText(nick, Language.msg(l10n_vestr_contract_uid, shortHex(c.uid)));
        nick.setTextColor(VestrDesign.DIM);
        nick.setTextSize(12f);
        nick.setSingleLine(true);
        titleCol.addView(nick);
        TextView amt = new TextView(act);
        Language.setText(amt, Language.join(amount(c.amount), " ", VestrText.token(c.raw.opt("token"), c.tokenid)));
        amt.setTextColor(VestrDesign.TEXT);
        amt.setTextSize(16f);
        amt.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(amt);
        head.addView(titleCol);
        card.addView(head);

        // ---- body: two columns ----
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(pad, pad, pad, pad);

        LinearLayout left = new LinearLayout(act);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(kv(act, Language.msg(l10n_vestr_total_vested), amount(c.total), true));
        left.addView(kv(act, Language.msg(l10n_vestr_collected), amount(c.collected()), true));
        body.addView(left);

        LinearLayout right = new LinearLayout(act);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        right.setGravity(Gravity.END);
        // Right column: collect cadence (grace period) plus human-readable start/end timing.
        right.addView(rightLine(act, Language.msg(l10n_vestr_collect_cadence, grace(c.graceHours)), VestrDesign.DIM));
        right.addView(rightLine(act, relative(c.startMs, "start"), VestrDesign.DIM_2));
        right.addView(rightLine(act, relative(c.endMs, "end"), VestrDesign.DIM_2));
        body.addView(right);
        card.addView(body);

        // Tapping the card hands the raw coin JSON to the detail/collect activity.
        card.setOnClickListener(v -> {
            Intent i = new Intent(act, ContractDetailActivity.class);
            i.putExtra("coin", c.raw.toString());
            act.startActivity(i);
        });
        return card;
    }

    // ---- helpers ----

    /** "key: value" label line for the body's left column; {@code bold} emphasises headline figures. */
    private static View kv(MainActivity act, CharSequence k, String v, boolean bold) {
        TextView t = new TextView(act);
        Language.setText(t, Language.join(k, ": ", v));
        t.setTextColor(VestrDesign.TEXT);
        t.setTextSize(13f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(act, 2), 0, dp(act, 2));
        return t;
    }

    /** End-aligned single text line for the body's right (timing) column, in the given dim colour. */
    private static View rightLine(MainActivity act, CharSequence s, int color) {
        TextView t = new TextView(act);
        Language.setText(t, s);
        t.setTextColor(color);
        t.setTextSize(12f);
        t.setGravity(Gravity.END);
        t.setPadding(0, dp(act, 2), 0, dp(act, 2));
        return t;
    }

    /** Map a grace-period in hours to its human label (e.g. "daily", "weekly") via the contract's Grace enum. */
    static CharSequence grace(int hours) {
        return VestrText.grace(VestingContract.Grace.fromHours(hours));
    }

    /** Format a token amount: cap at 6 decimals (round down — never overstate), drop trailing zeros. */
    static String amount(BigDecimal b) {
        try {
            BigDecimal t = b;
            if (b.scale() > 6) t = b.setScale(6, RoundingMode.DOWN);
            return t.stripTrailingZeros().toPlainString();
        } catch (Exception e) { return b.toPlainString(); }
    }

    /** Abbreviate a long hex id to "0x"-stripped "abcdef…wxyz"; short strings pass through unchanged. */
    static String shortHex(String s) {
        if (s == null) return "";
        s = s.startsWith("0x") ? s.substring(2) : s;
        return s.length() <= 10 ? s : s.substring(0, 6) + "…" + s.substring(s.length() - 4);
    }

    /** "starts in 3 days" / "started 2 days ago" / "ends in …". */
    static CharSequence relative(long whenMs, String verb) {
        if (whenMs <= 0) return "";
        long diff = whenMs - System.currentTimeMillis();
        boolean past = diff < 0;
        long a = Math.abs(diff);
        long days = TimeUnit.MILLISECONDS.toDays(a), hours = TimeUnit.MILLISECONDS.toHours(a), mins = TimeUnit.MILLISECONDS.toMinutes(a);
        int count = (int)Math.min(Integer.MAX_VALUE, days > 0 ? days : hours > 0 ? hours : mins);
        int unit = days > 0 ? com.eurobuddha.minimacore.i18n.R.plurals.l10n_vestr_days
                : hours > 0 ? com.eurobuddha.minimacore.i18n.R.plurals.l10n_vestr_hours : com.eurobuddha.minimacore.i18n.R.plurals.l10n_vestr_minutes;
        return Language.msg("start".equals(verb) ? (past ? l10n_vestr_started_ago : l10n_vestr_starts_in)
                : (past ? l10n_vestr_ended_ago : l10n_vestr_ends_in), Language.count(unit, count));
    }

    /** Convert density-independent pixels to raw pixels using the device's display density. */
    private static int dp(MainActivity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density);
    }
}
