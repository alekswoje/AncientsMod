package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.net.Protocol;
import com.aleks.ancientsmod.net.payload.MeteoriteHudPayload;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks every meteorite the local player has heard about. Keyed by
 * {@code (worldName, x, y, z)}: a fresh update at a known location replaces
 * the existing entry; {@code remaining=0} removes it; entries that haven't
 * been refreshed in {@link Protocol#METEORITE_HUD_STALE_AFTER_MS} self-evict
 * the next time anyone reads the map.
 *
 * <p>Multiple meteorites can be active at once (the cluster runs a
 * meteorite-shower event), so the world-space renderer needs to draw a
 * label on every one it knows about — a single-slot store would leave
 * neighbouring meteorites silently un-labeled.
 */
public final class MeteoriteState {

    private static final Map<String, Snapshot> ENTRIES = new ConcurrentHashMap<>();

    private MeteoriteState() {}

    public static void update(MeteoriteHudPayload p) {
        String key = key(p.worldName(), p.x(), p.y(), p.z());
        if (p.remaining() <= 0) {
            ENTRIES.remove(key);
            return;
        }
        ENTRIES.put(key, new Snapshot(p.worldName(), p.x(), p.y(), p.z(),
                p.tierName(), p.colorRgb(), p.refined(), p.remaining(),
                System.currentTimeMillis()));
    }

    /** Snapshot of every meteorite still considered fresh. Iteration triggers stale-eviction as a side effect. */
    public static Collection<Snapshot> entries() {
        if (ENTRIES.isEmpty()) return Collections.emptyList();
        long now = System.currentTimeMillis();
        ENTRIES.values().removeIf(s -> now - s.receivedMs > Protocol.METEORITE_HUD_STALE_AFTER_MS);
        return ENTRIES.values();
    }

    /**
     * Whether a fresh meteorite is known at these block coordinates.
     *
     * <p>The world name is deliberately ignored, for the same reason
     * {@code MeteoriteLabelRenderer} ignores it: Bukkit's
     * {@code world.getName()} and the client's registry path do not always
     * agree, the server only sends these to players in the meteorite's world,
     * and the map is cleared on every dimension switch — so anything still
     * held is for the world we are in.
     *
     * <p>Used by {@code MinePredictRenderer} to skip the ghost swap on a
     * meteorite: the server keeps that block in place for all of its remaining
     * ores, so a predicted replacement would only flicker and roll back.
     */
    public static boolean isKnownAt(int x, int y, int z) {
        if (ENTRIES.isEmpty()) return false;
        long now = System.currentTimeMillis();
        for (Snapshot s : ENTRIES.values()) {
            if (s.x == x && s.y == y && s.z == z
                    && now - s.receivedMs <= Protocol.METEORITE_HUD_STALE_AFTER_MS) {
                return true;
            }
        }
        return false;
    }

    public static void reset() {
        ENTRIES.clear();
    }

    private static String key(String worldName, int x, int y, int z) {
        return worldName + ":" + x + "," + y + "," + z;
    }

    public record Snapshot(String worldName, int x, int y, int z,
                           String tierName, int colorRgb, boolean refined,
                           int remaining, long receivedMs) {}
}
