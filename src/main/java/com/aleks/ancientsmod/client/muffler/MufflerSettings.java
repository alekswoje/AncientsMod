package com.aleks.ancientsmod.client.muffler;

import com.aleks.ancientsmod.AncientsMod;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent store for the Sound &amp; Particle Muffler.
 *
 * <p>Two pieces of state:
 * <ul>
 *   <li><b>Sound volumes</b> — per sound-event {@link Identifier} a factor in
 *       {@code [0,1]}. {@code 1.0} (the absent default) = untouched, {@code 0.0}
 *       = fully silenced, anything between = muffled. Read on the hot path by the
 *       {@code SoundSystem} mixin for every sound that plays.</li>
 *   <li><b>Muted particles</b> — a set of particle-type {@link Identifier}s that
 *       are fully suppressed. Read on the hot path by the {@code ParticleManager}
 *       mixin for every particle that spawns (client- or server-sent).</li>
 * </ul>
 *
 * <p>Backed by {@code config/ancientsmod-muffler.properties}. Maps are
 * {@link ConcurrentHashMap}s so the (off-thread-safe) audio/particle hot paths
 * can read without locking; {@code volatile} {@code any*} flags give a
 * branch-predicted fast exit when nothing is muffled (the common case).
 *
 * <p>Strictly client-cosmetic: muffling only changes what THIS client renders /
 * hears. The server is never told and gameplay is unaffected.
 */
public final class MufflerSettings {

    private static final String FILE_NAME = "ancientsmod-muffler.properties";

    /** Master switch. When off, every sound/particle passes through untouched
     *  (settings are remembered, just not applied). */
    private static volatile boolean enabled = true;

    /** sound id → volume factor in [0,1]. Absent = 1.0 (full volume). */
    private static final Map<Identifier, Float> soundVolumes = new ConcurrentHashMap<>();

    /** particle ids that are fully suppressed. */
    private static final Set<Identifier> mutedParticles = ConcurrentHashMap.newKeySet();

    /** Hot-path fast-exit flags (avoid a map lookup when nothing is muffled). */
    private static volatile boolean anySoundOverrides = false;
    private static volatile boolean anyParticleMuted = false;

    private MufflerSettings() {}

    // ── Hot-path reads (called per sound / per particle) ─────────────────────

    public static boolean isEnabled() { return enabled; }

    /** Volume factor for a sound id, 1.0 if untouched. Fast path returns 1.0
     *  immediately when no sound overrides exist at all. */
    public static float soundFactor(Identifier id) {
        if (!anySoundOverrides || id == null) return 1.0f;
        Float f = soundVolumes.get(id);
        return f == null ? 1.0f : f;
    }

    /** True if this particle id is fully suppressed. */
    public static boolean isParticleMuted(Identifier id) {
        if (!anyParticleMuted || id == null) return false;
        return mutedParticles.contains(id);
    }

    // ── Queries used by the GUI ──────────────────────────────────────────────

    /** Slider position for a sound id (1.0 default). */
    public static float getSoundVolume(Identifier id) {
        Float f = soundVolumes.get(id);
        return f == null ? 1.0f : f;
    }

    public static boolean isSoundSilenced(Identifier id) {
        return getSoundVolume(id) <= 0f;
    }

    public static boolean isParticleMutedGui(Identifier id) {
        return mutedParticles.contains(id);
    }

    // ── Mutations (called from the GUI) ──────────────────────────────────────

    public static void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        save();
    }

    /** Set a sound's volume factor, clamped to [0,1]. 1.0 removes the override.
     *  Persists immediately — for one-shot user actions (a single click). */
    public static void setSoundVolume(Identifier id, float factor) {
        setSoundVolume(id, factor, true);
    }

    /** As {@link #setSoundVolume(Identifier, float)}, but {@code persist=false}
     *  updates memory only and skips the disk write. Used for high-frequency
     *  batches — a slider drag (flushed once on mouse-release) or a multi-id
     *  bundle toggle (flushed once at the end) — so we don't rewrite the whole
     *  properties file dozens of times per second on the input thread. */
    public static void setSoundVolume(Identifier id, float factor, boolean persist) {
        if (id == null) return;
        float f = Math.max(0f, Math.min(1f, factor));
        boolean changed;
        if (f >= 1.0f) {
            changed = soundVolumes.remove(id) != null;
        } else {
            Float prev = soundVolumes.put(id, f);
            changed = prev == null || prev.floatValue() != f;
        }
        if (!changed) return;
        anySoundOverrides = !soundVolumes.isEmpty();
        if (persist) save();
    }

    public static void setParticleMuted(Identifier id, boolean muted) {
        setParticleMuted(id, muted, true);
    }

    /** {@code persist=false} variant — see {@link #setSoundVolume(Identifier, float, boolean)}. */
    public static void setParticleMuted(Identifier id, boolean muted, boolean persist) {
        if (id == null) return;
        boolean changed = muted ? mutedParticles.add(id) : mutedParticles.remove(id);
        if (!changed) return;
        anyParticleMuted = !mutedParticles.isEmpty();
        if (persist) save();
    }

    public static void toggleParticle(Identifier id) {
        setParticleMuted(id, !mutedParticles.contains(id), true);
    }

    /** Reset everything to "nothing muffled". */
    public static void clearAll() {
        if (soundVolumes.isEmpty() && mutedParticles.isEmpty()) return;
        soundVolumes.clear();
        mutedParticles.clear();
        anySoundOverrides = false;
        anyParticleMuted = false;
        save();
    }

    public static int mutedSoundCount() { return soundVolumes.size(); }

    public static int mutedParticleCount() { return mutedParticles.size(); }

    // ── Persistence ──────────────────────────────────────────────────────────

    private static Path configPath() {
        return com.aleks.ancientsmod.client.ConfigPaths.resolve(FILE_NAME);
    }

    public static void load() {
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            save();
            return;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            AncientsMod.LOGGER.warn("failed to load {}: {}", FILE_NAME, e.getMessage());
            return;
        }
        soundVolumes.clear();
        mutedParticles.clear();
        String en = props.getProperty("enabled");
        enabled = en == null || !en.trim().equalsIgnoreCase("false");
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("sound.")) {
                Identifier id = Identifier.tryParse(key.substring("sound.".length()));
                if (id == null) continue;
                try {
                    float f = Math.max(0f, Math.min(1f, Float.parseFloat(props.getProperty(key))));
                    if (f < 1.0f) soundVolumes.put(id, f);
                } catch (NumberFormatException ignored) {
                    // skip malformed entry
                }
            } else if (key.startsWith("particle.")) {
                Identifier id = Identifier.tryParse(key.substring("particle.".length()));
                if (id != null) mutedParticles.add(id);
            }
        }
        anySoundOverrides = !soundVolumes.isEmpty();
        anyParticleMuted = !mutedParticles.isEmpty();
    }

    public static void save() {
        Properties props = new Properties();
        props.setProperty("enabled", Boolean.toString(enabled));
        for (Map.Entry<Identifier, Float> e : soundVolumes.entrySet()) {
            props.setProperty("sound." + e.getKey(), Float.toString(e.getValue()));
        }
        for (Identifier id : mutedParticles) {
            props.setProperty("particle." + id, "off");
        }
        try {
            Files.createDirectories(configPath().getParent());
            try (var out = Files.newOutputStream(configPath())) {
                props.store(out, "AncientsMod Sound & Particle Muffler — sound.<id>=volumeFactor, particle.<id>=off");
            }
        } catch (IOException e) {
            AncientsMod.LOGGER.warn("failed to save {}: {}", FILE_NAME, e.getMessage());
        }
    }
}
