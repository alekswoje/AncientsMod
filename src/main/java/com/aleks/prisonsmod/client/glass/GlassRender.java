package com.aleks.prisonsmod.client.glass;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Procedural "Apple-glass" drawing primitives for the season2 mod GUI. {@link DrawContext}
 * has no rounded-rect, gradient-with-corners, or blur helper, so everything here is built
 * from axis-aligned {@code fill}/{@code fillGradient} spans plus the public {@link DrawContext#applyBlur()}.
 *
 * <p>Corner rounding is done by per-row horizontal insets following a quarter-circle; at GUI
 * scale the 1px stepping disappears under the gloss + translucency. Colors come from
 * {@link GlassTheme} so dark/light mode is automatic.
 */
public final class GlassRender {

    private GlassRender() {}

    /** Default corner radius for panels. */
    public static final int RADIUS = 8;

    // ── Core rounded primitives ──────────────────────────────────────────────

    public static void roundedRect(DrawContext c, int x1, int y1, int x2, int y2, int r, int color) {
        if (x2 <= x1 || y2 <= y1) return;
        r = clampR(r, x1, y1, x2, y2);
        if (r <= 0) { c.fill(x1, y1, x2, y2, color); return; }
        c.fill(x1, y1 + r, x2, y2 - r, color);
        for (int i = 0; i < r; i++) {
            int dx = cornerDx(r, i);
            c.fill(x1 + dx, y1 + i, x2 - dx, y1 + i + 1, color);
            c.fill(x1 + dx, y2 - 1 - i, x2 - dx, y2 - i, color);
        }
    }

    /** Rounded body with a vertical gradient (top → bottom), corners color-correct. */
    public static void roundedRectGrad(DrawContext c, int x1, int y1, int x2, int y2, int r, int top, int bot) {
        if (x2 <= x1 || y2 <= y1) return;
        r = clampR(r, x1, y1, x2, y2);
        int h = y2 - y1;
        if (r <= 0) { c.fillGradient(x1, y1, x2, y2, top, bot); return; }
        c.fillGradient(x1, y1 + r, x2, y2 - r,
                GlassTheme.lerp(top, bot, (float) r / h),
                GlassTheme.lerp(top, bot, (float) (h - r) / h));
        for (int i = 0; i < r; i++) {
            int dx = cornerDx(r, i);
            c.fill(x1 + dx, y1 + i, x2 - dx, y1 + i + 1, GlassTheme.lerp(top, bot, (float) i / h));
            c.fill(x1 + dx, y2 - 1 - i, x2 - dx, y2 - i, GlassTheme.lerp(top, bot, (float) (h - 1 - i) / h));
        }
    }

    public static void roundedBorder(DrawContext c, int x1, int y1, int x2, int y2, int r, int color) {
        if (x2 <= x1 || y2 <= y1) return;
        r = clampR(r, x1, y1, x2, y2);
        c.fill(x1 + r, y1, x2 - r, y1 + 1, color);
        c.fill(x1 + r, y2 - 1, x2 - r, y2, color);
        c.fill(x1, y1 + r, x1 + 1, y2 - r, color);
        c.fill(x2 - 1, y1 + r, x2, y2 - r, color);
        for (int i = 0; i < r; i++) {
            int dx = cornerDx(r, i);
            c.fill(x1 + dx, y1 + i, x1 + dx + 1, y1 + i + 1, color);
            c.fill(x2 - dx - 1, y1 + i, x2 - dx, y1 + i + 1, color);
            c.fill(x1 + dx, y2 - 1 - i, x1 + dx + 1, y2 - i, color);
            c.fill(x2 - dx - 1, y2 - 1 - i, x2 - dx, y2 - i, color);
        }
    }

    // ── Composite glass surfaces ─────────────────────────────────────────────

    /**
     * Real blurred backdrop + scrim behind a full-screen menu. Call before drawing panels.
     *
     * <p>Minecraft enforces at most ONE blur per frame ({@code DrawContext.applyBlur}
     * throws {@code IllegalStateException} otherwise). Vanilla {@code Screen.renderBackground}
     * already blurs when the player's menu-blur video setting is ≥1, so we attempt our own
     * blur and swallow the "already blurred" exception — either way the backdrop ends up
     * blurred and we draw the scrim on top. This also means the frost shows even when the
     * player has menu blur turned off.
     */
    public static void menuBackdrop(DrawContext c, int width, int height) {
        try {
            c.applyBlur();
        } catch (IllegalStateException alreadyBlurredThisFrame) {
            // Vanilla renderBackground already blurred this frame — fine, just add the scrim.
        }
        c.fill(0, 0, width, height, GlassTheme.scrim());
    }

    /** Frosted glass panel for a click-open menu (translucent, blur reads through). */
    public static void panel(DrawContext c, int x, int y, int w, int h) {
        panelBody(c, x, y, x + w, y + h, RADIUS, GlassTheme.panelTop(), GlassTheme.panelBot(), GlassTheme.rim());
    }

    /** Frosted glass panel for an always-on HUD, alpha-scaled by {@code opacityPct} (0..100). */
    public static void hudPanel(DrawContext c, int x, int y, int w, int h, int opacityPct) {
        int x2 = x + w, y2 = y + h;
        panelBody(c, x, y, x2, y2, RADIUS,
                GlassTheme.scaleAlpha(GlassTheme.hudTop(), opacityPct),
                GlassTheme.scaleAlpha(GlassTheme.hudBot(), opacityPct),
                GlassTheme.scaleAlpha(GlassTheme.rim(), opacityPct));
    }

    private static void panelBody(DrawContext c, int x1, int y1, int x2, int y2, int r, int top, int bot, int rim) {
        roundedRectGrad(c, x1, y1, x2, y2, r, top, bot);
        int glossH = Math.min(14, (y2 - y1) / 3);
        if (glossH > 0)
            c.fillGradient(x1 + r, y1 + 1, x2 - r, y1 + 1 + glossH, GlassTheme.gloss(), GlassTheme.withAlpha(GlassTheme.gloss(), 0));
        c.fill(x1 + r, y1 + 1, x2 - r, y1 + 2, GlassTheme.glossLine());
        int shH = Math.min(10, (y2 - y1) / 4);
        if (shH > 0)
            c.fillGradient(x1 + r, y2 - 1 - shH, x2 - r, y2 - 1, GlassTheme.withAlpha(GlassTheme.innerShadow(), 0), GlassTheme.innerShadow());
        roundedBorder(c, x1, y1, x2, y2, r, rim);
    }

    /** Recessed frosted slot (item cells, search fields, inner wells). */
    public static void slot(DrawContext c, int x1, int y1, int x2, int y2) {
        roundedRect(c, x1, y1, x2, y2, 4, GlassTheme.slot());
        roundedBorder(c, x1, y1, x2, y2, 4, GlassTheme.slotRim());
    }

    /** A list row plate; highlighted when hovered. */
    public static void row(DrawContext c, int x1, int y1, int x2, int y2, boolean hover) {
        roundedRect(c, x1, y1, x2, y2, 6, hover ? GlassTheme.rowHover() : GlassTheme.slot());
        if (hover) roundedBorder(c, x1, y1, x2, y2, 6, GlassTheme.rimSoft());
    }

    /** Generic glass button body (no label — caller draws text centered). */
    public static void button(DrawContext c, int x1, int y1, int x2, int y2, boolean hover, boolean active, boolean primary) {
        int body;
        if (!active)       body = GlassTheme.isLight() ? 0x22000000 : 0x18FFFFFF;
        else if (primary)  body = GlassTheme.withAlpha(GlassTheme.ACCENT, hover ? 0xD8 : 0xB0);
        else               body = hover ? GlassTheme.rowHover() : GlassTheme.slot();
        roundedRect(c, x1, y1, x2, y2, 6, body);
        int glossH = Math.min(7, (y2 - y1) / 2);
        if (glossH > 0)
            c.fillGradient(x1 + 2, y1 + 1, x2 - 2, y1 + 1 + glossH, GlassTheme.gloss(), GlassTheme.withAlpha(GlassTheme.gloss(), 0));
        int rim = primary ? GlassTheme.ACCENT_SOFT : hover ? GlassTheme.rim() : GlassTheme.rimSoft();
        roundedBorder(c, x1, y1, x2, y2, 6, rim);
    }

    /** Glass text-field plate; accent rim when focused. */
    public static void field(DrawContext c, int x1, int y1, int x2, int y2, boolean focused) {
        roundedRect(c, x1, y1, x2, y2, 5, GlassTheme.slot());
        roundedBorder(c, x1, y1, x2, y2, 5, focused ? GlassTheme.ACCENT_SOFT : GlassTheme.slotRim());
    }

    /** iOS-style switch. */
    public static void glassSwitch(DrawContext c, int x1, int y1, int x2, int y2, boolean on) {
        int r = (y2 - y1) / 2;
        roundedRect(c, x1, y1, x2, y2, r, on ? GlassTheme.withAlpha(GlassTheme.ACCENT, 0x99) : GlassTheme.slot());
        roundedBorder(c, x1, y1, x2, y2, r, on ? GlassTheme.ACCENT_SOFT : GlassTheme.rimSoft());
        int kd = (y2 - y1) - 4;
        int kx = on ? x2 - 2 - kd : x1 + 2;
        roundedRect(c, kx, y1 + 2, kx + kd, y2 - 2, kd / 2, 0xFFFFFFFF);
    }

    /** Slider rail with accent fill up to {@code frac} (0..1). Caller draws the knob. */
    public static void sliderTrack(DrawContext c, int x1, int y1, int x2, int y2, float frac) {
        int r = (y2 - y1) / 2;
        roundedRect(c, x1, y1, x2, y2, r, GlassTheme.slot());
        int fillX2 = x1 + Math.round((x2 - x1) * Math.max(0f, Math.min(1f, frac)));
        if (fillX2 > x1 + 1) roundedRect(c, x1, y1, fillX2, y2, r, GlassTheme.ACCENT_SOFT);
    }

    /** Rounded, glowing version of the per-row accent strip used by HUD panels. */
    public static void accentStrip(DrawContext c, int x, int topY, int h, int tint) {
        c.fill(x - 1, topY, x + 4, topY + h, GlassTheme.withAlpha(tint, 0x30));
        roundedRect(c, x, topY, x + 3, topY + h, 1, tint);
    }

    /** Rounded scrollbar (track + accent thumb). */
    public static void scrollbar(DrawContext c, int x, int top, int bottom, int thumbTop, int thumbH) {
        roundedRect(c, x, top, x + 3, bottom, 1, GlassTheme.slotRim());
        roundedRect(c, x, thumbTop, x + 3, thumbTop + thumbH, 1, GlassTheme.withAlpha(GlassTheme.ACCENT_SOFT, 0xCC));
    }

    /**
     * Section divider — a centered accent label on a frosted chip with a fading rule behind it.
     * Mirrors the old HeaderRow look in the season2 glass style.
     */
    public static void sectionDivider(DrawContext c, TextRenderer fr, int centerX, int rowTop, int rowH, int rowW, String label) {
        int midY = rowTop + rowH / 2;
        c.fillGradient(centerX - rowW / 2, midY, centerX, midY + 1,
                GlassTheme.withAlpha(GlassTheme.ACCENT_SOFT, 0x00), GlassTheme.withAlpha(GlassTheme.ACCENT_SOFT, 0x55));
        c.fillGradient(centerX, midY, centerX + rowW / 2, midY + 1,
                GlassTheme.withAlpha(GlassTheme.ACCENT_SOFT, 0x55), GlassTheme.withAlpha(GlassTheme.ACCENT_SOFT, 0x00));
        int lw = fr.getWidth(label);
        int ty = rowTop + (rowH - fr.fontHeight) / 2;
        roundedRect(c, centerX - lw / 2 - 7, ty - 2, centerX + lw / 2 + 7, ty + fr.fontHeight + 2, 5, GlassTheme.panelTop());
        roundedBorder(c, centerX - lw / 2 - 7, ty - 2, centerX + lw / 2 + 7, ty + fr.fontHeight + 2, 5, GlassTheme.rimSoft());
        c.drawText(fr, Text.literal(label), centerX - lw / 2, ty, GlassTheme.sectionLabel(), false);
    }

    // ── internals ────────────────────────────────────────────────────────────

    private static int clampR(int r, int x1, int y1, int x2, int y2) {
        return Math.max(0, Math.min(r, Math.min((x2 - x1) / 2, (y2 - y1) / 2)));
    }

    /** Horizontal inset of corner row {@code i} (0 = outermost) for radius {@code r}. */
    private static int cornerDx(int r, int i) {
        int dy = r - i;
        int sq = (int) Math.round(Math.sqrt((double) r * r - (double) dy * dy));
        return r - sq;
    }
}
