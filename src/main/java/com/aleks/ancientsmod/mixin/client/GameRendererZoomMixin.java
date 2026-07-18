package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.Zoom;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hold-to-zoom: scales the computed FOV by {@link Zoom#smoothedMultiplier()}
 * while the zoom key is held. Same {@code getFov} target the read-only
 * {@link GameRendererInvoker} exposes; this one modifies the return value.
 */
@Mixin(GameRenderer.class)
public class GameRendererZoomMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void ancientsmod$zoom(Camera camera, float tickDelta, boolean changingFov,
                                  CallbackInfoReturnable<Float> cir) {
        float mult = Zoom.smoothedMultiplier();
        if (mult != 1.0f) {
            cir.setReturnValue(cir.getReturnValue() * mult);
        }
    }
}
