package com.aleks.prisonsmod.render;

import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.ServerAllowlist;
import com.aleks.prisonsmod.client.hud.MeteoriteState;
import com.aleks.prisonsmod.mixin.client.GameRendererInvoker;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Floating "247 Emerald" label drawn above every meteorite block the local
 * player has heard about. Pure cosmetic — the count and tier come from the
 * server's {@code PKT_METEORITE_HUD} broadcast; the renderer projects the
 * block centre to screen and writes text there.
 *
 * <p>Uses the same HUD-pass projection trick as {@link GangPingRenderer}: the
 * world-render text pipeline drops glyphs after the 1.21.11 refactor, so the
 * label rides the 2D HUD draw context and re-projects each frame to stay
 * anchored above the block.
 */
public final class MeteoriteLabelRenderer {

    /**
     * Anchor the label this many blocks above the meteorite's centre. Kept
     * small (top-face of the block) — anything taller projects off the top
     * of the screen at point-blank distances where vFOV/2 ≈ 35° means a
     * 1-block offset already eats half the screen.
     */
    private static final float LABEL_WORLD_Y_OFFSET = 0.5f;

    /** Below this many blocks distance the label is hidden — too close = visual noise. */
    private static final double LABEL_MIN_DISTANCE = 0.5;

    /** Cull labels past this radius — at >96 blocks the server has already stopped sending us updates anyway. */
    private static final double LABEL_MAX_DISTANCE = 96.0;

    /** Full-size up to this many blocks; beyond this the label shrinks 1/d so distant meteorites don't dominate. */
    private static final double LABEL_SCALE_REFERENCE_DISTANCE = 6.0;
    /** Floor so 90-block-away labels are tiny but still legible (≈ 1/3 readable size). */
    private static final float LABEL_SCALE_FLOOR = 0.15f;

    private static final int COUNT_COLOR   = 0xFFFFFFFF;
    private static final int SUFFIX_COLOR  = 0xFFCCCCCC;
    private static final int REFINED_COLOR = 0xFFFFD68A;

    public static void register() {
        HudRenderCallback.EVENT.register(MeteoriteLabelRenderer::onHudRender);
    }

    private static void onHudRender(DrawContext context,
                                    net.minecraft.client.render.RenderTickCounter tickCounter) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!FeatureToggles.isMeteoriteHudEnabled()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) return;
        Camera camera = client.gameRenderer.getCamera();
        if (camera == null) return;

        var snapshots = MeteoriteState.entries();
        if (snapshots.isEmpty()) return;

        float tickDelta = tickCounter.getTickProgress(true);
        Vec3d playerPos = client.player.getLerpedPos(tickDelta);
        Vec3d camPos = camera.getCameraPos();

        // Mirror vanilla's world-render projection (FOV at the same tickDelta);
        // see GangPingRenderer for the rationale.
        float fov = ((GameRendererInvoker) (Object) client.gameRenderer)
                .prisonsmod$getFov(camera, tickDelta, true);
        Matrix4f proj = client.gameRenderer.getBasicProjectionMatrix(fov);
        Quaternionf invRot = camera.getRotation().conjugate(new Quaternionf());
        Matrix4f viewRot = new Matrix4f().rotation(invRot);
        Matrix4f viewProj = new Matrix4f(proj).mul(viewRot);

        // Zoom mods narrow the effective FOV — the meteorite block grows in
        // the 3D pass but the HUD label stays at a fixed pixel size unless we
        // scale it the same way perspective does. tan(base/2)/tan(current/2)
        // matches the projection scale; clamp ≥ 1.0 so the sprint FOV bump
        // never shrinks the label below baseline.
        float baseFov = client.options.getFov().getValue().floatValue();
        float zoomFactor = (float) Math.max(1.0,
                Math.tan(Math.toRadians(baseFov / 2.0))
                        / Math.tan(Math.toRadians(fov / 2.0)));

        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();

        // No world-name check here: Bukkit's world.getName() and the client's
        // RegistryKey path don't always agree (e.g. Bukkit "world" vs client
        // "overworld"). The server already filters recipients to players in
        // the same world, and MeteoriteState resets on every dimension
        // switch, so any entry we hold is for the world we're in.

        for (MeteoriteState.Snapshot s : snapshots) {
            double cx = s.x() + 0.5;
            double cy = s.y() + 0.5;
            double cz = s.z() + 0.5;
            double dx = cx - playerPos.x;
            double dy = cy - playerPos.y;
            double dz = cz - playerPos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < LABEL_MIN_DISTANCE) continue;
            if (dist > LABEL_MAX_DISTANCE) continue;

            drawProjectedLabel(context, client, s,
                    cx, cy + LABEL_WORLD_Y_OFFSET, cz,
                    dist, camPos, viewProj, sw, sh, zoomFactor);
        }
    }

    private static void drawProjectedLabel(DrawContext context, MinecraftClient client,
                                           MeteoriteState.Snapshot s,
                                           double wx, double wy, double wz,
                                           double distance,
                                           Vec3d camPos, Matrix4f viewProj,
                                           int sw, int sh, float zoomFactor) {
        float rx = (float) (wx - camPos.x);
        float ry = (float) (wy - camPos.y);
        float rz = (float) (wz - camPos.z);

        float w = viewProj.m03() * rx + viewProj.m13() * ry + viewProj.m23() * rz + viewProj.m33();
        if (w <= 0f) {
            // Behind camera — definitely don't draw.
            return;
        }

        Vector3f ndc = viewProj.transformProject(new Vector3f(rx, ry, rz));

        // Cull anything genuinely off-screen with a small margin. We used to
        // clamp to the viewport so a label always showed somewhere, but that
        // made far-away meteorites snap to the screen edge at full size — a
        // worse UX than just not showing them. With the 0.5-block Y-offset
        // above, in-bounds projections cover any case where you're looking
        // at the block.
        if (ndc.x < -1.05f || ndc.x > 1.05f) return;
        if (ndc.y < -1.05f || ndc.y > 1.05f) return;

        float screenX = (ndc.x + 1.0f) * 0.5f * sw;
        float screenY = (1.0f - ndc.y) * 0.5f * sh;

        TextRenderer tr = client.textRenderer;
        int tierColor = (s.colorRgb() & 0x00FFFFFF) | 0xFF000000;

        String countStr = Integer.toString(s.remaining());
        String tierStr = s.tierName() == null ? "" : s.tierName();
        String refinedStr = s.refined() ? " (Refined)" : "";

        // Layout: "247 Emerald (Refined)" centered, count in white, tier tinted, refined warm yellow.
        Text countText = Text.literal(countStr);
        Text spacerText = Text.literal(" ");
        Text tierText = Text.literal(tierStr);
        Text refinedText = refinedStr.isEmpty() ? null : Text.literal(refinedStr);

        int countW = tr.getWidth(countText);
        int spacerW = tr.getWidth(spacerText);
        int tierW = tr.getWidth(tierText);
        int refinedW = refinedText == null ? 0 : tr.getWidth(refinedText);
        int totalW = countW + spacerW + tierW + refinedW;

        // Distance-based scale so distant meteorites read as small dots, not
        // full-size labels floating in the sky. Matrix3x2fStack push +
        // translate + scale shrinks both glyphs and the backdrop in lockstep.
        // zoomFactor (≥ 1.0) tracks the world-projection so the label grows
        // with the meteorite block when a zoom mod narrows the FOV.
        float scale = (float) Math.max(LABEL_SCALE_FLOOR,
                Math.min(1.0, LABEL_SCALE_REFERENCE_DISTANCE / Math.max(distance, 1.0)));
        scale *= zoomFactor;

        org.joml.Matrix3x2fStack hudMatrices = context.getMatrices();
        hudMatrices.pushMatrix();
        hudMatrices.translate(screenX, screenY);
        hudMatrices.scale(scale, scale);

        // Subtle dark backdrop so the text reads against any biome — drawn in
        // unscaled local coords (the scale matrix shrinks both bg + text).
        int bgPad = 2;
        int halfW = totalW / 2;
        int top = -tr.fontHeight;
        context.fill(-halfW - bgPad, top - bgPad,
                -halfW + totalW + bgPad, top + tr.fontHeight + bgPad,
                0xB0000000);

        int x = -halfW;
        context.drawText(tr, countText, x, top, COUNT_COLOR, true);
        x += countW;
        context.drawText(tr, spacerText, x, top, SUFFIX_COLOR, true);
        x += spacerW;
        context.drawText(tr, tierText, x, top, tierColor, true);
        x += tierW;
        if (refinedText != null) {
            context.drawText(tr, refinedText, x, top, REFINED_COLOR, true);
        }

        hudMatrices.popMatrix();
    }

    private MeteoriteLabelRenderer() {}
}
