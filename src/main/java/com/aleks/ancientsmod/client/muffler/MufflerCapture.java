package com.aleks.ancientsmod.client.muffler;

import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Recently played" capture buffer powering the muffler's Recent tab. Records
 * the id of every sound / particle that fires while capture is <em>active</em>
 * (i.e. while the muffler screen is open) along with the last-seen timestamp and
 * a hit count, so the player can mute whatever just annoyed them without hunting
 * for its id.
 *
 * <p>Recording is gated on {@link #active} so the audio/particle hot paths pay
 * essentially nothing when the screen isn't open. The maps are bounded
 * implicitly by the vanilla id space and explicitly pruned to the last
 * {@link #WINDOW_MS} on read.
 */
public final class MufflerCapture {

    /** Only entries seen within this window show in the Recent list. */
    private static final long WINDOW_MS = 60_000L;
    /** Hard cap on rows returned to the GUI. */
    private static final int MAX_ROWS = 80;

    private static volatile boolean active = false;

    private static final Map<Identifier, Hit> sounds = new ConcurrentHashMap<>();
    private static final Map<Identifier, Hit> particles = new ConcurrentHashMap<>();

    private MufflerCapture() {}

    /** Toggle recording. The muffler screen calls {@code setActive(true)} on open
     *  and {@code false} on close. */
    public static void setActive(boolean on) {
        active = on;
    }

    public static boolean isActive() {
        return active;
    }

    public static void recordSound(Identifier id) {
        if (!active || id == null) return;
        record(sounds, id);
    }

    public static void recordParticle(Identifier id) {
        if (!active || id == null) return;
        record(particles, id);
    }

    private static void record(Map<Identifier, Hit> map, Identifier id) {
        Hit h = map.computeIfAbsent(id, k -> new Hit());
        h.time = System.currentTimeMillis();
        h.count.incrementAndGet();
    }

    /** Snapshot of recent sounds, newest first. */
    public static List<Entry> recentSounds() {
        return snapshot(sounds);
    }

    /** Snapshot of recent particles, newest first. */
    public static List<Entry> recentParticles() {
        return snapshot(particles);
    }

    private static List<Entry> snapshot(Map<Identifier, Hit> map) {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        List<Entry> out = new ArrayList<>();
        for (Identifier id : map.keySet()) {
            // Atomically drop the entry only if still expired — computeIfPresent
            // runs under the bin lock, so a concurrent record() that just
            // refreshed h.time is observed and the live entry survives.
            Hit h = map.computeIfPresent(id, (k, hh) -> hh.time < cutoff ? null : hh);
            if (h == null) continue;
            out.add(new Entry(id, h.time, h.count.get()));
        }
        out.sort(Comparator.comparingLong((Entry e) -> e.lastSeen).reversed());
        if (out.size() > MAX_ROWS) {
            return out.subList(0, MAX_ROWS);
        }
        return out;
    }

    public static void clear() {
        sounds.clear();
        particles.clear();
    }

    private static final class Hit {
        volatile long time;
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
    }

    /** Immutable row handed to the GUI. */
    public static final class Entry {
        public final Identifier id;
        public final long lastSeen;
        public final int count;

        Entry(Identifier id, long lastSeen, int count) {
            this.id = id;
            this.lastSeen = lastSeen;
            this.count = count;
        }
    }
}
