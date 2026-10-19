package com.eurobuddha.keyreuses;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/**
 * Design tokens, lifted from the KeyUses MiniDapp (mds/keyuses/minidapp/index.html:12-16).
 *
 * <p>NOT the utxo/history palette — ok/warn/risk differ, and the dapp's translucent card/border
 * fills are flattened here to the opaque equivalents over #0A0A0F. Programmatic views need ints,
 * so these are the source of truth; res/values/colors.xml mirrors them for the theme.
 */
public final class Design {

    public static final int BG      = 0xFF0A0A0F;
    public static final int CARD    = 0xFF15151F;
    public static final int BORDER  = 0xFF2A2A38;
    public static final int TEXT    = 0xFFE8E6E3;
    public static final int DIM     = 0xFF8D8B89;   // --text-dim, rgba(232,230,227,0.55) over BG
    public static final int DIM_2   = 0xFF5A5A63;   // the .copyhint / footer greys
    public static final int ACCENT  = 0xFFF7931A;
    public static final int ACCENT_2 = 0xFFFF5E3A;  // gradient end
    public static final int OK      = 0xFF4ADE80;
    public static final int WARN    = 0xFFFBBF24;
    public static final int RISK    = 0xFFF87171;

    /** Tinted fills for verdict banners and chips (the dapp's rgba(...) backgrounds over BG). */
    public static final int OK_SOFT   = 0x1F4ADE80;
    public static final int WARN_SOFT = 0x24FBBF24;
    public static final int RISK_SOFT = 0x24F87171;
    public static final int ACCENT_SOFT = 0x14F7931A;

    /** Inset panel behind the recommendation / "why this happened" blocks (.reco). */
    public static final int INSET = 0xFF08080C;

    private Design() {}

    /** Rounded card background with a 1px border, matching .card / .reco. */
    public static GradientDrawable card(Context ctx, int fill, int stroke, int radiusDp) {
        final GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(ctx, radiusDp));
        d.setStroke(Math.max(1, (int) dp(ctx, 1)), stroke);
        return d;
    }

    /** The primary button's 135deg #F7931A -> #FF5E3A gradient (.btn). */
    public static GradientDrawable accentButton(Context ctx, int radiusDp) {
        final GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{ACCENT, ACCENT_2});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(ctx, radiusDp));
        return d;
    }

    /** Pill background for a status chip (.chip). */
    public static GradientDrawable chip(Context ctx, int fill) {
        final GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(ctx, 20));
        return d;
    }

    /** Small circular dot, used for the badge's glowing accent marker. */
    public static GradientDrawable dot(int color) {
        final GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    public static float dp(Context ctx, float v) {
        return v * ctx.getResources().getDisplayMetrics().density;
    }

    public static int dpi(Context ctx, float v) {
        return (int) dp(ctx, v);
    }

    /** Same colour at a different alpha — for tinted borders derived from a state colour. */
    public static int alpha(int color, int a) {
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }
}
