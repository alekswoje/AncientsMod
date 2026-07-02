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
