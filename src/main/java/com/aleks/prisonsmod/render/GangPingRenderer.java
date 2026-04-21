package com.aleks.prisonsmod.render;

import com.aleks.prisonsmod.client.ServerAllowlist;
import com.aleks.prisonsmod.client.gangping.GangPing;
import com.aleks.prisonsmod.client.gangping.GangPingInput;
import com.aleks.prisonsmod.client.gangping.GangPingManager;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * World-space renderer for live gang pings. Draws two things per ping:
 * <ul>
 *   <li>A vertical "beam" cross made of two translucent colored quads so
 *       it's visible from every angle, reaching up {@link #BEAM_HEIGHT}
 *       blocks from the ground point.</li>
 *   <li>A billboarded name tag showing the sender (and, on the local
 *       player's live preview, their own name in the theme accent color).</li>
 * </ul>
 *
 * <p>Hooked into Fabric's {@link WorldRenderEvents#AFTER_ENTITIES}, which
 * fires after entity rendering and before the world's translucent pass —
 * the natural spot for translucent world overlays.
 */
public final class GangPingRenderer {

    private static final float BEAM_HEIGHT = 64.0f;
    private static final float BEAM_HALF_WIDTH = 0.2f;
    private static final float NAMETAG_HEIGHT = 2.6f;

    /**
     * Translucent POSITION_COLOR render layer. Built on the stock
     * {@code DEBUG_FILLED_BOX} pipeline (POSITION_COLOR, alpha blend,
     * depth test but no depth write) — exactly what a ghostly beam needs.
     */
    private static final RenderLayer BEAM_LAYER = RenderLayer.of(
            "prisonsmod_gang_ping_beam",
            RenderSetup.builder(RenderPipelines.DEBUG_FILLED_BOX)
                    .translucent()
                    .expectedBufferSize(1536)
                    .build());

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(GangPingRenderer::onRender);
    }

    private static void onRender(WorldRenderContext context) {
        if (!ServerAllowlist.isAllowed()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) return;

        MatrixStack matrices = context.matrices();
        VertexConsumerProvider provider = context.consumers();
        Camera camera = client.gameRenderer.getCamera();
        if (matrices == null || provider == null || camera == null) return;

        long now = System.currentTimeMillis();
        Vec3d camPos = camera.getCameraPos();

        for (GangPing ping : GangPingManager.snapshot()) {
            float alpha = ping.alpha(now);
            if (alpha <= 0.0f) continue;
            renderPing(matrices, provider, camera, camPos,
                    ping.x, ping.y, ping.z, ping.colorRgb, ping.senderName,
                    alpha, client.textRenderer);
        }

        if (GangPingInput.isPreviewActive()) {
            Vec3d target = GangPingInput.getPreviewTarget();
            if (target != null) {
                int previewColor = 0xF59E0B; // theme accent (amber)
                renderPing(matrices, provider, camera, camPos,
                        target.x, target.y, target.z, previewColor,
                        client.player.getName().getString(),
                        0.40f, client.textRenderer);
            }
        }
    }

    private static void renderPing(MatrixStack matrices, VertexConsumerProvider provider,
                                   Camera camera, Vec3d camPos,
                                   double x, double y, double z,
                                   int colorRgb, String label, float alpha,
                                   TextRenderer textRenderer) {
        matrices.push();
        matrices.translate(x - camPos.x, y - camPos.y, z - camPos.z);

        renderBeam(matrices, provider, colorRgb, alpha);
        renderNametag(matrices, provider, camera, label, alpha, textRenderer);

        matrices.pop();
    }

    private static void renderBeam(MatrixStack matrices, VertexConsumerProvider provider,
                                   int colorRgb, float alpha) {
        float r = ((colorRgb >> 16) & 0xFF) / 255.0f;
        float g = ((colorRgb >> 8) & 0xFF) / 255.0f;
        float b = (colorRgb & 0xFF) / 255.0f;
        float a = Math.max(0.0f, Math.min(0.6f, alpha * 0.6f));

        VertexConsumer buf = provider.getBuffer(BEAM_LAYER);
        Matrix4f mat = matrices.peek().getPositionMatrix();

        // Two perpendicular vertical quads form a "cross" beam visible from every angle.
        quadVertical(buf, mat, -BEAM_HALF_WIDTH, 0, BEAM_HALF_WIDTH, 0, BEAM_HEIGHT, r, g, b, a);
        quadVertical(buf, mat, 0, -BEAM_HALF_WIDTH, 0, BEAM_HALF_WIDTH, BEAM_HEIGHT, r, g, b, a);
    }

    private static void quadVertical(VertexConsumer buf, Matrix4f mat,
                                     float x0, float z0, float x1, float z1, float height,
                                     float r, float g, float b, float a) {
        buf.vertex(mat, x0, 0.0f, z0).color(r, g, b, a);
        buf.vertex(mat, x0, height, z0).color(r, g, b, a);
        buf.vertex(mat, x1, height, z1).color(r, g, b, a);
        buf.vertex(mat, x1, 0.0f, z1).color(r, g, b, a);
    }

    private static void renderNametag(MatrixStack matrices, VertexConsumerProvider provider,
                                      Camera camera, String label, float alpha,
                                      TextRenderer textRenderer) {
        if (label == null || label.isEmpty()) return;
        matrices.push();
        matrices.translate(0.0f, NAMETAG_HEIGHT, 0.0f);
        matrices.multiply(camera.getRotation());
        matrices.scale(-0.025f, -0.025f, 0.025f);
        Matrix4f mat = matrices.peek().getPositionMatrix();

        Text text = Text.literal(label);
        float halfWidth = -textRenderer.getWidth(text) / 2.0f;
        int alphaByte = Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));
        int textColor = (alphaByte << 24) | 0xFFFFFF;
        int bgColor = (Math.round(alpha * 0.35f * 255.0f) & 0xFF) << 24;
        textRenderer.draw(text, halfWidth, 0, textColor, false, mat, provider,
                TextRenderer.TextLayerType.SEE_THROUGH, bgColor, 0xF000F0);
        matrices.pop();
    }

    private GangPingRenderer() {}
}
