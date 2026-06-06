package com.aleks.prisonsmod.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

/**
 * Tracks vertical scroll offset for oversized tooltips.
 *
 * <p>When a tooltip's total height exceeds the screen height, vanilla
 * {@link net.minecraft.client.gui.tooltip.HoveredTooltipPositioner} pins
 * the bottom of the tooltip on-screen (with a 3px padding) and lets the
 * top spill off-screen above — so the item name and the first lines of
 * lore are hidden. This class tracks a scroll offset so the
 * {@link com.aleks.prisonsmod.mixin.client.HoveredTooltipPositionerMixin}
 * can shift the tooltip vertically — revealing the otherwise-hidden top —
 * while a {@link net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents}
 * handler consumes scroll wheel input to drive the offset.
 *
 * <p>Offset range: {@code [0, +(tooltipHeight - screenHeight + PADDING)]}.
 * Zero means tooltip is in its natural (bottom-pinned) position; positive
 * values slide the tooltip down so the top becomes visible.
 *
 * <p>Wheel direction follows the standard convention: wheeling up scrolls
 * toward the top of the content (i.e. increases the offset, sliding the
 * tooltip down to reveal the top); wheeling down returns toward the
 * vanilla bottom-pinned view.
 *
 * <p>Offset resets to zero automatically whenever a non-oversized tooltip
 * renders — i.e. moving the cursor to a normal-sized item clears the
 * scroll state.
 */
public final class TooltipScroll {

    private static final int PADDING_PX = 8;
    private static final int SCROLL_STEP_PX = 12;

    /**
     * How recently a tooltip must have rendered for the scroll handler to
     * treat the cursor as "over an oversized tooltip". Tooltips re-render
     * every frame while hovered, so a live one keeps this fresh continuously;
     * once the cursor leaves the item (or the screen changes) nothing calls
     * {@link #captureRender}, the timestamp goes stale within a couple of
     * frames, and scroll falls back through to the screen (chat history, item
     * lists, …). Without this gate the last oversized tooltip's state lingered
     * forever and silently ate scroll in chat and other screens.
     */
    private static final long FRESH_WINDOW_MS = 150;

    private static int offset = 0;
    private static int lastTooltipHeight = 0;
    private static int lastScreenHeight = 0;
    private static long lastRenderMillis = 0L;

    public static void captureRender(int screenHeight, int tooltipHeight) {
        lastScreenHeight = screenHeight;
        lastTooltipHeight = tooltipHeight;
        lastRenderMillis = System.currentTimeMillis();
        if (!isOversized()) offset = 0;
    }

    public static boolean isOversized() {
        return lastTooltipHeight > lastScreenHeight && lastScreenHeight > 0;
    }

    /**
     * True only while an oversized tooltip is actually on screen — oversized
     * AND rendered within the last {@link #FRESH_WINDOW_MS}. The scroll
     * handler gates on this (not bare {@link #isOversized()}) so it stops
     * swallowing scroll the instant the tooltip is gone.
     */
    public static boolean isActivelyOversized() {
        return isOversized()
            && (System.currentTimeMillis() - lastRenderMillis) <= FRESH_WINDOW_MS;
    }

    public static void scroll(double verticalAmount) {
        if (!isOversized()) return;
        int delta = (int) Math.round(verticalAmount * SCROLL_STEP_PX);
        int maxOffset = lastTooltipHeight - lastScreenHeight + PADDING_PX;
        int newOffset = offset + delta;
        if (newOffset < 0) newOffset = 0;
        if (newOffset > maxOffset) newOffset = maxOffset;
        offset = newOffset;
    }

    public static int getOffset() {
        return offset;
    }

    public static void reset() {
        offset = 0;
        lastTooltipHeight = 0;
        lastScreenHeight = 0;
    }

    /**
     * Hook mouse scroll on every screen. When an oversized tooltip is
     * being rendered, consume the scroll event and shift the tooltip
     * instead of forwarding scroll to the underlying screen.
     */
    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
            ScreenMouseEvents.allowMouseScroll(screen).register(
                (s, mouseX, mouseY, horizontalAmount, verticalAmount) -> {
                    if (!ServerAllowlist.isAllowed()) return true;
                    if (!FeatureToggles.isScrollableTooltipsEnabled()) return true;
                    if (!isActivelyOversized()) return true;
                    scroll(verticalAmount);
                    return false;
                }
            )
        );
    }

    private TooltipScroll() {}
}
