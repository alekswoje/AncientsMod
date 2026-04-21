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
 * vanilla flushes reliably every frame. Drawn as an N-segment cylinder so
 * it reads as a rounded pillar from every viewing angle (rather than a
 * flat quad that edge-culls at shallow angles). A billboarded name tag
 * floats above the beam showing the pinger's name in the ping color.
 *
 * <p>While a player is holding the keybind past the hold threshold, a
 * low-opacity preview beam in the theme amber color tracks their cursor.
 * The preview target is re-raycast every render frame so it follows the
 * view smoothly at the display refresh rate (not the 20 Hz tick rate).
 */
public final class GangPingRenderer {

    private static final Identifier BEAM_TEXTURE =
            Identifier.ofVanilla("textures/entity/beacon_beam.png");

    /** Beam extends from {@code ping_y - BEAM_Y_DOWN} up to {@code ping_y + BEAM_Y_UP}
     *  so players below the ping point still see the bottom of the pillar when the
     *  ping lands in open air. */
    private static final float BEAM_Y_UP = 96.0f;
    private static final float BEAM_Y_DOWN = 24.0f;
    private static final float BEAM_INNER_RADIUS = 0.22f;
    private static final float BEAM_OUTER_RADIUS = 0.55f;
    /** Quads around the circumference. 16 reads as a smooth cylinder at normal view distances. */
    private static final int BEAM_SEGMENTS = 16;

    private static final float NAMETAG_BASE_OFFSET = 2.6f;
    /** World-space vertical gap between stacked nametag lines. */
    private static final float NAMETAG_LINE_SPACING = 0.32f;
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

        float tickDelta = client.getRenderTickCounter().getTickProgress(true);
        Vec3d playerPos = client.player.getLerpedPos(tickDelta);

        for (GangPing ping : GangPingManager.snapshot()) {
            float alpha = ping.alpha(now);
            if (alpha <= 0.0f) continue;
            renderPing(matrices, provider, camera, camPos, playerPos,
                    ping.x, ping.y, ping.z, ping.colorRgb, ping.senderName, ping.worldName,
                    alpha, client.textRenderer, now);
        }

        if (GangPingInput.isPreviewActive()) {
            Vec3d target = GangPingInput.computeLiveTarget(client, tickDelta);
            if (target != null) {
                int previewColor = 0xF59E0B; // theme amber accent
                String worldName = client.world != null && client.world.getRegistryKey() != null
                        ? client.world.getRegistryKey().getValue().getPath()
                        : "";
                renderPing(matrices, provider, camera, camPos, playerPos,
                        target.x, target.y, target.z, previewColor,
                        client.player.getName().getString(), worldName,
                        0.45f, client.textRenderer, now);
            }
        }
    }

    private static void renderPing(MatrixStack matrices, VertexConsumerProvider provider,
                                   Camera camera, Vec3d camPos, Vec3d playerPos,
                                   double x, double y, double z,
                                   int colorRgb, String senderName, String worldName,
                                   float alpha,
                                   TextRenderer textRenderer, long nowMs) {
        matrices.push();
        matrices.translate(x - camPos.x, y - camPos.y, z - camPos.z);

        renderBeam(matrices, provider, colorRgb, alpha, nowMs);
        int distance = distanceMeters(playerPos, x, y, z);
        renderNametag(matrices, provider, camera, senderName, worldName, distance,
                colorRgb, alpha, textRenderer);

        matrices.pop();
    }

    private static int distanceMeters(Vec3d playerPos, double x, double y, double z) {
        if (playerPos == null) return 0;
        double dx = x - playerPos.x;
        double dy = y - playerPos.y;
        double dz = z - playerPos.z;
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    private static void renderBeam(MatrixStack matrices, VertexConsumerProvider provider,
                                   int colorRgb, float alpha, long nowMs) {
        float r = ((colorRgb >> 16) & 0xFF) / 255.0f;
        float g = ((colorRgb >> 8) & 0xFF) / 255.0f;
        float b = (colorRgb & 0xFF) / 255.0f;

        VertexConsumer buf = provider.getBuffer(BEAM_LAYER);
        MatrixStack.Entry entry = matrices.peek();

        float totalHeight = BEAM_Y_UP + BEAM_Y_DOWN;
        float yBottom = -BEAM_Y_DOWN;

        // UV V coordinate scrolls with time so the beam looks animated.
        float vScroll = (nowMs % 4000L) / 4000.0f;
        float vTop = vScroll + totalHeight * 0.5f;

        // Inner (bright core) and outer (soft glow) concentric cylinders.
        float innerAlpha = Math.max(0.0f, Math.min(1.0f, alpha * 0.95f));
        float outerAlpha = Math.max(0.0f, Math.min(1.0f, alpha * 0.35f));

        drawCylinder(buf, entry, BEAM_INNER_RADIUS, yBottom, totalHeight, r, g, b, innerAlpha, vScroll, vTop);
        drawCylinder(buf, entry, BEAM_OUTER_RADIUS, yBottom, totalHeight, r, g, b, outerAlpha, vScroll, vTop);
    }

    private static void drawCylinder(VertexConsumer buf, MatrixStack.Entry entry,
                                     float radius, float yBottom, float height,
                                     float r, float g, float b, float a,
                                     float v0, float v1) {
        // N-sided prism around the Y axis. Each segment is wound CCW when
        // viewed from outside the cylinder, matching vanilla beacon-face
        // winding, so the beacon pipeline renders them as front faces.
        final double step = (Math.PI * 2.0) / BEAM_SEGMENTS;
        for (int i = 0; i < BEAM_SEGMENTS; i++) {
            double t0 = i * step;
            double t1 = (i + 1) * step;
            float x0 = (float) (Math.cos(t0) * radius);
            float z0 = (float) (Math.sin(t0) * radius);
            float x1 = (float) (Math.cos(t1) * radius);
            float z1 = (float) (Math.sin(t1) * radius);
            quad(buf, entry, x0, z0, x1, z1, yBottom, height, r, g, b, a, v0, v1);
        }
    }

    private static void quad(VertexConsumer buf, MatrixStack.Entry entry,
                             float x0, float z0, float x1, float z1,
                             float yBottom, float height,
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
        float yTop = yBottom + height;
        buf.vertex(mat, x0, yBottom, z0).color(r, g, b, a).texture(0f, v0)
                .overlay(overlay).light(light).normal(entry, 0f, 1f, 0f);
        buf.vertex(mat, x0, yTop, z0).color(r, g, b, a).texture(0f, v1)
                .overlay(overlay).light(light).normal(entry, 0f, 1f, 0f);
        buf.vertex(mat, x1, yTop, z1).color(r, g, b, a).texture(1f, v1)
                .overlay(overlay).light(light).normal(entry, 0f, 1f, 0f);
        buf.vertex(mat, x1, yBottom, z1).color(r, g, b, a).texture(1f, v0)
                .overlay(overlay).light(light).normal(entry, 0f, 1f, 0f);
    }

    private static void renderNametag(MatrixStack matrices, VertexConsumerProvider provider,
                                      Camera camera, String senderName, String worldName,
                                      int distanceMeters,
                                      int colorRgb, float alpha,
                                      TextRenderer textRenderer) {
        if (senderName == null || senderName.isEmpty()) return;

        Text senderLine = Text.literal(senderName);
        Text distanceLine = Text.literal(distanceMeters + "m away");
        String pretty = prettifyWorldName(worldName);
        Text worldLine = pretty.isEmpty() ? null : Text.literal(pretty);

        int alphaByte = Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));
        int senderColor = (alphaByte << 24) | (colorRgb & 0xFFFFFF);
        int subColor = (alphaByte << 24) | 0xCCCCCC;
        int bgAlphaByte = Math.round(alpha * 0.55f * 255.0f) & 0xFF;
        int bgColor = (bgAlphaByte << 24) | 0x000000;

        // Each line is its own billboard transform so the world-space
        // stacking isn't subject to the -Y text-scale flip. Top line is
        // highest in the world.
        float y = NAMETAG_BASE_OFFSET + 2 * NAMETAG_LINE_SPACING;
        drawBillboardedLine(matrices, provider, camera, textRenderer,
                senderLine, y, senderColor, bgColor);
        y -= NAMETAG_LINE_SPACING;
        drawBillboardedLine(matrices, provider, camera, textRenderer,
                distanceLine, y, subColor, bgColor);
        if (worldLine != null) {
            y -= NAMETAG_LINE_SPACING;
            drawBillboardedLine(matrices, provider, camera, textRenderer,
                    worldLine, y, subColor, bgColor);
        }
    }

    private static void drawBillboardedLine(MatrixStack matrices, VertexConsumerProvider provider,
                                            Camera camera, TextRenderer textRenderer,
                                            Text text, float worldY,
                                            int textColor, int bgColor) {
        matrices.push();
        matrices.translate(0.0f, worldY, 0.0f);
        matrices.multiply(camera.getRotation());
        matrices.scale(-NAMETAG_SCALE, -NAMETAG_SCALE, NAMETAG_SCALE);
        Matrix4f mat = matrices.peek().getPositionMatrix();
        float halfWidth = textRenderer.getWidth(text) / 2.0f;
        textRenderer.draw(text, -halfWidth, 0f, textColor, false, mat, provider,
                TextRenderer.TextLayerType.SEE_THROUGH, bgColor, 0xF000F0);
        matrices.pop();
    }

    /**
     * Convert a registry-id path like {@code forgotten_polis} to a nicely
     * title-cased label like {@code Forgotten Polis}. Empty or null inputs
     * return empty string so callers can skip rendering cleanly.
     */
    public static String prettifyWorldName(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String[] parts = raw.split("[_\\s-]+");
        StringBuilder out = new StringBuilder(raw.length());
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) out.append(part.substring(1).toLowerCase());
        }
        return out.toString();
    }

    private GangPingRenderer() {}
}
