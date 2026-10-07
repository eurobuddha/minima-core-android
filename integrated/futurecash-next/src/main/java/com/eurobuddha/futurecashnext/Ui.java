package com.eurobuddha.futurecashnext;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * View builders on top of {@link Design}, so screens read as content rather than as layout plumbing.
 *
 * <p>Two rules this file exists to enforce:
 *
 * <ul>
 *   <li><b>Numbers and addresses are mono.</b> JetBrains Mono's digits are tabular, so amounts and
 *       block heights line up column-wise and don't jitter as they tick. On a screen people scan to
 *       check money, a figure that shifts sideways as it updates is a figure they re-read.</li>
 *   <li><b>Colour means risk, never decoration.</b> Green/amber/red come from Design's risk
 *       vocabulary and are spent only on state. Everything else is ink, dim ink, or the accent.</li>
 * </ul>
 */
public final class Ui {

    private Ui() {}

    public static int dp(Context c, int v) { return Design.dp(c, v); }

    /* ---------- text ---------- */

    public static TextView text(Context c, String s, int color, int sizeSp, boolean bold) {
        final TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        t.setTypeface(bold ? Design.sansBold() : Design.sans());
        t.setLineSpacing(dp(c, 3), 1f);   // body copy here is explanatory; tight leading makes it a wall
        return t;
    }

    /** Numbers, amounts, block heights, addresses — anything that should line up or be compared. */
    public static TextView mono(Context c, String s, int color, int sizeSp, boolean bold) {
        final TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        t.setTypeface(bold ? Design.monoBold() : Design.mono());
        return t;
    }

    /** A small caps-ish section label — the quiet top line of a card. */
    public static TextView label(Context c, String s) {
        final TextView t = text(c, s.toUpperCase(), Design.DIM2(), 11, true);
        t.setLetterSpacing(0.08f);
        return t;
    }

    public static TextView title(Context c, String s) {
        return text(c, s, Design.TEXT(), 17, true);
    }

    public static TextView body(Context c, String s) {
        return text(c, s, Design.DIM(), 13, false);
    }

    /* ---------- containers ---------- */

    public static LinearLayout column(Context c) {
        final LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        final LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /**
     * A raised card. {@code accent} tints the hairline so the card's risk state is readable before a
     * single word is — pass 0 for a neutral card.
     */
    public static LinearLayout card(Context c, int accent) {
        final LinearLayout l = column(c);
        final GradientDrawable bg = Design.raised(c, 18);
        if (accent != 0) bg.setStroke(Math.max(1, dp(c, 1)), accent);
        l.setBackground(bg);
        Design.elevate(l, 6);
        l.setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 16));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 14);
        l.setLayoutParams(lp);
        return l;
    }

    /** An inset row inside a card — a level down, for list items. */
    public static LinearLayout inset(Context c) {
        final LinearLayout l = row(c);
        l.setBackground(Design.roundBg(c, Design.SURFACE2(), 12));
        l.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 8);
        l.setLayoutParams(lp);
        return l;
    }

    /** A status chip: tinted ground, saturated ink, so it reads at a glance without shouting. */
    public static TextView pill(Context c, String s, int colour) {
        return Design.pill(c, s, tint(colour, 0x24), colour);
    }

    private static int tint(int colour, int alpha) {
        return (alpha << 24) | (colour & 0x00FFFFFF);
    }

    /* ---------- controls ---------- */

    /** The primary action: gradient ground, ripple, press-scale. One per screen, ideally. */
    public static Button cta(Context c, String labelText, View.OnClickListener onClick) {
        final Button b = baseButton(c, labelText, Design.ON_ACCENT());
        b.setBackground(Design.ripple(Design.gradientCta(c)));
        Design.pressable(b);
        b.setOnClickListener(onClick);
        return b;
    }

    /** A secondary action: outlined, quieter, still tappable-looking. */
    public static Button ghost(Context c, String labelText, View.OnClickListener onClick) {
        final Button b = baseButton(c, labelText, Design.TEXT());
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(c, 14));
        bg.setStroke(Math.max(1, dp(c, 1)), Design.BORDER());
        b.setBackground(Design.ripple(bg));
        Design.pressable(b);
        b.setOnClickListener(onClick);
        return b;
    }

    /** A destructive or state-changing action, tinted by meaning. */
    public static Button tinted(Context c, String labelText, int colour, View.OnClickListener onClick) {
        final Button b = baseButton(c, labelText, colour);
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(tint(colour, 0x1F));
        bg.setCornerRadius(dp(c, 14));
        bg.setStroke(Math.max(1, dp(c, 1)), tint(colour, 0x66));
        b.setBackground(Design.ripple(bg));
        Design.pressable(b);
        b.setOnClickListener(onClick);
        return b;
    }

    private static Button baseButton(Context c, String labelText, int ink) {
        final Button b = new Button(c);
        b.setText(labelText);
        b.setAllCaps(false);
        b.setTextColor(ink);
        b.setTextSize(14);
        b.setTypeface(Design.sansBold());
        b.setStateListAnimator(null);   // kill the stock elevation bounce; Design.pressable owns motion
        b.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 12);
        lp.rightMargin = dp(c, 8);
        b.setLayoutParams(lp);
        return b;
    }

    public static EditText field(Context c, String hint, boolean numeric) {
        final EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(Design.DIM2());
        e.setTextColor(Design.TEXT());
        e.setTextSize(15);
        e.setSingleLine(true);
        // Amounts and addresses are mono for the same reason they are everywhere else: they get
        // compared, and a proportional font makes that harder than it needs to be.
        e.setTypeface(numeric ? Design.mono() : Design.sans());
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setBackground(Design.stroked(c, Design.SURFACE2(), 14));
        e.setPadding(dp(c, 14), dp(c, 13), dp(c, 14), dp(c, 13));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 10);
        e.setLayoutParams(lp);
        return e;
    }

    /** A hairline divider, for separating rows inside one card. */
    public static View divider(Context c) {
        final View v = new View(c);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.topMargin = dp(c, 12);
        lp.bottomMargin = dp(c, 4);
        v.setLayoutParams(lp);
        v.setBackgroundColor(Design.BORDER());
        return v;
    }

    /** Push the next view to the right-hand edge of a row. */
    public static View spacer(Context c) {
        final View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    /**
     * A full identifier — mono, wrapping onto further lines rather than losing characters, tap to
     * copy the complete value. An abbreviated address cannot be pasted into a wallet, an explorer
     * or a support ticket, which is the only thing it exists for — so nothing in this app may
     * shorten one. This builder is the replacement for the deleted {@code shortAddr()}.
     */
    public static TextView copyable(Context c, String value, int color, int sizeSp) {
        final TextView t = mono(c, value, color, sizeSp, false);
        t.setOnClickListener(v -> {
            final android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("value", value));
                android.widget.Toast.makeText(c, "Copied", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        return t;
    }

    /* ---------- formatting ---------- */

    public static String amount(String a) {
        try {
            final double d = Double.parseDouble(a);
            if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
            final String s = String.format(java.util.Locale.US, "%.4f", d);
            return s.replaceAll("0+$", "").replaceAll("\\.$", "");
        } catch (Exception e) { return a; }
    }

    /** "in ~3 h (block 12345)" — blocks are ~50s. */
    public static String maturesIn(long block, long tip) {
        final long blocks = Math.max(0, block - tip);
        final long mins = Math.round(blocks * 50 / 60.0);
        final String when = mins < 90 ? mins + " min"
                : mins < 1440 ? Math.round(mins / 60.0) + " h"
                : Math.round(mins / 1440.0) + " days";
        return "in ~" + when;
    }

    public static String timeAgo(long ms) {
        final long d = System.currentTimeMillis() - ms;
        if (d < 60000) return "just now";
        final long m = d / 60000;
        if (m < 60) return m + "m ago";
        return (m / 60) + "h ago";
    }
}
