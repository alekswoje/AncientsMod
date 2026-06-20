package com.aleks.prisonsmod.mixin.client;

import com.aleks.prisonsmod.client.muffler.MufflerHooks;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import net.minecraft.sound.SoundCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-side Sound Muffler. Lets the player silence or reduce the volume of any
 * sound event by id (see {@code MufflerSettings} / the muffler screen).
 *
 * <ul>
 *   <li>{@code play(SoundInstance)} is the single choke point every started
 *       sound passes through (direct, delayed and next-tick paths all converge
 *       here). We read the id, feed the capture buffer, and either suppress the
 *       sound outright (factor 0) or stash a partial factor for the volume
 *       hooks below.</li>
 *   <li>{@code getAdjustedVolume(float, SoundCategory)} computes the final
 *       channel volume during {@code play}; we scale it by the stashed factor
 *       for partial muffling of one-shot sounds.</li>
 *   <li>{@code getAdjustedVolume(SoundInstance)} is re-evaluated each tick for
 *       looping/tickable sounds; we scale it directly from the instance's id.</li>
 * </ul>
 *
 * Purely client-cosmetic — the server is never told and gameplay is unaffected.
 */
@Mixin(SoundSystem.class)
public class SoundSystemMufflerMixin {

    @Inject(
            method = "play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void prisonsmod$muffleSound(SoundInstance sound, CallbackInfoReturnable<SoundSystem.PlayResult> cir) {
        if (MufflerHooks.onSoundPlay(sound)) {
            cir.setReturnValue(SoundSystem.PlayResult.NOT_STARTED);
        }
    }

    @Inject(
            method = "play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;",
            at = @At("RETURN")
    )
    private void prisonsmod$clearPending(SoundInstance sound, CallbackInfoReturnable<SoundSystem.PlayResult> cir) {
        MufflerHooks.clearPending();
    }

    @Inject(
            method = "getAdjustedVolume(FLnet/minecraft/sound/SoundCategory;)F",
            at = @At("RETURN"),
            cancellable = true
    )
    private void prisonsmod$scaleOneShot(float volume, SoundCategory category, CallbackInfoReturnable<Float> cir) {
        Float f = MufflerHooks.consumePendingFactor();
        if (f != null) {
            cir.setReturnValue(cir.getReturnValueF() * f);
        }
    }

    @Inject(
            method = "getAdjustedVolume(Lnet/minecraft/client/sound/SoundInstance;)F",
            at = @At("RETURN"),
            cancellable = true
    )
    private void prisonsmod$scaleTickable(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
        float factor = MufflerHooks.tickableFactor(sound);
        if (factor != 1.0f) {
            cir.setReturnValue(cir.getReturnValueF() * factor);
        }
    }
}
