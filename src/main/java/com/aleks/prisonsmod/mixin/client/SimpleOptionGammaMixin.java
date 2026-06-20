package com.aleks.prisonsmod.mixin.client;

import com.aleks.prisonsmod.client.Fullbright;
import com.aleks.prisonsmod.client.ServerAllowlist;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;
import net.minecraft.text.TextContent;
import net.minecraft.text.TranslatableTextContent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides the gamma slider's getter when fullbright is active. The vanilla
 * slider clamps the *stored* value to 0..1, but consumers
 * ({@code LightTexture} / lightmap update) call {@link SimpleOption#getValue()}
 * and use whatever comes back — returning 15.0 effectively maxes brightness in
 * dark areas without touching the lightmap code.
 *
 * <p>Identified by translation key ("options.gamma") rather than instance
 * identity so we don't need to patch the constructor. Cheap: one branch on
 * non-gamma options (instanceof check is short-circuited by the key compare in
 * the cancellable path).
 */
@Mixin(SimpleOption.class)
public class SimpleOptionGammaMixin {

    @Shadow @Final
    Text text;

    @Inject(method = "getValue", at = @At("HEAD"), cancellable = true)
    private void prisonsmod$fullbright(CallbackInfoReturnable<Object> cir) {
        TextContent content = text.getContent();
        if (!(content instanceof TranslatableTextContent tc)) return;
        if (!"options.gamma".equals(tc.getKey())) return;
        if (!ServerAllowlist.isAllowed()) return;
        if (!Fullbright.isActive()) return;
        cir.setReturnValue(15.0D);
    }
}
