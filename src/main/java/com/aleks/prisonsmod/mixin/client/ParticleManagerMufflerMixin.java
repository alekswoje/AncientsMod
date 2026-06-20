package com.aleks.prisonsmod.mixin.client;

import com.aleks.prisonsmod.client.muffler.MufflerHooks;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.particle.ParticleEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-side Particle Muffler. {@code addParticle(ParticleEffect, double x6)} is
 * the single funnel that BOTH client-spawned particles and server-sent particle
 * packets (via {@code ClientWorld.addParticleClient}) pass through, so one HEAD
 * hook catches every particle that would spawn. We read the particle type's id,
 * feed the capture buffer, and return {@code null} (the method is {@code @Nullable})
 * to suppress muted types.
 *
 * Purely client-cosmetic — the server is never told and gameplay is unaffected.
 */
@Mixin(ParticleManager.class)
public class ParticleManagerMufflerMixin {

    @Inject(
            method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void prisonsmod$muffleParticle(ParticleEffect parameters,
                                           double x, double y, double z,
                                           double velocityX, double velocityY, double velocityZ,
                                           CallbackInfoReturnable<Particle> cir) {
        if (MufflerHooks.onParticle(parameters)) {
            cir.setReturnValue(null);
        }
    }
}
