package com.aleks.ancientsmod.render;

import com.aleks.ancientsmod.mixin.client.GameRendererInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * The view-projection matrix the world is drawn with, for HUD overlays that project a world point to
 * the screen (gang pings, meteorite labels).
 *
 * <p>Vanilla's {@code GameRenderer.renderWorld} builds the projection as
 * {@code getBasicProjectionMatrix(fov) * tiltViewWhenHurt * bobView} (bob only with View Bobbing on),
 * both at {@code camera.getLastTickProgress()}. The overlays used to stop at the basic matrix, so while
 * the player walked the world swayed with the bob and the labels did not: they slid around their block
 * every step (ANCT-1145). Nausea/portal distortion is left out; it warps the world on purpose.
 */
public final class WorldProjection {

    public static Matrix4f viewProj(MinecraftClient client, Camera camera, float fov) {
        GameRendererInvoker gr = (GameRendererInvoker) (Object) client.gameRenderer;
        MatrixStack bob = new MatrixStack();
        float bobDelta = camera.getLastTickProgress();
        gr.ancientsmod$tiltViewWhenHurt(bob, bobDelta);
        if (client.options.getBobView().getValue()) gr.ancientsmod$bobView(bob, bobDelta);

        Matrix4f proj = client.gameRenderer.getBasicProjectionMatrix(fov);
        proj.mul(bob.peek().getPositionMatrix());
        Quaternionf invRot = camera.getRotation().conjugate(new Quaternionf());
        return proj.mul(new Matrix4f().rotation(invRot));
    }

    private WorldProjection() {}
}
