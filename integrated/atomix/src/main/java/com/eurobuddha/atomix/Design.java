package com.eurobuddha.atomix;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

/**
 * Design-token engine with a runtime light/dark toggle (persisted; applied by re-rendering, no recreate()).
 *
 *  - ONYX     — refined dark fintech: cool blue-black grounds, hairline-bordered cards, restrained Minima
 *               orange, mono tabular numerals. The default.
 *  - DAYLIGHT — clean light: cool paper, near-black ink, soft elevation, same orange accent.
 *
 * Every view reads colours/typefaces from here, so one toggle restyles the whole app. Numbers are set in
 * JetBrains Mono (tabular); text in Inter. Values are 1:1 with the approved mock-ups.
 */
public final class Design {

    public enum Mode { ONYX, DAYLIGHT }

    private static final String PREFS = "atomix_design";   // SEPARATE from the fund prefs ("atomix")
    private static final String KEY = "theme";

    private static Mode mode = Mode.ONYX;      // Onyx (dark) is the default
    private static Typeface sSans, sMono;      // Inter / JetBrains Mono (bundled variable TTFs)

    private Design() {}

    // ---- lifecycle ----

    /** Load persisted mode + bundled fonts. Call once as the first line of onCreate. */
    public static void load(Context c) {
        String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, Mode.ONYX.name());
        try { mode = Mode.valueOf(s); } catch (Exception e) { mode = Mode.ONYX; }
        if (sSans == null) { try { sSans = ResourcesCompat.getFont(c, R.font.pm_atomix_inter); } catch (Exception ignore) {} }
        if (sMono == null) { try { sMono = ResourcesCompat.getFont(c, R.font.pm_atomix_jetbrains_mono); } catch (Exception ignore) {} }
    }

    public static void set(Context c, Mode m) {
        mode = m;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, m.name()).apply();
    }

    /** Flip Onyx ↔ Daylight and persist. */
    public static void toggle(Context c) { set(c, mode == Mode.ONYX ? Mode.DAYLIGHT : Mode.ONYX); }

    public static Mode mode()   { return mode; }
    public static boolean isDark() { return mode == Mode.ONYX; }
    public static String label()   { return isDark() ? "Dark" : "Light"; }

    private static int pick(int onyx, int daylight) { return mode == Mode.ONYX ? onyx : daylight; }

    // ---- semantic colours (ARGB ints) — method form so the toggle restyles everything ----
    public static int BG()          { return pick(0xFF0B0D12, 0xFFF3F4F7); }   // app ground
    public static int SURFACE()     { return pick(0xFF12151C, 0xFFFFFFFF); }   // card
    public static int SURFACE2()    { return pick(0xFF1B1F28, 0xFFEDEEF2); }   // elevated / input / inactive pill
    public static int BORDER()      { return pick(0x12FFFFFF, 0xFFE6E8EE); }   // hairline stroke
    public static int TEXT()        { return pick(0xFFEEF0F4, 0xFF181B22); }   // primary ink
    public static int DIM()         { return pick(0xFF8B909C, 0xFF8B909C); }   // secondary
    public static int DIM2()        { return pick(0xFF6F7583, 0xFFA6ABB6); }   // tertiary / axes
    // Accent = the ACTIVE CURRENCY's identity, so toggling MINIMA ↔ mxUSDT restyles the whole app (orange ↔
    // Tether-green): accent, active-nav pill tint, CTA gradient and on-accent ink all follow the currency,
    // each still keyed on dark/light where the currency defines two inks.
    public static int ACCENT()      { return TradingContext.active().accent; }
    public static int ACCENT_SOFT() { return pick(TradingContext.active().accentSoftDark, TradingContext.active().accentSoftLight); }
    public static int GRAD_START()  { return TradingContext.active().gradStart; }
    public static int ON_ACCENT()   { return pick(TradingContext.active().onAccentDark, TradingContext.active().onAccentLight); }
    public static int IN()          { return pick(0xFF3FD0A2, 0xFF12A97E); }   // received / bid / positive
    public static int POSITIVE()    { return IN(); }
    public static int OUT()         { return ACCENT(); }                       // sent
    public static int RED()         { return pick(0xFFFF5C5C, 0xFFE5484D); }   // error / ask / refund

    // ---- typography ----
    public static Typeface sans()     { return sSans != null ? sSans : Typeface.SANS_SERIF; }
    public static Typeface sansBold() { return Typeface.create(sans(), Typeface.BOLD); }
    public static Typeface mono()     { return sMono != null ? sMono : Typeface.MONOSPACE; }
    public static Typeface monoBold() { return Typeface.create(mono(), Typeface.BOLD); }

    // ---- dialog theme so AlertDialog chrome follows the in-app toggle ----
    public static int dialogTheme() { return isDark() ? R.style.pm_atomix_SwapDialogDark : R.style.pm_atomix_SwapDialogLight; }

    // ---- metrics ----
    public static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    // ---- drawables / view helpers ----

    /** A rounded chip. Text uses Inter. */
    public static TextView pill(Context c, String text, int bg, int fg) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(11f);
        t.setTypeface(sans());
        t.setGravity(Gravity.CENTER);
        int h = dp(c, 6), w = dp(c, 10);
        t.setPadding(w, h, w, h);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(c, 14));
        t.setBackground(d);
        return t;
    }

    /**
     * The currency switch: a pill whose ring MOVES, drawn in the OTHER currency's accent.
     *
     * Two earlier attempts are worth not repeating. The control first looked exactly like the Mainnet
     * status pill 8dp away - same fill, same text colour, same radius - which is why people read it as
     * a badge and never tried it. Replacing that with a 1px border that breathed fixed the confusion
     * with the badge and communicated nothing about what the button DOES; it was too quiet to notice
     * and too static to read as an invitation.
     *
     * What carries meaning here is the COLOUR of the moving part: it is the currency you would switch
     * TO. An orange dot orbiting the green dollar pill says "MINIMA is over there" continuously, while
     * the label still names the market your money is actually in. That distinction is not cosmetic -
     * settlement is currency-agnostic and history is currency-scoped, so a chip that periodically
     * displayed the currency you are NOT trading would be a real-funds hazard at a glance. This says
     * the same thing without ever lying.
     *
     *   dollar market (green pill)  -> an ORANGE DOT orbits the perimeter
     *   MINIMA market (orange pill) -> a GREEN ARC of glow sweeps around the edge
     */
    public static TextView actionPill(Context c, String glyph, String text, int bg, int fg) {
        TextView t = pill(c, glyph + "  " + text, bg, fg);
        int padH = dp(c, 12), padV = dp(c, 7);
        t.setPadding(padH, padV, padH, padV);
        return t;
    }

    /** The moving ring for the active currency, in the other currency's accent. */
    public static CcyRing ccyRing(Context c, int bodyColor, int otherAccent, boolean minimaActive) {
        return new CcyRing(bodyColor, otherAccent,
                minimaActive ? CcyRing.Style.SWEEP : CcyRing.Style.ORBIT,
                dp(c, 14), Math.max(2f, dp(c, 2)), Math.max(2.5f, dp(c, 3)));
    }

    /** How fast the ring travels. Cycled on the pill by long-press. */
    public enum Glow {
        OFF(0), CALM(4200), LIVELY(2400), INSISTENT(1300);
        public final int periodMs;   // one full lap
        Glow(int periodMs) { this.periodMs = periodMs; }
        public Glow next() { return values()[(ordinal() + 1) % values().length]; }
    }

    private static final String KEY_GLOW = "ccyglow";

    public static Glow glowLevel(Context c) {
        String v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GLOW, Glow.LIVELY.name());
        try { return Glow.valueOf(v); } catch (IllegalArgumentException e) { return Glow.LIVELY; }
    }

    public static Glow cycleGlow(Context c) {
        Glow next = glowLevel(c).next();
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_GLOW, next.name()).apply();
        return next;
    }

    /**
     * Drive the ring. Returns the animator so the CALLER can cancel it - not optional: render()
     * rebuilds the view tree every ~30s and an Animator is not a Handler callback, so the blanket
     * removeCallbacksAndMessages(null) in onDestroy does not touch it. Without an explicit cancel this
     * leaks one live animator per render, each holding a detached view.
     *
     * Returns null when there is nothing to run - OFF, or the system has animations disabled - and in
     * that case paints the ring as a full static rim so the control is still marked. Motion is the
     * point, but it must not be the ONLY signal.
     */
    public static ValueAnimator glow(final TextView pill, final CcyRing ring, Glow level) {
        pill.setBackground(ring);
        if (level == Glow.OFF || !ValueAnimator.areAnimatorsEnabled()) {
            ring.setPhase(0f, true);
            return null;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(level.periodMs);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.RESTART);
        a.setInterpolator(new android.view.animation.LinearInterpolator());   // a lap, not a bounce
        a.addUpdateListener(an -> ring.setPhase((float) an.getAnimatedValue(), false));
        a.start();
        return a;
    }

    /** A rounded filled background (no border). */
    public static GradientDrawable roundBg(Context c, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** A card surface: filled SURFACE with a 1px hairline BORDER stroke. */
    public static GradientDrawable card(Context c, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(SURFACE());
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.max(1, dp(c, 1)), BORDER());
        return d;
    }

    /** A stroked rounded rect over an arbitrary fill (rows/quotes). */
    public static GradientDrawable stroked(Context c, int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.max(1, dp(c, 1)), BORDER());
        return d;
    }

    /** Primary CTA background: vertical orange gradient. */
    public static GradientDrawable gradientCta(Context c) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{GRAD_START(), ACCENT()});
        d.setCornerRadius(dp(c, 14));
        return d;
    }

    /** Give a view a soft press animation (scale down on touch). Tasteful micro-motion. */
    public static void pressable(final View v) {
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start(); break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start(); break;
            }
            return false;   // never consume — the click listener still fires
        });
    }

    /** Wrap a drawable in a ripple keyed on the soft-accent colour (for the CTA). */
    public static RippleDrawable ripple(GradientDrawable base) {
        return new RippleDrawable(ColorStateList.valueOf(ACCENT_SOFT()), base, null);
    }

    /** A prominent "value just changed" pulse: a bounce (plays 3×) plus, for TextViews, a colour flash that
     *  reverts. Runs on the UI thread; no-op if the view is null. */
    public static void pulse(final View v, final int flashColor) {
        if (v == null) return;
        v.post(() -> {
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(v.getHeight() / 2f);
            ObjectAnimator sx = ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.22f, 1f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.22f, 1f);
            sx.setRepeatCount(2); sy.setRepeatCount(2);
            AnimatorSet set = new AnimatorSet();
            set.playTogether(sx, sy);
            set.setDuration(420);
            set.setInterpolator(new OvershootInterpolator());
            set.start();

            if (v instanceof TextView) {
                final TextView tv = (TextView) v;
                final int original = tv.getCurrentTextColor();
                ValueAnimator flash = ValueAnimator.ofArgb(original, flashColor);
                flash.setDuration(220);
                flash.setRepeatMode(ValueAnimator.REVERSE);
                flash.setRepeatCount(5);
                flash.addUpdateListener(a -> tv.setTextColor((int) a.getAnimatedValue()));
                flash.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) { tv.setTextColor(original); }
                });
                flash.start();
            }
        });
    }
}
