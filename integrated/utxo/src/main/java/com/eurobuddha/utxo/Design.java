package com.eurobuddha.utxo;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Central design-token object. Supports two design languages with a runtime toggle (chosen in CFG,
 * applied app-wide via Activity.recreate()):
 *
 *  - ORIGINAL — brutalist/monospace, faithful to the utxoWallet dapp, with LIGHT (default) and DARK
 *               (photo-negative) sub-modes; accent #ff5a1f constant across both.
 *  - CURRENT  — the existing native dark look (accent #F7931A, rounded, sans-serif).
 *
 * Every view reads colors/metrics from here instead of hard-coded resources, so one toggle restyles
 * the whole app. Values are verbatim from DESIGN_MAP.md (extracted from the dapp's CSS tokens).
 */
public final class Design {

    public enum Mode { ORIGINAL_LIGHT, ORIGINAL_DARK, CURRENT }

    private static final String PREFS = "utxo_design";
    private static final String KEY = "mode";

    // Default to CURRENT so the toggle is opt-in; switch to ORIGINAL_LIGHT/DARK for the dapp look.
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
    public static boolean isOriginal() { return mode != Mode.CURRENT; }

    /** Cycle the three design languages: Original Light → Original Dark → Current (native) → … */
    public static Mode next() {
        switch (mode) {
            case ORIGINAL_LIGHT: return Mode.ORIGINAL_DARK;
            case ORIGINAL_DARK:  return Mode.CURRENT;
            default:             return Mode.ORIGINAL_LIGHT;
        }
    }

    private static int pick(int origLight, int origDark, int current) {
        switch (mode) {
            case ORIGINAL_LIGHT: return origLight;
            case ORIGINAL_DARK:  return origDark;
            default:             return current;
        }
    }

    // ---- semantic colors (ARGB ints) ----
    public static int bg()         { return pick(0xFFFFFFFF, 0xFF000000, 0xFF0A0A0F); }
    public static int surface()    { return pick(0xFFF4F4F2, 0xFF0D0D0F, 0xFF15151F); }
    public static int surface2()   { return pick(0xFFE8E8E5, 0xFF18181B, 0xFF1F1F2B); }
    public static int border()     { return pick(0xFF0A0A0A, 0xFFF0F0F0, 0xFF2A2A38); }
    public static int border2()    { return pick(0xFFC9C9C6, 0xFF383840, 0xFF2A2A38); }
    public static int accent()     { return pick(0xFFFF5A1F, 0xFFFF5A1F, 0xFFF7931A); }
    public static int accentText() { return pick(0xFFB93D0B, 0xFFFF7847, 0xFFF7931A); }
    public static int accent2()    { return pick(0xFFD8430F, 0xFFFF7847, 0xFFF7931A); }
    public static int accentSoft() { return pick(0xFFFFE9E0, 0xFF2E1408, 0x33F7931A); }
    public static int text()       { return pick(0xFF1C1C1C, 0xFFD6D6D6, 0xFFFFFFFF); }
    public static int dim()        { return pick(0xFF5F5F5F, 0xFF8E8E8E, 0xFF9A9AA8); }
    public static int dim2()       { return pick(0xFF666666, 0xFF9A9AA8, 0xFF9A9AA8); }
    public static int heading()    { return pick(0xFF0A0A0A, 0xFFF5F5F5, 0xFFFFFFFF); }
    public static int red()        { return pick(0xFFCC0000, 0xFFFF5B5B, 0xFFFF5B5B); }
    public static int redSoft()    { return pick(0xFFFFE5E5, 0xFF2A0D0D, 0x33E74C3C); }
    public static int amber()      { return pick(0xFF9A6B00, 0xFFE0A93A, 0xFFE6A23C); }
    public static int amberSoft()  { return pick(0xFFFBF0D6, 0xFF2A2008, 0x33E6A23C); }
    public static int blue()       { return pick(0xFF1C46E0, 0xFF6F9BFF, 0xFF6F9BFF); }
    public static int blueSoft()   { return pick(0xFFE4E9FF, 0xFF11193A, 0x336F9BFF); }
    /** Text drawn on top of the accent fill (buttons). */
    public static int onAccent()   { return 0xFF000000; }
    /** "success" maps to the accent in the original; green in the current style. */
    public static int success()    { return pick(0xFFB93D0B, 0xFFFF5A1F, 0xFF2ECC71); }

    // ---- dialogs ----
    /** The dialog theme for the active design language (see themes.xml). */
    public static int dialogTheme() {
        switch (mode) {
            case ORIGINAL_LIGHT: return R.style.pm_utxo_Theme_Utxo_Dialog_Light;
            case ORIGINAL_DARK:  return R.style.pm_utxo_Theme_Utxo_Dialog_OriginalDark;
            default:             return R.style.pm_utxo_Theme_Utxo_Dialog_Native;
        }
    }
    /** THE way to build a dialog: frame, title, buttons and inputs follow Design, not the system theme. */
    public static androidx.appcompat.app.AlertDialog.Builder dialog(Context c) {
        return new com.eurobuddha.minimacore.i18n.LanguageDialog(c, dialogTheme()) {
            @Override public androidx.appcompat.app.AlertDialog create() {
                androidx.appcompat.app.AlertDialog dialog = super.create();
                if (c instanceof MainActivity) ((MainActivity)c).trackLanguageDialog(dialog);
                return dialog;
            }
        };
    }

    // ---- type ----
    public static Typeface typeface()    { return isOriginal() ? Typeface.MONOSPACE : Typeface.DEFAULT; }
}
