package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.EntityFade;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the unified entity-fade effect (peaceful mining + peaceful PvP)
 * to {@link LivingEntityRenderer#render}. Three coordinated injections:
 *
 * <ol>
 *   <li><b>HEAD:</b> raise the {@link EntityFade#beginRender()} flag when
 *       {@link EntityFade#shouldFade} matches the entity being rendered.
 *       Cleared at RETURN. The flag is what
 *       {@code EquipmentRendererMixin} reads to swap armor layers.</li>
 *   <li><b>@ModifyVariable on the second boolean:</b> flip the vanilla
 *       {@code translucent} local to {@code true}. That single switch
 *       gives us (a) the {@code itemEntityTranslucentCull} render layer
 *       for the body and (b) the constant color {@code 0x26FFFFFF}
 *       (~15% alpha) — without it the body would still render solid.</li>
 *   <li><b>@ModifyConstant on 0x26FFFFFF:</b> rewrite the vanilla 15%-alpha
 *       color to {@link EntityFade#BODY_COLOR} (50%) whenever we're in a
 *       faded render. The constant is unique inside this method, so the
 *       match is unambiguous.</li>
 * </ol>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Inject(
            method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At("HEAD"))
    private void ancientsmod$beginFade(LivingEntityRenderState state,
                                      MatrixStack matrices,
                                      OrderedRenderCommandQueue queue,
                                      CameraRenderState cameraState,
                                      CallbackInfo ci) {
        if (EntityFade.shouldFade(state)) {
            EntityFade.beginRender();
        }
    }

    @Inject(
            method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At("RETURN"))
    private void ancientsmod$endFade(LivingEntityRenderState state,
                                    MatrixStack matrices,
                                    OrderedRenderCommandQueue queue,
                                    CameraRenderState cameraState,
                                    CallbackInfo ci) {
        EntityFade.endRender();
    }

    @ModifyVariable(
            method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At("STORE"),
            ordinal = 1)
    private boolean ancientsmod$forceTranslucent(boolean original,
                                                LivingEntityRenderState state,
                                                MatrixStack matrices,
                                                OrderedRenderCommandQueue queue,
                                                CameraRenderState cameraState) {
        if (EntityFade.shouldFade(state)) return true;
        return original;
    }

    @ModifyConstant(
            method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            constant = @Constant(intValue = 0x26FFFFFF))
    private int ancientsmod$bumpBodyAlpha(int vanillaTranslucentColor) {
        if (EntityFade.isRenderingFaded()) return EntityFade.BODY_COLOR;
        return vanillaTranslucentColor;
    }
}
