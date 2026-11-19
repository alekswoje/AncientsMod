package com.aleks.ancientsmod.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the private {@code GameRenderer#getFov(Camera, float, boolean)} so
 * we can compute the same FOV the world is rendered with at any tickDelta.
 *
 * <p>The public {@code gameRenderer.project(Vec3d)} helper would otherwise be
 * the obvious choice, but it hard-codes {@code tickDelta = 0}, which lags the
 * world's actual rendered FOV by up to one tick. That shows up as a visible
 * "snap" when projected HUD overlays catch up to the world at each tick
 * boundary — most noticeable during the sprint FOV ramp.
 */
@Mixin(GameRenderer.class)
public interface GameRendererInvoker {
    @Invoker("getFov")
    float ancientsmod$getFov(Camera camera, float tickDelta, boolean changingFov);

    /** Vanilla's hurt tilt, which the world projection carries on top of the basic one. */
    @Invoker("tiltViewWhenHurt")
    void ancientsmod$tiltViewWhenHurt(MatrixStack matrices, float tickDelta);

    /** Vanilla's walk bob (only applied when the View Bobbing option is on). */
    @Invoker("bobView")
    void ancientsmod$bobView(MatrixStack matrices, float tickDelta);
}
