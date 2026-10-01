package com.aleks.ancientsmod.client.glass;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Drawing primitives for the mod's glass GUI. {@link DrawContext} has no rounded-rect or blur
 * helper, so everything here is built from axis-aligned {@code fill}/{@code fillGradient} spans
 * plus the public {@link DrawContext#applyBlur()}.
 *
 * <p>Corners are a single notched pixel, not a stepped quarter-circle: at GUI scale a stepped
 * curve reads as jagged against the pixel font, while one missing corner pixel reads as a crisp,
 * deliberate edge. The {@code rounded*} names and {@code r} parameters are kept so existing call
 * sites compile; any {@code r > 0} draws the notch, {@code r == 0} draws a square box.
 *
 * <p>The surface is flat: no gloss bands, no inner shadows. Colors come from {@link GlassTheme}
 * so dark/light mode is automatic.
 */
public final class GlassRender {

    private GlassRender() {}

    /** Default corner radius for panels (any value above 0 draws the notched corner). */
    public static final int RADIUS = 2;

    // ── Core notched primitives ──────────────────────────────────────────────

    public static void roundedRect(DrawContext c, int x1, int y1, int x2, int y2, int r, int color) {
        if (x2 <= x1 || y2 <= y1) return;
        if (r <= 0 || x2 - x1 < 3 || y2 - y1 < 3) { c.fill(x1, y1, x2, y2, color); return; }
        c.fill(x1 + 1, y1, x2 - 1, y2, color);
        c.fill(x1, y1 + 1, x1 + 1, y2 - 1, color);
        c.fill(x2 - 1, y1 + 1, x2, y2 - 1, color);
    }

    /** Notched body with a vertical gradient (top → bottom). Flat when {@code top == bot}. */
    public static void roundedRectGrad(DrawContext c, int x1, int y1, int x2, int y2, int r, int top, int bot) {
        if (top == bot) { roundedRect(c, x1, y1, x2, y2, r, top); return; }
        if (x2 <= x1 || y2 <= y1) return;
        if (r <= 0 || x2 - x1 < 3 || y2 - y1 < 3) { c.fillGradient(x1, y1, x2, y2, top, bot); return; }
        int h = y2 - y1;
        int t1 = GlassTheme.lerp(top, bot, 1f / h), b1 = GlassTheme.lerp(top, bot, (float) (h - 1) / h);
        c.fillGradient(x1 + 1, y1, x2 - 1, y2, top, bot);
        c.fillGradient(x1, y1 + 1, x1 + 1, y2 - 1, t1, b1);
        c.fillGradient(x2 - 1, y1 + 1, x2, y2 - 1, t1, b1);
    }

    public static void roundedBorder(DrawContext c, int x1, int y1, int x2, int y2, int r, int color) {
        if (x2 <= x1 || y2 <= y1) return;
        int n = (r > 0 && x2 - x1 >= 3 && y2 - y1 >= 3) ? 1 : 0;
        c.fill(x1 + n, y1, x2 - n, y1 + 1, color);
        c.fill(x1 + n, y2 - 1, x2 - n, y2, color);
        c.fill(x1, y1 + 1, x1 + 1, y2 - 1, color);
        c.fill(x2 - 1, y1 + 1, x2, y2 - 1, color);
    }

    // ── Composite glass surfaces ─────────────────────────────────────────────

    /**
     * Real blurred backdrop + scrim behind a full-screen menu. Call before drawing panels.
     *
     * <p>Minecraft enforces at most ONE blur per frame ({@code DrawContext.applyBlur}
     * throws {@code IllegalStateException} otherwise). Vanilla {@code Screen.renderBackground}
     * already blurs when the player's menu-blur video setting is ≥1, so we attempt our own
     * blur and swallow the "already blurred" exception. Either way the backdrop ends up
     * blurred and we draw the scrim on top, so the frost shows even with menu blur off.
     */
    public static void menuBackdrop(DrawContext c, int width, int height) {
        try {
            c.applyBlur();
        } catch (IllegalStateException alreadyBlurredThisFrame) {
            // Vanilla renderBackground already blurred this frame; just add the scrim.
        }
        c.fill(0, 0, width, height, GlassTheme.scrim());
    }

    /** Glass panel for a click-open menu (translucent, blur reads through). */
    public static void panel(DrawContext c, int x, int y, int w, int h) {
        panelBody(c, x, y, x + w, y + h, GlassTheme.panelTop(), GlassTheme.rim());
    }

    /** Glass panel for an always-on HUD, alpha-scaled by {@code opacityPct} (0..100). */
    public static void hudPanel(DrawContext c, int x, int y, int w, int h, int opacityPct) {
        panelBody(c, x, y, x + w, y + h,
                GlassTheme.scaleAlpha(GlassTheme.hudTop(), opacityPct),
                GlassTheme.scaleAlpha(GlassTheme.rim(), opacityPct));
    }

    private static void panelBody(DrawContext c, int x1, int y1, int x2, int y2, int body, int rim) {
        roundedRect(c, x1, y1, x2, y2, 1, body);
        roundedBorder(c, x1, y1, x2, y2, 1, rim);
    }

    /** Recessed slot (item cells, search fields, inner wells). */
    public static void slot(DrawContext c, int x1, int y1, int x2, int y2) {
        roundedRect(c, x1, y1, x2, y2, 1, GlassTheme.slot());
        roundedBorder(c, x1, y1, x2, y2, 1, GlassTheme.slotRim());
    }

    /** A list row plate. Transparent at rest, a soft tint when hovered. */
    public static void row(DrawContext c, int x1, int y1, int x2, int y2, boolean hover) {
        if (hover) roundedRect(c, x1, y1, x2, y2, 1, GlassTheme.rowHover());
    }

    /** Selected item in a list or sidebar: ember tint with an ember rim. */
    public static void selected(DrawContext c, int x1, int y1, int x2, int y2) {
        roundedRect(c, x1, y1, x2, y2, 1, GlassTheme.selected());
        roundedBorder(c, x1, y1, x2, y2, 1, GlassTheme.withAlpha(GlassTheme.ACCENT, 0x80));
    }

    /** Hairline horizontal rule in the theme's bronze. */
    public static void rule(DrawContext c, int x1, int x2, int y) {
        c.fill(x1, y, x2, y + 1, GlassTheme.rule());
    }

    /** Hairline vertical rule in the theme's bronze. */
    public static void vrule(DrawContext c, int x, int y1, int y2) {
        c.fill(x, y1, x + 1, y2, GlassTheme.rule());
    }

    /** Generic button body (no label; caller draws text centered via {@link #buttonText}). */
    public static void button(DrawContext c, int x1, int y1, int x2, int y2, boolean hover, boolean active, boolean primary) {
        if (!active) {
            roundedRect(c, x1, y1, x2, y2, 1, GlassTheme.slot());
            roundedBorder(c, x1, y1, x2, y2, 1, GlassTheme.rimSoft());
        } else if (primary) {
            roundedRect(c, x1, y1, x2, y2, 1, hover ? 0xFFFF9A45 : GlassTheme.ACCENT);
        } else {
            roundedRect(c, x1, y1, x2, y2, 1, hover ? GlassTheme.rowHover() : GlassTheme.slot());
            roundedBorder(c, x1, y1, x2, y2, 1, hover ? GlassTheme.rim() : GlassTheme.rimSoft());
        }
    }

    /** Label color matching {@link #button}'s state. */
    public static int buttonText(boolean active, boolean primary) {
        if (!active) return GlassTheme.textMuted();
        return primary ? GlassTheme.INK : GlassTheme.text();
    }

    /** Text-field plate; candle rim when focused. */
    public static void field(DrawContext c, int x1, int y1, int x2, int y2, boolean focused) {
        roundedRect(c, x1, y1, x2, y2, 1, GlassTheme.slot());
        roundedBorder(c, x1, y1, x2, y2, 1, focused ? GlassTheme.ACCENT_SOFT : GlassTheme.slotRim());
    }

    /** Switch: ember track with a light knob on the right when on, ash knob on the left when off. */
    public static void glassSwitch(DrawContext c, int x1, int y1, int x2, int y2, boolean on) {
        roundedRect(c, x1, y1, x2, y2, 1, on ? GlassTheme.ACCENT
                : (GlassTheme.isLight() ? 0x3324130A : 0x40EADFCB));
        int kd = (y2 - y1) - 2;
        int kw = Math.max(kd, (x2 - x1) / 2 - 2);
        int kx = on ? x2 - 1 - kw : x1 + 1;
        roundedRect(c, kx, y1 + 1, kx + kw, y2 - 1, 1,
                on ? 0xFFFFF4E2 : (GlassTheme.isLight() ? 0xFF8A7663 : 0xFFB8AC9C));
    }

    /** Slider rail with ember fill up to {@code frac} (0..1). Caller draws the knob. */
    public static void sliderTrack(DrawContext c, int x1, int y1, int x2, int y2, float frac) {
        c.fill(x1, y1, x2, y2, GlassTheme.slot());
        int fillX2 = x1 + Math.round((x2 - x1) * Math.max(0f, Math.min(1f, frac)));
        if (fillX2 > x1) c.fill(x1, y1, fillX2, y2, GlassTheme.ACCENT);
    }

    /** One-pixel accent strip used at the left of HUD rows. */
    public static void accentStrip(DrawContext c, int x, int topY, int h, int tint) {
        c.fill(x, topY, x + 1, topY + h, tint);
    }

    /** Thin scrollbar (track + bronze thumb). */
    public static void scrollbar(DrawContext c, int x, int top, int bottom, int thumbTop, int thumbH) {
        c.fill(x + 1, top, x + 2, bottom, GlassTheme.rule());
        c.fill(x, thumbTop, x + 3, thumbTop + thumbH, GlassTheme.withAlpha(GlassTheme.BRONZE, 0xCC));
    }

    /**
     * Section heading inside a list: ember label on the left with a bronze hairline running to
     * the right edge of the row. {@code centerX}/{@code rowW} describe the row's span.
     */
    public static void sectionDivider(DrawContext c, TextRenderer fr, int centerX, int rowTop, int rowH, int rowW, String label) {
        int x1 = centerX - rowW / 2, x2 = centerX + rowW / 2;
        int ty = rowTop + rowH - fr.fontHeight - 3;
        c.drawText(fr, Text.literal(label), x1 + 2, ty, GlassTheme.sectionLabel(), false);
        int lx = x1 + 2 + fr.getWidth(label) + 6;
        if (lx < x2) rule(c, lx, x2, ty + fr.fontHeight / 2);
    }
}
