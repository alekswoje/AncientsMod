package com.aleks.prisonsmod.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;

/**
 * Tracks vertical scroll offset for oversized tooltips.
 *
 * <p>When a tooltip's total height exceeds the screen height, vanilla clamps
 * the tooltip at the top and cuts off the bottom. This class tracks a scroll
 * offset so the {@link com.aleks.prisonsmod.mixin.client.HoveredTooltipPositionerMixin}
 * can shift the tooltip vertically — revealing otherwise-hidden portions —
 * while a {@link net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents}
 * handler consumes scroll wheel input to drive the offset.
 *
 * <p>Offset range: {@code [-(tooltipHeight - screenHeight + PADDING), 0]}.
 * Zero means tooltip is in its natural position; negative values slide the
 * tooltip up so the bottom becomes visible.
 *
 * <p>Offset resets to zero automatically whenever a non-oversized tooltip
 * renders — i.e. moving the cursor to a normal-sized item clears the
 * scroll state.
 */
public final class TooltipScroll {

    private static final int PADDING_PX = 8;
    private static final int SCROLL_STEP_PX = 12;

    private static int offset = 0;
    private static int lastTooltipHeight = 0;
    private static int lastScreenHeight = 0;

    public static void captureRender(int screenHeight, int tooltipHeight) {
        lastScreenHeight = screenHeight;
        lastTooltipHeight = tooltipHeight;
        if (!isOversized()) offset = 0;
    }

    public static boolean isOversized() {
        return lastTooltipHeight > lastScreenHeight && lastScreenHeight > 0;
    }

    public static void scroll(double verticalAmount) {
        if (!isOversized()) return;
        int delta = -(int) Math.round(verticalAmount * SCROLL_STEP_PX);
        int minOffset = -(lastTooltipHeight - lastScreenHeight + PADDING_PX);
        int newOffset = offset + delta;
        if (newOffset > 0) newOffset = 0;
        if (newOffset < minOffset) newOffset = minOffset;
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
                    if (!isOversized()) return true;
                    scroll(verticalAmount);
                    return false;
                }
            )
        );
    }

    private TooltipScroll() {}
}
