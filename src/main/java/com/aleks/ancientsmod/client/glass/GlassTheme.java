package com.aleks.ancientsmod.client.glass;

import com.aleks.ancientsmod.client.FeatureToggles;

/**
 * Single source of truth for the mod's GUI theme: the palette plus the per-mode (dark / light)
 * chrome colors every mod screen and HUD draws through.
 *
 * <p>The palette is the server's map-4 "Hearth" set (see the drawn-gui skill's palette.md):
 * Ember primary, Bronze frames and rules, Candle for clickable text, Flame for numbers, Bone body
 * text, Ash secondary text, Moss for success, Cinder for warnings. Item rarity keeps the
 * <em>vanilla</em> chat-code colors. The surface is flat translucent glass: no gloss, no
 * gradients, 1px notched corners. The dark/light split is driven by
 * {@link FeatureToggles#isGlassLightThemeEnabled()} (dark by default).
 *
 * <p>All colors are packed ARGB ({@code 0xAARRGGBB}). Drawing primitives live in
 * {@link GlassRender}; the custom widgets ({@link GlassButton}, {@link GlassToggle},
 * {@link GlassSlider}, {@link GlassTextField}) build on both.
 */
public final class GlassTheme {

    private GlassTheme() {}

    // ── Hearth palette (mode-independent) ────────────────────────────────────
    /** Ember: primary brand color (selected items, switches on, primary buttons, titles). */
    public static final int ACCENT      = 0xFFF2862B;
    /** Candle: clickable text, focus rings, highlights. */
    public static final int ACCENT_SOFT = 0xFFFFD27A;
    /** Bronze: frames, rules, separators. */
    public static final int BRONZE      = 0xFFC98A4B;
    /** Flame: numbers and values only. */
    public static final int VALUE       = 0xFFFFA552;
    /** Cinder: errors, destructive actions, warnings. */
    public static final int WARN        = 0xFFFF6B4F;
    /** Moss: success, gains, "held". */
    public static final int OK          = 0xFFB5D65E;
    /** Dark ink: text drawn on an Ember fill (white on Ember fails contrast). */
    public static final int INK         = 0xFF24130A;

    /** True when the player has flipped the glass to its light (parchment) variant. */
    public static boolean isLight() { return FeatureToggles.isGlassLightThemeEnabled(); }

    // ── Per-mode chrome ──────────────────────────────────────────────────────
    // Menu panels sit over a real blurred backdrop, so they stay translucent. HUD panels float
    // over the live world with no blur, so they are a little more opaque for legibility.
    // The surface is flat: panelTop == panelBot and gloss is fully transparent, so older call
    // sites that still draw a gradient or gloss band render as a plain flat panel.
    public static int panelTop()   { return isLight() ? 0xE6E6D3AE : 0xB81E140F; }
    public static int panelBot()   { return panelTop(); }
    public static int hudTop()     { return isLight() ? 0xE0E6D3AE : 0xA61E140F; }
    public static int hudBot()     { return hudTop(); }
    public static int rim()        { return isLight() ? 0x66845A32 : 0x40C98A4B; }
    public static int rimSoft()    { return isLight() ? 0x40845A32 : 0x26EADFCB; }
    public static int gloss()      { return 0x00FFFFFF; }
    public static int glossLine()  { return 0x00FFFFFF; }
    public static int innerShadow(){ return 0x00000000; }
    public static int text()       { return isLight() ? 0xFF24130A : 0xFFFFF4E2; }
    public static int textDim()    { return isLight() ? 0xFF4F3A2A : 0xFFEADFCB; }
    public static int textMuted()  { return isLight() ? 0xFF7A6552 : 0xFFA8998A; }
    public static int slot()       { return isLight() ? 0x1F24130A : 0x40000000; }
    public static int slotRim()    { return isLight() ? 0x40845A32 : 0x26EADFCB; }
    public static int rowHover()   { return isLight() ? 0x1F24130A : 0x14EADFCB; }
    /** Hairline rule between groups (bronze, translucent). */
    public static int rule()       { return isLight() ? 0x59845A32 : 0x33C98A4B; }
    /** Fill behind the selected item in a list or sidebar. */
    public static int selected()   { return withAlpha(ACCENT, isLight() ? 0x40 : 0x4D); }
    /** Full-screen tint drawn over the blurred backdrop behind a menu. */
    public static int scrim()      { return isLight() ? 0x33E6D3AE : 0x40000000; }
    public static int sectionLabel() { return ACCENT; }
    /** Translucent accent wash for header strips. */
    public static int headerWash() { return withAlpha(ACCENT, 0x1F); }

    // ── Helpers ──────────────────────────────────────────────────────────────
    public static int withAlpha(int color, int alpha) {
        return ((alpha & 0xFF) << 24) | (color & 0x00FFFFFF);
    }

    /** Scale a color's existing alpha by a 0..100 percentage (RGB preserved). */
    public static int scaleAlpha(int color, int percent) {
        int p = percent < 0 ? 0 : percent > 100 ? 100 : percent;
        int a = ((color >>> 24) & 0xFF) * p / 100;
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** Per-channel ARGB interpolation, t clamped to [0,1]. */
    public static int lerp(int a, int b, float t) {
        t = t < 0 ? 0 : t > 1 ? 1 : t;
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ra = Math.round(aa + (ba - aa) * t);
        int rr = Math.round(ar + (br - ar) * t);
        int rg = Math.round(ag + (bg - ag) * t);
        int rb = Math.round(ab + (bb - ab) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }

    /**
     * Map a rarity name (ShardRarity / LootRarity, any case) to its <em>vanilla</em>
     * chat-code color so the mod matches the server exactly. Unknown → light gray.
     */
    public static int rarityColor(String name) {
        if (name == null) return 0xFFAAAAAA;
        switch (name.trim().toLowerCase()) {
            case "common": case "simple":      return 0xFFAAAAAA; // §7
            case "uncommon":                   return 0xFF55FF55; // §a
            case "elite":                      return 0xFF5555FF; // §9
            case "rare": case "divine":        return 0xFF55FFFF; // §b
            case "ultimate":                   return 0xFFFFFF55; // §e
            case "legendary":                  return 0xFFFFAA00; // §6
            case "godly":                      return 0xFFFF5555; // §c
            case "epic": case "mythic":        return 0xFFFF55FF; // §d
            case "exceptional":                return 0xFFFFFFFF; // §f
            default:                           return 0xFFAAAAAA;
        }
    }
}
