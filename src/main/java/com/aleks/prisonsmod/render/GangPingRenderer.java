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
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * World-space renderer for live gang pings.
 *
 * <p>The beam reuses vanilla's beacon-beam render pipeline
 * ({@link RenderPipelines#BEACON_BEAM_TRANSLUCENT}) and texture so it
 * animates and tints identically to a real beacon — proven code path that
 * vanilla flushes reliably every frame. Drawn as a cross of two quads so
 * it's visible from every angle. A billboarded name tag floats above the
 * beam showing the pinger's name in the ping color.
 *
 * <p>While a player is holding the keybind past the hold threshold, a
 * low-opacity preview beam in the theme amber color tracks their cursor.
 */
public final class GangPingRenderer {

    private static final Identifier BEAM_TEXTURE =
            Identifier.ofVanilla("textures/entity/beacon_beam.png");

    private static final float BEAM_HEIGHT = 64.0f;
    private static final float BEAM_INNER_HALF = 0.2f;
    private static final float BEAM_OUTER_HALF = 0.55f;
    private static final float NAMETAG_OFFSET = 2.6f;
    private static final float NAMETAG_SCALE = 0.04f;

    /**
     * Translucent beam layer using the beacon pipeline. Vanilla already wires
     * this pipeline through the normal translucent draw pass, which means the
     * immediate buffer provider always flushes it — we don't need to manage a
     * custom flush cycle.
     */
    private static final RenderLayer BEAM_LAYER = RenderLayer.of(
            "prisonsmod_gang_ping_beam",
            RenderSetup.builder(RenderPipelines.BEACON_BEAM_TRANSLUCENT)
                    .texture("Sampler0", BEAM_TEXTURE)
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
                    alpha, client.textRenderer, now);
        }

        if (GangPingInput.isPreviewActive()) {
            Vec3d target = GangPingInput.getPreviewTarget();
            if (target != null) {
                int previewColor = 0xF59E0B; // theme amber accent
                renderPing(matrices, provider, camera, camPos,
                        target.x, target.y, target.z, previewColor,
                        client.player.getName().getString(),
                        0.45f, client.textRenderer, now);
            }
        }
    }

    private static void renderPing(MatrixStack matrices, VertexConsumerProvider provider,
                                   Camera camera, Vec3d camPos,
                                   double x, double y, double z,
                                   int colorRgb, String label, float alpha,
                                   TextRenderer textRenderer, long nowMs) {
        matrices.push();
        matrices.translate(x - camPos.x, y - camPos.y, z - camPos.z);

        renderBeam(matrices, provider, colorRgb, alpha, nowMs);
        renderNametag(matrices, provider, camera, label, colorRgb, alpha, textRenderer);

        matrices.pop();
    }

    private static void renderBeam(MatrixStack matrices, VertexConsumerProvider provider,
                                   int colorRgb, float alpha, long nowMs) {
        float r = ((colorRgb >> 16) & 0xFF) / 255.0f;
        float g = ((colorRgb >> 8) & 0xFF) / 255.0f;
        float b = (colorRgb & 0xFF) / 255.0f;

        VertexConsumer buf = provider.getBuffer(BEAM_LAYER);
        MatrixStack.Entry entry = matrices.peek();

        // UV V coordinate scrolls with time so the beam looks animated.
        float vScroll = (nowMs % 4000L) / 4000.0f;
        float vTop = vScroll + BEAM_HEIGHT * 0.5f;

        // Inner (bright, opaque) and outer (soft glow) cross quads.
        float innerAlpha = Math.max(0.0f, Math.min(1.0f, alpha * 0.95f));
        float outerAlpha = Math.max(0.0f, Math.min(1.0f, alpha * 0.35f));

        drawCrossQuads(buf, entry, BEAM_INNER_HALF, BEAM_HEIGHT, r, g, b, innerAlpha, vScroll, vTop);
        drawCrossQuads(buf, entry, BEAM_OUTER_HALF, BEAM_HEIGHT, r, g, b, outerAlpha, vScroll, vTop);
    }

    private static void drawCrossQuads(VertexConsumer buf, MatrixStack.Entry entry,
                                       float halfWidth, float height,
                                       float r, float g, float b, float a,
                                       float v0, float v1) {
        // Two perpendicular vertical quads so the beam looks cylindrical from
        // every angle.
        quad(buf, entry, -halfWidth, 0f, halfWidth, 0f, height, r, g, b, a, v0, v1);
        quad(buf, entry, 0f, -halfWidth, 0f, halfWidth, height, r, g, b, a, v0, v1);
    }

    private static void quad(VertexConsumer buf, MatrixStack.Entry entry,
                             float x0, float z0, float x1, float z1, float height,
                             float r, float g, float b, float a,
                             float v0, float v1) {
        // BEACON_BEAM_TRANSLUCENT expects the full block/entity vertex
        // format: position, color, uv, overlay, light, normal. Emissive beam
        // so we just flood the lightmap to full brightness and use an up
        // normal — neither is actually sampled by the beam shader but the
        // buffer validator rejects the vertex if they're omitted.
        Matrix4f mat = entry.getPositionMatrix();
        int light = 0xF000F0;
        int overlay = OverlayTexture.DEFAULT_UV;
        buf.vertex(mat, x0, 0f, z0).color(r, g, b, a).texture(0f, v0)
                .overlay(overlay).light(light).normal(entry, 0f, 1f, 0f);
        buf.vertex(mat, x0, height, z0).color(r, g, b, a).texture(0f, v1)
                .overlay(overlay).light(light).normal(entry, 0f, 1f, 0f);
        buf.vertex(mat, x1, height, z1).color(r, g, b, a).texture(1f, v1)
                .overlay(overlay).light(light).normal(entry, 0f, 1f, 0f);
        buf.vertex(mat, x1, 0f, z1).color(r, g, b, a).texture(1f, v0)
                .overlay(overlay).light(light).normal(entry, 0f, 1f, 0f);
    }

    private static void renderNametag(MatrixStack matrices, VertexConsumerProvider provider,
                                      Camera camera, String label, int colorRgb, float alpha,
                                      TextRenderer textRenderer) {
        if (label == null || label.isEmpty()) return;
        Text text = Text.literal(label);

        matrices.push();
        matrices.translate(0.0f, NAMETAG_OFFSET, 0.0f);
        matrices.multiply(camera.getRotation());
        matrices.scale(-NAMETAG_SCALE, -NAMETAG_SCALE, NAMETAG_SCALE);
        Matrix4f mat = matrices.peek().getPositionMatrix();

        int alphaByte = Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));
        int textColor = (alphaByte << 24) | (colorRgb & 0xFFFFFF);
        int bgAlphaByte = Math.round(alpha * 0.45f * 255.0f) & 0xFF;
        int bgColor = (bgAlphaByte << 24) | 0x000000;
        float halfWidth = -textRenderer.getWidth(text) / 2.0f;
        textRenderer.draw(text, halfWidth, 0, textColor, false, mat, provider,
                TextRenderer.TextLayerType.SEE_THROUGH, bgColor, 0xF000F0);
        matrices.pop();
    }

    private GangPingRenderer() {}
}
