package com.aleks.prisonsmod.render;

import com.aleks.prisonsmod.client.ServerAllowlist;
import com.aleks.prisonsmod.client.gangping.GangPing;
import com.aleks.prisonsmod.client.gangping.GangPingInput;
import com.aleks.prisonsmod.client.gangping.GangPingManager;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Renders gang and meteor pings.
 *
 * <h2>Beam (world-space)</h2>
 * Drawn in {@link WorldRenderEvents#AFTER_ENTITIES} using the vanilla beacon
 * pipeline so it animates and tints like a real beacon. Cylindrical so it
 * reads from any angle.
 *
 * <h2>Label (HUD)</h2>
 * Sender name + distance + world are rendered on the 2D HUD via
 * {@link HudRenderCallback} at the screen-projected position of the ping. We
 * went HUD-based after the 1.21.11 render-pipeline refactor consistently
 * dropped world-space {@code SEE_THROUGH}/{@code NORMAL} text glyphs queued
 * from {@code AFTER_ENTITIES}. HUD rendering is part of a separate pass with
 * its own pipeline state, so the text always shows. From the player's POV the
 * label still looks anchored to the beam since its screen position tracks the
 * 3D ping coordinates frame-by-frame.
 */
public final class GangPingRenderer {

    private static final Identifier BEAM_TEXTURE =
            Identifier.ofVanilla("textures/entity/beacon_beam.png");

    /** Beam extends from {@code ping_y - BEAM_Y_DOWN} up to {@code ping_y + BEAM_Y_UP}. */
    private static final float BEAM_Y_UP = 96.0f;
    private static final float BEAM_Y_DOWN = 24.0f;
    private static final float BEAM_INNER_RADIUS = 0.22f;
    private static final float BEAM_OUTER_RADIUS = 0.55f;
    private static final int BEAM_SEGMENTS = 16;

    /** World-space offset above the ping point where the projected label anchors. */
    private static final float LABEL_WORLD_Y_OFFSET = 2.6f;

    /** Hide the label entirely once the player gets within this many blocks — at
     *  point-blank range it overlaps the beam and adds visual noise. */
    private static final double LABEL_MIN_DISTANCE = 1.5;

    /** Distance at and below which the label renders at full HUD scale. */
    private static final double LABEL_SCALE_REFERENCE_DISTANCE = 12.0;
    /** Floor so the label stays readable instead of dwindling to nothing far out. */
    private static final float LABEL_SCALE_FLOOR = 0.4f;

    private static final RenderLayer BEAM_LAYER = RenderLayer.of(
            "prisonsmod_gang_ping_beam",
            RenderSetup.builder(RenderPipelines.BEACON_BEAM_TRANSLUCENT)
                    .texture("Sampler0", BEAM_TEXTURE)
                    .translucent()
                    .expectedBufferSize(1536)
                    .build());


    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(GangPingRenderer::onWorldRender);
        HudRenderCallback.EVENT.register(GangPingRenderer::onHudRender);
    }

    // ── World-space beam ─────────────────────────────────────────────────────

    private static void onWorldRender(WorldRenderContext context) {
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

        for (GangPing ping : GangPingManager.snapshot()) {
            float alpha = ping.alpha(now);
            if (alpha <= 0.0f) continue;
            renderBeamAt(matrices, provider, camPos, ping.x, ping.y, ping.z,
                    ping.colorRgb, alpha, now);
        }

        if (GangPingInput.isPreviewActive()) {
            Vec3d target = GangPingInput.computeLiveTarget(client, tickDelta);
            if (target != null) {
                renderBeamAt(matrices, provider, camPos, target.x, target.y, target.z,
                        0xFFFFFF, 0.45f, now);
            }
        }
    }

    private static void renderBeamAt(MatrixStack matrices, VertexConsumerProvider provider,
                                     Vec3d camPos,
                                     double x, double y, double z,
                                     int colorRgb, float alpha, long nowMs) {
        matrices.push();
        matrices.translate(x - camPos.x, y - camPos.y, z - camPos.z);
        renderBeam(matrices, provider, colorRgb, alpha, nowMs);
        matrices.pop();
    }

    // ── HUD-space label ──────────────────────────────────────────────────────

    private static void onHudRender(DrawContext context,
                                    net.minecraft.client.render.RenderTickCounter tickCounter) {
        if (!ServerAllowlist.isAllowed()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) return;
        Camera camera = client.gameRenderer.getCamera();
        if (camera == null) return;

        // Build the view-projection matrix the same way vanilla does for its
        // world-to-screen helper: proj * rotation(camera.rotation.conjugate()).
        // Vertices are pre-translated by -camPos before this transform.
        float fovDeg = (float)(int) client.options.getFov().getValue();
        Matrix4f proj = client.gameRenderer.getBasicProjectionMatrix(fovDeg);
        Quaternionf invRot = camera.getRotation().conjugate(new Quaternionf());
        Matrix4f viewRot = new Matrix4f().rotation(invRot);
        Matrix4f viewProj = new Matrix4f(proj).mul(viewRot);

        long now = System.currentTimeMillis();
        float tickDelta = tickCounter.getTickProgress(true);
        Vec3d playerPos = client.player.getLerpedPos(tickDelta);
        Vec3d camPos = camera.getCameraPos();

        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();

        for (GangPing ping : GangPingManager.snapshot()) {
            float alpha = ping.alpha(now);
            if (alpha <= 0.0f) continue;
            double distSqToPlayer = (ping.x - playerPos.x) * (ping.x - playerPos.x)
                    + (ping.y - playerPos.y) * (ping.y - playerPos.y)
                    + (ping.z - playerPos.z) * (ping.z - playerPos.z);
            if (distSqToPlayer < LABEL_MIN_DISTANCE * LABEL_MIN_DISTANCE) continue;

            drawProjectedLabel(context, client, ping.senderName, ping.worldName,
                    ping.colorRgb, alpha,
                    ping.x, ping.y + LABEL_WORLD_Y_OFFSET, ping.z,
                    playerPos, camPos, viewProj, sw, sh);
        }

        if (GangPingInput.isPreviewActive()) {
            Vec3d target = GangPingInput.computeLiveTarget(client, tickDelta);
            if (target != null) {
                String world = client.world.getRegistryKey() != null
                        ? client.world.getRegistryKey().getValue().getPath() : "";
                drawProjectedLabel(context, client,
                        client.player.getName().getString(), world,
                        0xFFFFFF, 0.45f,
                        target.x, target.y + LABEL_WORLD_Y_OFFSET, target.z,
                        playerPos, camPos, viewProj, sw, sh);
            }
        }
    }

    /**
     * Project a world point onto the screen using the same {@code proj * viewRot}
     * pattern MC's internal world-to-screen helper uses, and, if it lands on
     * screen, draw the multi-line label there.
     */
    private static void drawProjectedLabel(DrawContext context, MinecraftClient client,
                                           String senderName, String worldName,
                                           int colorRgb, float alpha,
                                           double wx, double wy, double wz,
                                           Vec3d playerPos, Vec3d camPos,
                                           Matrix4f viewProj,
                                           int sw, int sh) {
        if (senderName == null || senderName.isEmpty()) return;

        // Pre-translate to camera-relative — MC's view matrix doesn't carry the
        // camera translation; world geometry is rendered camera-relative.
        float rx = (float) (wx - camPos.x);
        float ry = (float) (wy - camPos.y);
        float rz = (float) (wz - camPos.z);

        // Detect behind-camera by checking clip-space w before perspective divide.
        // transformProject divides x/y/z by w internally; if w is negative the
        // result is sign-flipped (point projected from behind the camera).
        float w = viewProj.m03() * rx + viewProj.m13() * ry + viewProj.m23() * rz + viewProj.m33();
        if (w <= 0f) return;

        Vector3f ndc = viewProj.transformProject(new Vector3f(rx, ry, rz));
        if (ndc.x < -1f || ndc.x > 1f) return;
        if (ndc.y < -1f || ndc.y > 1f) return;

        float screenX = (ndc.x + 1.0f) * 0.5f * sw;
        float screenY = (1.0f - ndc.y) * 0.5f * sh;

        TextRenderer tr = client.textRenderer;
        Text senderLine = Text.literal(senderName);
        int distMeters = distanceMeters(playerPos, wx, wy - LABEL_WORLD_Y_OFFSET, wz);
        Text distanceLine = Text.literal(distMeters + "m away");
        String pretty = prettifyWorldName(worldName);
        Text worldLine = pretty.isEmpty() ? null : Text.literal(pretty);

        int aByte = Math.max(0, Math.min(255, Math.round(alpha * 255.0f)));
        int senderColor = (aByte << 24) | (colorRgb & 0xFFFFFF);
        int subColor = (aByte << 24) | 0xCCCCCC;

        // Distance-based scale: full size up to LABEL_SCALE_REFERENCE_DISTANCE,
        // then 1/distance falloff (matches how vanilla entity nameplates shrink
        // with apparent screen size). Floor keeps it readable far out.
        float scale = (float) Math.max(LABEL_SCALE_FLOOR,
                Math.min(1.0, LABEL_SCALE_REFERENCE_DISTANCE / Math.max(distMeters, 1)));

        int lineHeight = tr.fontHeight + 1;

        // Push HUD 2D matrix, translate to anchor, scale, draw lines around origin.
        // Drawing through the scaled matrix shrinks both glyph size and the
        // spacing offsets in lockstep so the stack stays well-proportioned.
        org.joml.Matrix3x2fStack hudMatrices = context.getMatrices();
        hudMatrices.pushMatrix();
        hudMatrices.translate(screenX, screenY);
        hudMatrices.scale(scale, scale);

        drawCenteredLine(context, tr, senderLine, 0, -lineHeight * 2, senderColor);
        drawCenteredLine(context, tr, distanceLine, 0, -lineHeight, subColor);
        if (worldLine != null) {
            drawCenteredLine(context, tr, worldLine, 0, 0, subColor);
        }

        hudMatrices.popMatrix();
    }

    private static void drawCenteredLine(DrawContext context, TextRenderer tr,
                                         Text text, int cx, int y, int color) {
        int w = tr.getWidth(text);
        context.drawText(tr, text, cx - w / 2, y, color, true);
    }

    private static int distanceMeters(Vec3d playerPos, double x, double y, double z) {
        if (playerPos == null) return 0;
        double dx = x - playerPos.x;
        double dy = y - playerPos.y;
        double dz = z - playerPos.z;
        return (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    // ── Beam geometry ────────────────────────────────────────────────────────

    private static void renderBeam(MatrixStack matrices, VertexConsumerProvider provider,
                                   int colorRgb, float alpha, long nowMs) {
        float r = ((colorRgb >> 16) & 0xFF) / 255.0f;
        float g = ((colorRgb >> 8) & 0xFF) / 255.0f;
        float b = (colorRgb & 0xFF) / 255.0f;

        VertexConsumer buf = provider.getBuffer(BEAM_LAYER);
        MatrixStack.Entry entry = matrices.peek();

        float totalHeight = BEAM_Y_UP + BEAM_Y_DOWN;
        float yBottom = -BEAM_Y_DOWN;

        float vScroll = (nowMs % 4000L) / 4000.0f;
        float vTop = vScroll + totalHeight * 0.5f;

        float innerAlpha = Math.max(0.0f, Math.min(1.0f, alpha * 0.95f));
        float outerAlpha = Math.max(0.0f, Math.min(1.0f, alpha * 0.35f));

        drawCylinder(buf, entry, BEAM_INNER_RADIUS, yBottom, totalHeight, r, g, b, innerAlpha, vScroll, vTop);
        drawCylinder(buf, entry, BEAM_OUTER_RADIUS, yBottom, totalHeight, r, g, b, outerAlpha, vScroll, vTop);
    }

    private static void drawCylinder(VertexConsumer buf, MatrixStack.Entry entry,
                                     float radius, float yBottom, float height,
                                     float r, float g, float b, float a,
                                     float v0, float v1) {
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

    /**
     * Convert a registry-id path like {@code forgotten_polis} to a nicely
     * title-cased label like {@code Forgotten Polis}.
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
