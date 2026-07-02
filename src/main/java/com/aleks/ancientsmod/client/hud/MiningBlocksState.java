package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.net.payload.MiningBlocksPayload;

import java.util.List;

/**
 * Latest per-ore mined-block counts (lifetime + session), written by the network
 * handler and read by {@link StatsHud} each frame.
 *
 * <p>Mirrors {@link MiningStatsState}'s stale-after window — the server only
 * sends a heartbeat while the player is actively mining, so a few seconds
 * without an update is the natural "stopped mining" signal and the blocks
 * section collapses.
 */
public final class MiningBlocksState {

    private static final long STALE_AFTER_MS = 5_000L;

    private static volatile List<MiningBlocksPayload.Row> rows = List.of();
    private static volatile long receivedMs = 0L;

    private MiningBlocksState() {}

    public static void update(MiningBlocksPayload payload) {
        rows = payload.rows() != null ? payload.rows() : List.of();
        receivedMs = payload.receivedMs();
    }

    public static boolean isLive() {
        return receivedMs > 0L && System.currentTimeMillis() - receivedMs <= STALE_AFTER_MS;
    }

    /** Live rows, or empty when stale. Order matches the wire (server's insertion order). */
    public static List<MiningBlocksPayload.Row> rows() {
        return isLive() ? rows : List.of();
    }
}
