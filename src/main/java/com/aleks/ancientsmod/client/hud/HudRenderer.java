package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.client.ServerAllowlist;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Renders every registered {@link HudElement} on the 2D HUD. Skipped entirely
 * when the player isn't on a AncientsMod-allowed server (dormant on vanilla).
 *
 * <p>The {@link HudEditScreen} is a separate path that handles its own
 * rendering (so it can show the placeholder for invisible widgets and overlay
 * the drag selection) — this renderer is only for normal play.
 */
public final class HudRenderer {

    private HudRenderer() {}

    public static void register() {
        HudRenderCallback.EVENT.register(HudRenderer::onHudRender);
    }

    private static void onHudRender(DrawContext ctx, net.minecraft.client.render.RenderTickCounter counter) {
        if (!ServerAllowlist.isAllowed()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;
        // Don't render over a fullscreen GUI (chat, inventory, pause menu) — the HUD
        // sits behind those by convention.
        if (mc.currentScreen != null && !(mc.currentScreen instanceof HudEditScreen)) return;
        if (mc.currentScreen instanceof HudEditScreen) return; // editor handles its own draw

        int screenW = ctx.getScaledWindowWidth();
        int screenH = ctx.getScaledWindowHeight();
        float tickDelta = counter.getTickProgress(true);

        for (HudElement el : HudRegistry.all()) {
            if (!el.isVisible()) continue;
            drawElement(ctx, mc, el, screenW, screenH, tickDelta);
        }
    }

    /** Shared draw helper — invoked by both the live renderer and the edit-mode preview. */
    static void drawElement(DrawContext ctx, MinecraftClient mc, HudElement el,
                            int screenW, int screenH, float tickDelta) {
        int x = HudPositions.getX(el, screenW);
        int y = HudPositions.getY(el, screenH);
        float scale = HudPositions.getScale(el);

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        if (scale != 1.0f) ctx.getMatrices().scale(scale, scale);
        try {
            el.render(ctx, mc.textRenderer, tickDelta);
        } finally {
            ctx.getMatrices().popMatrix();
        }
    }
}
