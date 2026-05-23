package com.aleks.prisonsmod.mixin.client;

import com.aleks.prisonsmod.client.EntityFade;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.entity.equipment.EquipmentRenderer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Pairs with {@code LivingEntityRendererMixin} to make armor fade with the
 * body. Vanilla armor goes through the cutout-no-cull layer (no alpha blend),
 * so simply forcing the body translucent leaves armor floating opaque on
 * top — exactly the case the user flagged.
 *
 * <p>Two coordinated hooks fire only while
 * {@link EntityFade#isRenderingFaded()} is true (set/cleared by
 * {@code LivingEntityRendererMixin} for the duration of one entity's
 * render):
 *
 * <ol>
 *   <li><b>@Redirect on {@link RenderLayers#armorCutoutNoCull}:</b> swap the
 *       layer to {@link RenderLayers#armorTranslucent}, which has alpha
 *       blending enabled so a sub-0xFF color alpha actually does something.</li>
 *   <li><b>@ModifyArg on every {@code submitModel} call:</b> overwrite the
 *       upper byte of the color int (alpha) with {@link EntityFade#BODY_ALPHA}.
 *       RGB tint (dye colors) is preserved so dyed leather still looks dyed,
 *       just half-transparent. This also bakes alpha into glint + trim submit
 *       calls so they don't sit opaque on top of translucent armor.</li>
 * </ol>
 */
@Mixin(EquipmentRenderer.class)
public abstract class EquipmentRendererMixin {

    @Redirect(
            method = "render(Lnet/minecraft/client/render/entity/equipment/EquipmentModel$LayerType;Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/util/Identifier;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/RenderLayers;armorCutoutNoCull(Lnet/minecraft/util/Identifier;)Lnet/minecraft/client/render/RenderLayer;"))
    private RenderLayer prisonsmod$translucentArmorLayer(Identifier texture) {
        if (EntityFade.isRenderingFaded()) {
            return RenderLayers.armorTranslucent(texture);
        }
        return RenderLayers.armorCutoutNoCull(texture);
    }

    @ModifyArg(
            method = "render(Lnet/minecraft/client/render/entity/equipment/EquipmentModel$LayerType;Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/util/Identifier;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/command/RenderCommandQueue;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IIILnet/minecraft/client/texture/Sprite;ILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V"),
            index = 6)
    private int prisonsmod$applyAlphaToArmor(int color) {
        if (EntityFade.isRenderingFaded()) {
            // Preserve RGB tint (e.g. dyed leather), replace alpha byte.
            return (color & 0x00FFFFFF) | (EntityFade.BODY_ALPHA << 24);
        }
        return color;
    }
}
