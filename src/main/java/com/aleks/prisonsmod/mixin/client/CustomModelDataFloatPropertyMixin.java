package com.aleks.prisonsmod.mixin.client;

import com.aleks.prisonsmod.client.DisabledTextures;
import net.minecraft.client.render.item.property.numeric.CustomModelDataFloatProperty;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.util.HeldItemContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a player turn off individual custom item textures (chosen in
 * {@code /toggles → Custom Textures}, pushed to us via the prisonsmod channel
 * into {@link DisabledTextures}).
 *
 * <p>This property is what {@code range_dispatch} reads to pick a model from the
 * item's {@code custom_model_data} (index 0 = the value our resource pack keys
 * off). When the player has disabled the texture for this (item, CMD) pair, we
 * return a value below every threshold so range_dispatch falls through to its
 * vanilla fallback model. Non-disabled items are untouched, so this is a no-op
 * for everyone who hasn't opted out.
 */
@Mixin(CustomModelDataFloatProperty.class)
public abstract class CustomModelDataFloatPropertyMixin {

    @Shadow @Final private int index;

    @Inject(method = "getValue", at = @At("RETURN"), cancellable = true)
    private void prisonsmod$ignoreDisabledTexture(ItemStack stack, ClientWorld world, HeldItemContext context,
                                                  int seed, CallbackInfoReturnable<Float> cir) {
        if (index != 0) return;                 // our textures all key off index 0
        if (!DisabledTextures.hasAny()) return; // fast path: nobody opted out
        int cmd = Math.round(cir.getReturnValueF());
        if (cmd <= 0) return;
        if (DisabledTextures.isDisabled(stack, cmd)) {
            cir.setReturnValue(-1.0f); // below all thresholds → vanilla fallback model
        }
    }
}
