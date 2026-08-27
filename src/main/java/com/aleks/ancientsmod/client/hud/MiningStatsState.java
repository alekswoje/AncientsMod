package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.net.payload.MiningStatsPayload;

/**
 * Latest mining stats snapshot, written by the network handler, read by
 * {@link StatsHud} each frame.
 *
 * <p>Mirrors {@link PveStatsState}'s stale-after window — the server only sends
 * a heartbeat while the player is actively mining, so a few seconds without an
 * update is the natural "stopped mining" signal and the section collapses.
 */
public final class MiningStatsState {

    /** Stale-after window — slightly larger than the server-side per-window
     *  inactivity timeout so a brief between-blocks pause doesn't flicker the
     *  HUD off. 5 s is generous given the 1 Hz heartbeat. */
    private static final long STALE_AFTER_MS = 5_000L;

    private static volatile long xpPerHour = 0L;
    private static volatile long energyPerHour = 0L;
    private static volatile long moneyPerHour = 0L;
    private static volatile long blocksPerHour = 0L;
    private static volatile long receivedMs = 0L;

    private MiningStatsState() {}

    public static void update(MiningStatsPayload payload) {
        xpPerHour     = payload.xpPerHour();
        energyPerHour = payload.energyPerHour();
        moneyPerHour  = payload.moneyPerHour();
        blocksPerHour = payload.blocksPerHour();
        receivedMs    = payload.receivedMs();
    }

    /** Epoch-ms of the last heartbeat. The server only sends these while the
     *  player is actually breaking blocks, so this doubles as "last mined at". */
    public static long lastUpdateMs() { return receivedMs; }

    public static boolean isLive() {
        return receivedMs > 0L && System.currentTimeMillis() - receivedMs <= STALE_AFTER_MS;
    }

    public static long xpPerHour()     { return isLive() ? xpPerHour     : 0L; }
    public static long energyPerHour() { return isLive() ? energyPerHour : 0L; }
    public static long moneyPerHour()  { return isLive() ? moneyPerHour  : 0L; }
    /** Prestige-weighted blocks mined per hour (0 when idle/stale). */
    public static long blocksPerHour() { return isLive() ? blocksPerHour : 0L; }
}
