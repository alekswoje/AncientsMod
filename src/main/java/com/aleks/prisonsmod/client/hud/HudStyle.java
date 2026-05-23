package com.aleks.prisonsmod.client.hud;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Shared chrome rendering + per-widget look settings.
 *
 * <p>Used by {@link BoosterHud}, {@link EventsHud} and {@link CooldownsHud} so
 * background / border / header strip and the compact-mode geometry stay
 * identical across widgets. Each widget passes its {@code id} so the
 * background opacity, compact flag and hide-header flag read from
 * {@link HudSettings} can vary per widget.
 */
public final class HudStyle {

    // ── Setting keys ─────────────────────────────────────────────────────────
    public static final String KEY_HIDE_HEADER = "hide_header";
    public static final String KEY_HIDE_BORDER = "hide_border";
    public static final String KEY_BG_OPACITY  = "bg_opacity";
    public static final String KEY_COMPACT     = "compact";
    /** When ON, the HUD renders its chrome (header strip) even with no rows — useful for confirming it's wired. */
    public static final String KEY_ALWAYS_SHOW = "always_show";

    public static final int DEFAULT_OPACITY = 90; // percent of base alpha

    // ── Chrome colors ────────────────────────────────────────────────────────
    /** Base alpha is preserved at 100% opacity; the per-widget opacity setting scales it down. */
    public static final int BG_TOP_BASE = 0xE6111319;
    public static final int BG_BOT_BASE = 0xE61A1D26;
    public static final int BORDER       = 0x55FFFFFF;
    public static final int BORDER_INNER = 0x11FFFFFF;
    public static final int HEADER_BG    = 0x22FFC857;
    public static final int HEADER_RULE  = 0x44FFFFFF;
    public static final int HEADER_TEXT  = 0xFFFFD68A;
    public static final int TIME_COLOR   = 0xFFBFC4CC;

    // ── Layout constants (normal / compact) ──────────────────────────────────
    private static final int PADDING_X_NORMAL  = 6;
    private static final int PADDING_X_COMPACT = 3;
    private static final int PADDING_Y_NORMAL  = 4;
    private static final int PADDING_Y_COMPACT = 2;
    private static final int HEADER_H_NORMAL   = 13;
    private static final int HEADER_H_COMPACT  = 10;
    private static final int ROW_H_NORMAL      = 12;
    private static final int ROW_H_COMPACT     = 10;
    private static final int COLUMN_GAP_NORMAL = 10;
    private static final int COLUMN_GAP_COMPACT = 6;
    private static final int STRIP_W           = 2;
    private static final int STRIP_GAP_NORMAL  = 5;
    private static final int STRIP_GAP_COMPACT = 3;

    private HudStyle() {}

    // ── Setting accessors ────────────────────────────────────────────────────
    public static boolean isHeaderHidden(String widgetId) {
        return HudSettings.getBoolean(widgetId, KEY_HIDE_HEADER, false);
    }

    public static boolean isBorderHidden(String widgetId) {
        return HudSettings.getBoolean(widgetId, KEY_HIDE_BORDER, false);
    }

    public static int bgOpacity(String widgetId) {
        return Math.max(0, Math.min(100, HudSettings.getInt(widgetId, KEY_BG_OPACITY, DEFAULT_OPACITY)));
    }

    public static boolean isCompact(String widgetId) {
        return HudSettings.getBoolean(widgetId, KEY_COMPACT, false);
    }

    public static boolean isAlwaysShown(String widgetId) {
        return HudSettings.getBoolean(widgetId, KEY_ALWAYS_SHOW, false);
    }

    public static int padX(String widgetId)    { return isCompact(widgetId) ? PADDING_X_COMPACT : PADDING_X_NORMAL; }
    public static int padY(String widgetId)    { return isCompact(widgetId) ? PADDING_Y_COMPACT : PADDING_Y_NORMAL; }
    public static int headerH(String widgetId) { return isCompact(widgetId) ? HEADER_H_COMPACT : HEADER_H_NORMAL; }
    public static int rowH(String widgetId)    { return isCompact(widgetId) ? ROW_H_COMPACT : ROW_H_NORMAL; }
    public static int columnGap(String widgetId) { return isCompact(widgetId) ? COLUMN_GAP_COMPACT : COLUMN_GAP_NORMAL; }
    public static int stripW()                 { return STRIP_W; }
    public static int stripGap(String widgetId) { return isCompact(widgetId) ? STRIP_GAP_COMPACT : STRIP_GAP_NORMAL; }

    /** Effective height contribution of the header strip (0 if hidden). */
    public static int effectiveHeaderH(String widgetId) {
        return isHeaderHidden(widgetId) ? 0 : headerH(widgetId);
    }

    /**
     * Multiply the alpha channel of a base color by the widget's opacity
     * setting. RGB is preserved exactly.
     */
    public static int applyOpacity(int baseColor, String widgetId) {
        int op = bgOpacity(widgetId);
        int alpha = ((baseColor >>> 24) & 0xFF) * op / 100;
        return (alpha << 24) | (baseColor & 0x00FFFFFF);
    }

    /**
     * Draw the panel chrome (gradient background, hairline border, optional
     * header). Returns the y-coordinate at which the first content row should
     * be drawn.
     */
    public static int drawChrome(DrawContext ctx, TextRenderer fr, String widgetId,
                                 int w, int h, String headerLabel) {
        int bgTop = applyOpacity(BG_TOP_BASE, widgetId);
        int bgBot = applyOpacity(BG_BOT_BASE, widgetId);
        ctx.fillGradient(0, 0, w, h, bgTop, bgBot);

        if (!isBorderHidden(widgetId)) {
            drawBorder(ctx, 0, 0, w, h, BORDER);
            ctx.fill(1, 1, w - 1, 2, BORDER_INNER);
        }

        if (!isHeaderHidden(widgetId)) {
            int hh = headerH(widgetId);
            ctx.fill(1, 1, w - 1, hh, HEADER_BG);
            ctx.fill(1, hh, w - 1, hh + 1, HEADER_RULE);
            ctx.drawText(fr, Text.literal(headerLabel), padX(widgetId),
                    (hh - fr.fontHeight) / 2 + 1, HEADER_TEXT, true);
            return hh + padY(widgetId);
        }
        // No header: content starts at the top with regular vertical padding.
        return padY(widgetId);
    }

    private static void drawBorder(DrawContext ctx, int x, int y, int x2, int y2, int color) {
        ctx.fill(x, y, x2, y + 1, color);
        ctx.fill(x, y2 - 1, x2, y2, color);
        ctx.fill(x, y, x + 1, y2, color);
        ctx.fill(x2 - 1, y, x2, y2, color);
    }
}
