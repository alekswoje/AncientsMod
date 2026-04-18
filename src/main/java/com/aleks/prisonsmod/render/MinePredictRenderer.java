package com.aleks.prisonsmod.render;

import com.aleks.prisonsmod.PrisonsMod;
import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.net.payload.MineStartPayload;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Latency-mitigation renderer for mining. When the server emits
 * {@link com.aleks.prisonsmod.net.Protocol#PKT_MINE_START}, the mod starts a
 * local {@code ClientWorld#setBlockBreakingInfo} animation immediately —
 * roughly 100ms earlier than waiting for the server's first
 * {@code BlockDestructionPacket} to round-trip back. The server remains
 * authoritative for the real break; this is purely cosmetic anticipation.
 *
 * <p>Each predicted animation is keyed by a synthetic entity id high enough
 * to never collide with real players' breaking indicators. Entries self-expire
 * once their predicted duration elapses, or when a new hint replaces them.
 */
public final class MinePredictRenderer {

    /** Reserved synthetic entity id range. Far above any real player entity id. */
    private static final int BASE_ENTITY_ID = 1_000_000_000;

    /** Hard cap on concurrent predicted animations. */
    private static final int MAX_ENTRIES = 16;

    private static final Map<BlockPos, Entry> ACTIVE = new HashMap<>();

    private static final class Entry {
        final int entityId;
        final long startMs;
        final int durationMs;
        int lastStageSent = -1;

        Entry(int entityId, long startMs, int durationMs) {
            this.entityId = entityId;
            this.startMs = startMs;
            this.durationMs = durationMs;
        }
    }

    private static int nextEntityId = BASE_ENTITY_ID;

    public static void onMineStart(MineStartPayload payload) {
        // Feature toggle: when off, we still accept the packet (so the rate limiter
        // accounting stays honest) but don't render anything — the server's real
        // progress packets will drive the crack animation with the natural latency.
        if (!FeatureToggles.isMinePredictEnabled()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) return;

        // Cap memory footprint; drop the oldest if at the limit.
        if (ACTIVE.size() >= MAX_ENTRIES) {
            Iterator<Map.Entry<BlockPos, Entry>> it = ACTIVE.entrySet().iterator();
            if (it.hasNext()) {
                Map.Entry<BlockPos, Entry> oldest = it.next();
                world.setBlockBreakingInfo(oldest.getValue().entityId, oldest.getKey(), -1);
                it.remove();
            }
        }

        // If we already had a prediction for this block, clear it first.
        Entry existing = ACTIVE.remove(payload.pos());
        if (existing != null) {
            world.setBlockBreakingInfo(existing.entityId, payload.pos(), -1);
        }

        int entityId = nextEntityId++;
        if (nextEntityId > BASE_ENTITY_ID + 1_000_000) nextEntityId = BASE_ENTITY_ID;

        Entry entry = new Entry(entityId, System.currentTimeMillis(), Math.max(1, payload.durationMs()));
        ACTIVE.put(payload.pos(), entry);
    }

    /** Tick every client frame to advance stages and expire finished entries. */
    public static void tick() {
        if (ACTIVE.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null) {
            ACTIVE.clear();
            return;
        }

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<BlockPos, Entry>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Entry> e = it.next();
            Entry entry = e.getValue();
            long elapsed = now - entry.startMs;

            // Past the predicted duration? Clear — the server's real packets take over.
            if (elapsed >= entry.durationMs) {
                world.setBlockBreakingInfo(entry.entityId, e.getKey(), -1);
                it.remove();
                continue;
            }

            // 10 stages (0-9); -1 clears.
            int stage = (int) Math.min(9, (elapsed * 10) / entry.durationMs);
            if (stage != entry.lastStageSent) {
                world.setBlockBreakingInfo(entry.entityId, e.getKey(), stage);
                entry.lastStageSent = stage;
            }
        }
    }

    /** Called when the mod is disabled (leaving an allowlisted server). */
    public static void reset() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world != null) {
            for (Map.Entry<BlockPos, Entry> e : ACTIVE.entrySet()) {
                world.setBlockBreakingInfo(e.getValue().entityId, e.getKey(), -1);
            }
        }
        ACTIVE.clear();
        PrisonsMod.LOGGER.debug("MinePredictRenderer reset");
    }

    private MinePredictRenderer() {}
}
