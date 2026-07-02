package com.aleks.ancientsmod.client.glass;

import net.minecraft.client.gui.DrawContext;

/**
 * Reusable draggable scrollbar. Each frame, the owning screen calls {@link #render} with its
 * current scroll geometry; this draws the bar and remembers the thumb bounds so a subsequent
 * {@link #mousePressed}/{@link #scrollFor}/{@link #release} can map a drag (or a track click)
 * to a new {@code scrollY}. The screen keeps owning its own {@code scrollY} field — this helper
 * only does the geometry + drag math.
 *
 * <p>The hit zone is intentionally wider than the 3px visual bar so the thumb is easy to grab.
 */
public class GlassScrollbar {

    private int x, top, bottom, thumbY, thumbH;
    private boolean visible, dragging;
    private int grab;

    /** Draw the bar for the given content/scroll. No-op + not draggable when the content fits. */
    public void render(DrawContext c, int x, int top, int bottom, int contentHeight, int scrollY) {
        int viewH = bottom - top;
        this.visible = contentHeight > viewH && viewH > 0;
        if (!visible) return;
        this.x = x;
        this.top = top;
        this.bottom = bottom;
        this.thumbH = Math.max(20, (int) ((long) viewH * viewH / contentHeight));
        int travel = viewH - thumbH;
        int maxScroll = Math.max(1, contentHeight - viewH);
        int sy = Math.max(0, Math.min(maxScroll, scrollY));
        this.thumbY = top + (travel <= 0 ? 0 : (int) ((long) sy * travel / maxScroll));
        GlassRender.scrollbar(c, x, top, bottom, thumbY, thumbH);
    }

    /** Generous hit zone around the thin visual bar. */
    public boolean isOver(double mx, double my) {
        return visible && mx >= x - 4 && mx <= x + 7 && my >= top && my <= bottom;
    }

    /** Begin dragging if the press lands on the bar. Track clicks jump the thumb to the cursor. */
    public boolean mousePressed(double mx, double my) {
        if (!isOver(mx, my)) return false;
        grab = (my >= thumbY && my <= thumbY + thumbH) ? (int) (my - thumbY) : thumbH / 2;
        dragging = true;
        return true;
    }

    public boolean isDragging() { return dragging; }

    public void release() { dragging = false; }

    /** Map the current mouse-y to a clamped {@code scrollY} for the given content height. */
    public int scrollFor(double my, int contentHeight) {
        int viewH = bottom - top;
        int travel = viewH - thumbH;
        if (travel <= 0) return 0;
        int maxScroll = Math.max(0, contentHeight - viewH);
        int newThumbY = (int) Math.round(my - grab);
        newThumbY = Math.max(top, Math.min(top + travel, newThumbY));
        return (int) Math.round((double) (newThumbY - top) * maxScroll / travel);
    }
}
