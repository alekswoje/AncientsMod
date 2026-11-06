package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.FeatureToggles;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Low fire: drops the on-fire first-person overlay toward the bottom of the screen
 * so it no longer blinds you. The vanilla method pushes and pops its own matrix
 * entry, so we wrap the whole call in one extra push/pop around a downward shift.
 */
@Mixin(InGameOverlayRenderer.class)
public class InGameOverlayRendererLowFireMixin {

    private static final float LOW_FIRE_SHIFT = 0.4f;

    @Inject(method = "renderFireOverlay", at = @At("HEAD"))
    private static void ancientsmod$lowFireStart(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                                 Sprite sprite, CallbackInfo ci) {
        if (!FeatureToggles.isLowFireEnabled()) return;
        matrices.push();
        matrices.translate(0.0f, -LOW_FIRE_SHIFT, 0.0f);
    }

    @Inject(method = "renderFireOverlay", at = @At("RETURN"))
    private static void ancientsmod$lowFireEnd(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                               Sprite sprite, CallbackInfo ci) {
        if (!FeatureToggles.isLowFireEnabled()) return;
        matrices.pop();
    }
}
