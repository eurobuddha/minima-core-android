package com.eurobuddha.vestr;

/**
 * vestr design tokens — the shared dark + orange look used across the native Minima dapps (matches the
 * utxo wallet's "current" theme): near-black surfaces, orange accent, white/grey text.
 * (Field names kept from the original vestr skin to avoid churn; YELLOW/ON_YELLOW are now the orange
 * accent + the text drawn on it.)
 */
public final class VestrDesign {

    private VestrDesign() {}

    // ---- accent (orange) + dark chrome ----
    public static final int YELLOW       = 0xFFF7931A;   // accent: header title, active tab, CTA, icon
    public static final int BLACK        = 0xFF0A0A0F;   // app background / dark chrome
    public static final int BLACK_2      = 0xFF15151F;   // header / card surface
    public static final int BLACK_3      = 0xFF1F1F2B;   // raised surface / input
    public static final int BORDER_DARK  = 0xFF2A2A38;

    // ---- content surface ----
    public static final int CONTENT_BG   = 0xFF0A0A0F;   // main content area
    public static final int CARD         = 0xFF15151F;   // cards / list items
    public static final int CARD_2       = 0xFF1F1F2B;

    // ---- text ----
    public static final int TEXT         = 0xFFFFFFFF;
    public static final int TEXT_ON_DARK = 0xFFFFFFFF;
    public static final int DIM          = 0xFF9A9AA8;   // secondary text
    public static final int DIM_2        = 0xFF6A6A78;
    public static final int DIVIDER      = 0xFF2A2A38;

    // ---- accent text / status ----
    public static final int ON_YELLOW    = 0xFF0A0A0F;   // dark text on the orange accent
    public static final int GREEN        = 0xFF2ECC71;   // positive / collectible
    public static final int GREEN_BG     = 0x332ECC71;
    public static final int RED          = 0xFFE74C3C;
    public static final int RED_BG       = 0x33E74C3C;
    public static final int LABEL        = 0xFFF7931A;   // form labels (orange)

    // ---- bottom nav ----
    public static final int NAV_BG       = 0xFF0A0A0F;
    public static final int NAV_ACTIVE   = 0xFFF7931A;
    public static final int NAV_INACTIVE = 0xFF6A6A78;
}
