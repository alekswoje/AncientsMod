package com.aleks.ancientsmod.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;

/**
 * Draws a slim saturation bar directly above the vanilla hunger bar.
 *
 * <p>Saturation is the hidden buffer that drains before hunger does — vanilla
 * never shows it, so you can't tell a "just ate, good for ages" full bar from a
 * "one sprint away from dropping a haunch" full bar. This overlays a 2px strip
 * on the hunger row whose width tracks {@code saturation / 20}, anchored to the
 * right like the hunger icons themselves.
 *
 * <p>Client-only: the vanilla health-update packet already carries saturation,
 * so nothing new goes over the wire. Off by default; toggle in Settings → HUDs.
 *
 * <p>Geometry mirrors {@code InGameHud#renderStatusBars} / {@code renderFood}:
 * the food row's right edge is {@code width/2 + 91}, its top is
 * {@code height - 39}, and the ten 9px icons span 81px leftwards from there.
 */
public final class SaturationOverlay {

    /** Right edge of the hunger row, relative to screen centre. */
    private static final int FOOD_RIGHT_OFFSET = 91;
    /** Distance from the bottom of the screen to the top of the hunger icons. */
    private static final int FOOD_TOP_OFFSET = 39;
    /** Total pixel span of the ten hunger icons. */
    private static final int FOOD_BAR_WIDTH = 81;
    /** Height of the saturation strip. */
    private static final int BAR_H = 2;
    /** Gap between the strip and the top of the hunger icons. */
    private static final int BAR_GAP = 1;

    private static final float MAX_SATURATION = 20.0f;

    private static final int FILL_COLOR  = 0xFFFFC857; // amber, reads as "food"
    private static final int TRACK_COLOR = 0x66000000; // faint backing so 0 saturation is still legible

    private SaturationOverlay() {}

    public static void register() {
        HudRenderCallback.EVENT.register((ctx, counter) -> onHudRender(ctx));
    }

    private static void onHudRender(DrawContext ctx) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!FeatureToggles.isSaturationOverlayEnabled()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.interactionManager == null) return;
        if (mc.currentScreen != null) return;
        if (mc.options.hudHidden) return;
        // Creative / spectator have no status bars at all.
        if (!mc.interactionManager.hasStatusBars()) return;

        ClientPlayerEntity player = mc.player;
        // Riding something with health replaces the hunger row with mount health.
        if (player.getVehicle() instanceof LivingEntity mount && mount.getMaxHealth() > 0.0f) return;

        float saturation = player.getHungerManager().getSaturationLevel();
        if (saturation < 0.0f) saturation = 0.0f;
        if (saturation > MAX_SATURATION) saturation = MAX_SATURATION;

        int right = ctx.getScaledWindowWidth() / 2 + FOOD_RIGHT_OFFSET;
        int left = right - FOOD_BAR_WIDTH;
        int top = ctx.getScaledWindowHeight() - FOOD_TOP_OFFSET - BAR_GAP - BAR_H;

        ctx.fill(left, top, right, top + BAR_H, TRACK_COLOR);

        int filled = Math.round(FOOD_BAR_WIDTH * (saturation / MAX_SATURATION));
        if (filled <= 0) return;
        // Anchor right, grow left — the same direction the hunger icons fill.
        ctx.fill(right - filled, top, right, top + BAR_H, FILL_COLOR);
    }
}
