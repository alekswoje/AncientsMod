package com.aleks.prisonsmod.client.glass;

import com.aleks.prisonsmod.client.FeatureToggles;

/**
 * Single source of truth for the season2 "frosted glass" GUI theme — the palette
 * + per-mode (dark / light) chrome colors every mod screen and HUD draws through.
 *
 * <p>The accent identity mirrors the season2 server (ServerTheme): amethyst violet
 * primary, lilac accent, amber reserved for numbers/values, crimson for warnings,
 * and <em>vanilla</em> rarity codes wherever rarity is shown. The dark/light split
 * is driven by {@link FeatureToggles#isGlassLightThemeEnabled()} (dark by default,
 * toggleable in settings) so callers just ask for {@link #panelTop()} etc. and get
 * the right value for the active mode.
 *
 * <p>All colors are packed ARGB ({@code 0xAARRGGBB}). Drawing primitives live in
 * {@link GlassRender}; the custom widgets ({@link GlassButton}, {@link GlassToggle},
 * {@link GlassSlider}, {@link GlassTextField}) build on both.
 */
public final class GlassTheme {

    private GlassTheme() {}

    // ── Server-identity accent (mode-independent) ────────────────────────────
    /** Amethyst — primary brand / structure color (titles, active fills). */
    public static final int ACCENT      = 0xFF7C3AED;
    /** Lilac — accent / highlight (focus rings, section labels, switches on). */
    public static final int ACCENT_SOFT = 0xFFA78BFA;
    /** Amber — reserved for numbers / values ONLY (never a general accent). */
    public static final int VALUE       = 0xFFF59E0B;
    /** Crimson — errors / destructive / warnings. */
    public static final int WARN        = 0xFFDC2626;
    /** Success green (matches the existing OutpostHud "held" hue). */
    public static final int OK          = 0xFF57E89C;

    /** True when the player has flipped the glass to its light variant. */
    public static boolean isLight() { return FeatureToggles.isGlassLightThemeEnabled(); }

    // ── Per-mode chrome ──────────────────────────────────────────────────────
    // Menu panels sit over a real blurred backdrop, so they stay fairly
    // translucent (the frost reads through). HUD panels float over the live
    // world with no blur, so they're more opaque for legibility.
    public static int panelTop()   { return isLight() ? 0xD6F5F3FC : 0xCC161226; }
    public static int panelBot()   { return isLight() ? 0xD6E9E5F4 : 0xCC0D0A18; }
    public static int hudTop()     { return isLight() ? 0xE8F2F0FA : 0xE6141020; }
    public static int hudBot()     { return isLight() ? 0xE8E7E2F2 : 0xE61B1630; }
    public static int rim()        { return isLight() ? 0x55FFFFFF : 0x4DFFFFFF; }
    public static int rimSoft()    { return isLight() ? 0x33FFFFFF : 0x24FFFFFF; }
    public static int gloss()      { return isLight() ? 0x80FFFFFF : 0x3DFFFFFF; }
    public static int glossLine()  { return isLight() ? 0x99FFFFFF : 0x4DFFFFFF; }
    public static int innerShadow(){ return isLight() ? 0x22000000 : 0x40000000; }
    public static int text()       { return isLight() ? 0xFF231C3A : 0xFFEDEFF6; }
    public static int textDim()    { return isLight() ? 0xFF5C5577 : 0xFFA6ABBA; }
    public static int textMuted()  { return isLight() ? 0xFF8B86A0 : 0xFF6F7283; }
    public static int slot()       { return isLight() ? 0x1A000000 : 0x33000000; }
    public static int slotRim()    { return isLight() ? 0x40FFFFFF : 0x1FFFFFFF; }
    public static int rowHover()   { return isLight() ? 0x22FFFFFF : 0x14FFFFFF; }
    /** Full-screen tint drawn over the blurred backdrop behind a menu. */
    public static int scrim()      { return isLight() ? 0x4DFFFFFF : 0x73080512; }
    public static int sectionLabel() { return ACCENT_SOFT; }
    /** Translucent accent wash for header strips. */
    public static int headerWash() { return withAlpha(ACCENT, 0x2E); }

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
