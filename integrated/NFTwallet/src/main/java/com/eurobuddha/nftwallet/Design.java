package com.eurobuddha.nftwallet;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Central design-token object. Four design languages with a runtime toggle (applied app-wide
 * via Activity.recreate()):
 *
 *  - CURRENT     — the family dark look (bg #0A0A0F, cards #15151F, accent #F7931A). Default.
 *  - CLEAN_LIGHT — the old-wallet 2.47.2 mood: white cards on a soft grey ground, rounded,
 *                  sans-serif, accent #E8820A. The day-one light theme.
 *  - ORIGINAL_LIGHT / ORIGINAL_DARK — the brutalist/monospace utxoWallet-dapp pair, kept as
 *                  selectable extras (Settings), accent #FF5A1F.
 *
 * Every view reads colors/metrics from here instead of hard-coded resources, so one toggle
 * restyles the whole app. CURRENT/ORIGINAL values are verbatim from DESIGN_MAP.md.
 */
public final class Design {

    public enum Mode { ORIGINAL_LIGHT, ORIGINAL_DARK, CURRENT, CLEAN_LIGHT }

    private static final String PREFS = "nftwallet_design";
    private static final String KEY = "mode";

    private static Mode mode = Mode.CURRENT;

    private Design() {}

    public static void load(Context c) {
        String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, Mode.CURRENT.name());
        try { mode = Mode.valueOf(s); } catch (Exception e) { mode = Mode.CURRENT; }
    }

    public static void set(Context c, Mode m) {
        mode = m;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, m.name()).apply();
    }

    public static Mode mode() { return mode; }
    public static boolean isOriginal() { return mode == Mode.ORIGINAL_LIGHT || mode == Mode.ORIGINAL_DARK; }
    public static boolean isDark() { return mode == Mode.ORIGINAL_DARK || mode == Mode.CURRENT; }

    /** Toggle Dark (family) ↔ Light (old-wallet clean white). */
    public static Mode next() {
        return mode == Mode.CURRENT ? Mode.CLEAN_LIGHT : Mode.CURRENT;
    }

    public static String label() {
        switch (mode) {
            case ORIGINAL_LIGHT: return "Original · Light";
            case ORIGINAL_DARK:  return "Original · Dark";
            case CLEAN_LIGHT:    return "Clean · Light";
            default:             return "Dark";
        }
    }

    private static int pick(int origLight, int origDark, int current, int cleanLight) {
        switch (mode) {
            case ORIGINAL_LIGHT: return origLight;
            case ORIGINAL_DARK:  return origDark;
            case CLEAN_LIGHT:    return cleanLight;
            default:             return current;
        }
    }

    // ---- semantic colors (ARGB ints) ----
    public static int bg()         { return pick(0xFFFFFFFF, 0xFF000000, 0xFF0A0A0F, 0xFFF5F5F6); }
    public static int surface()    { return pick(0xFFF4F4F2, 0xFF0D0D0F, 0xFF15151F, 0xFFFFFFFF); }
    public static int surface2()   { return pick(0xFFE8E8E5, 0xFF18181B, 0xFF1F1F2B, 0xFFEFEFF1); }
    public static int border()     { return pick(0xFF0A0A0A, 0xFFF0F0F0, 0xFF2A2A38, 0xFFE4E4E9); }
    public static int border2()    { return pick(0xFFC9C9C6, 0xFF383840, 0xFF2A2A38, 0xFFD5D5DC); }
    public static int accent()     { return pick(0xFFFF5A1F, 0xFFFF5A1F, 0xFFF7931A, 0xFFE8820A); }
    public static int accentText() { return isDark() ? accent() : 0xFFB93D0B; }
    public static int accent2()    { return pick(0xFFD8430F, 0xFFFF7847, 0xFFF7931A, 0xFFC96E05); }
    public static int accentSoft() { return pick(0xFFFFE9E0, 0xFF2E1408, 0x33F7931A, 0xFFFDEBD3); }
    public static int text()       { return pick(0xFF1C1C1C, 0xFFD6D6D6, 0xFFFFFFFF, 0xFF17181C); }
    public static int dim()        { return pick(0xFF5F5F5F, 0xFF8E8E8E, 0xFF9A9AA8, 0xFF72747C); }
    public static int dim2()       { return pick(0xFF8A8A8A, 0xFF5A5A5A, 0xFF6A6A78, 0xFF9A9CA4); }
    public static int heading()    { return pick(0xFF0A0A0A, 0xFFF5F5F5, 0xFFFFFFFF, 0xFF0E0F12); }
    public static int red()        { return pick(0xFFCC0000, 0xFFFF5B5B, 0xFFE74C3C, 0xFFD93831); }
    public static int redSoft()    { return pick(0xFFFFE5E5, 0xFF2A0D0D, 0x33E74C3C, 0xFFFBE2E1); }
    public static int amber()      { return pick(0xFF9A6B00, 0xFFE0A93A, 0xFFE6A23C, 0xFFB97A0A); }
    public static int amberSoft()  { return pick(0xFFFBF0D6, 0xFF2A2008, 0x33E6A23C, 0xFFFCF1DC); }
    public static int blue()       { return pick(0xFF1C46E0, 0xFF6F9BFF, 0xFF6F9BFF, 0xFF2273CE); }
    public static int blueSoft()   { return pick(0xFFE4E9FF, 0xFF11193A, 0x336F9BFF, 0xFFE3EEFA); }
    /** Text drawn on top of the accent fill (buttons). */
    public static int onAccent()   { return 0xFF000000; }
    /** "success" maps to the accent in the original; green in the current/clean styles. */
    public static int success()    { return pick(0xFFFF5A1F, 0xFFFF5A1F, 0xFF2ECC71, 0xFF158A50); }

    // ---- metrics / type ----
    public static Typeface typeface()    { return isOriginal() ? Typeface.MONOSPACE : Typeface.DEFAULT; }
    public static Typeface typefaceBold(){ return isOriginal() ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) : Typeface.DEFAULT_BOLD; }
    /** Corner radius in dp — the original is hard-edged (0); clean-light is extra soft. */
    public static float radiusDp()       { return isOriginal() ? 0f : (mode == Mode.CLEAN_LIGHT ? 12f : 6f); }
    /** Micro-labels are UPPERCASE + tracked in the original. */
    public static boolean upperLabels()  { return isOriginal(); }
    public static float labelTracking()  { return isOriginal() ? 0.12f : 0.0f; }
}
