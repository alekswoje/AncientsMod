package com.aleks.prisonsmod.client.muffler;

import net.minecraft.client.sound.SoundInstance;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Static bridge between the {@code SoundSystem} / {@code ParticleManager} mixins
 * and {@link MufflerSettings} / {@link MufflerCapture}. Keeping the logic here
 * (rather than in the mixins) keeps the injected code tiny and lets the mixins
 * stay free of any mod state beyond a single call.
 *
 * <h2>Sound volume scaling</h2>
 * {@code SoundSystem.play(SoundInstance)} computes the final channel volume via
 * the private {@code getAdjustedVolume(float, SoundCategory)} overload, which has
 * no reference to the {@code SoundInstance} — so it can't know which sound it's
 * scaling. To bridge that, {@link #onSoundPlay} stashes the resolved factor in a
 * {@link ThreadLocal} for the duration of the {@code play} call; the
 * {@code getAdjustedVolume(float, SoundCategory)} hook reads it via
 * {@link #pendingFactor()}. {@link #clearPending()} is called on {@code play}
 * return. Looping/tickable sounds re-evaluate every tick through the
 * {@code getAdjustedVolume(SoundInstance)} overload, handled separately by
 * {@link #tickableFactor(SoundInstance)} which has the instance in hand.
 */
public final class MufflerHooks {

    /** Per-call volume factor for the sound currently being started, or null. */
    private static final ThreadLocal<Float> PENDING_FACTOR = new ThreadLocal<>();

    private MufflerHooks() {}

    /**
     * Called at the head of {@code SoundSystem.play(SoundInstance)}.
     *
     * @return {@code true} if the sound should be suppressed entirely (the mixin
     *         then short-circuits {@code play} with {@code NOT_STARTED}).
     */
    public static boolean onSoundPlay(SoundInstance sound) {
        PENDING_FACTOR.remove();
        if (sound == null) return false;
        Identifier id = sound.getId();
        MufflerCapture.recordSound(id);
        if (!MufflerSettings.isEnabled()) return false;
        float f = MufflerSettings.soundFactor(id);
        if (f <= 0f) return true;           // fully silenced — skip starting it
        if (f < 1.0f) PENDING_FACTOR.set(f); // partial — scale in getAdjustedVolume
        return false;
    }

    public static void clearPending() {
        PENDING_FACTOR.remove();
    }

    /**
     * Read AND clear the pending one-shot factor in a single call (null = leave
     * the volume as-is). Consuming it here makes the factor one-shot by
     * construction: even if {@code play()} exits abnormally (an exception skips
     * the {@code RETURN} cleanup), a stale factor can scale at most the very next
     * {@code getAdjustedVolume} call rather than lingering on the thread.
     * {@link #clearPending()} on {@code play} return remains a backstop.
     */
    public static Float consumePendingFactor() {
        Float f = PENDING_FACTOR.get();
        if (f != null) PENDING_FACTOR.remove();
        return f;
    }

    /** Per-tick factor for an already-playing (looping/tickable) sound. */
    public static float tickableFactor(SoundInstance sound) {
        if (sound == null || !MufflerSettings.isEnabled()) return 1.0f;
        return MufflerSettings.soundFactor(sound.getId());
    }

    /**
     * Called at the head of {@code ParticleManager.addParticle(ParticleEffect,
     * double x6)} — the single choke point both client-spawned and server-sent
     * particles funnel through.
     *
     * @return {@code true} if the particle should be suppressed (mixin returns
     *         {@code null}).
     */
    public static boolean onParticle(ParticleEffect effect) {
        if (effect == null) return false;
        Identifier id = Registries.PARTICLE_TYPE.getId(effect.getType());
        if (id == null) return false;
        MufflerCapture.recordParticle(id);
        return MufflerSettings.isEnabled() && MufflerSettings.isParticleMuted(id);
    }
}
