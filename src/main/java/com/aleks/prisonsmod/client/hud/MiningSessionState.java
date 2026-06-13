package com.aleks.prisonsmod.client.hud;

import com.aleks.prisonsmod.net.payload.MiningSessionPayload;

/**
 * Latest manual mining-session snapshot, written by the network handler and read
 * by {@link StatsHud} each frame.
 *
 * <p>Unlike {@link MiningStatsState}, the server heartbeats this for as long as a
 * {@code /miningtrack} session exists — running OR stopped — so the section stays
 * visible (with frozen totals) after a stop. The wire only goes quiet on
 * {@code reset}/quit, so a stale window a few seconds past the 1 Hz heartbeat is
 * the natural "session cleared" signal that collapses the section.
 */
public final class MiningSessionState {

    private static final long STALE_AFTER_MS = 5_000L;

    private static volatile int state = 0; // 0 none, 1 running, 2 stopped
    private static volatile long elapsedMs = 0L;
    private static volatile long totalXp = 0L;
    private static volatile long totalEnergy = 0L;
    private static volatile long totalMoney = 0L;
    private static volatile long totalBlocks = 0L;
    private static volatile long receivedMs = 0L;

    private MiningSessionState() {}

    public static void update(MiningSessionPayload p) {
        state       = p.state();
        elapsedMs   = p.elapsedMs();
        totalXp     = p.totalXp();
        totalEnergy = p.totalEnergy();
        totalMoney  = p.totalMoney();
        totalBlocks = p.totalBlocks();
        receivedMs  = p.receivedMs();
    }

    public static boolean isLive() {
        return receivedMs > 0L && System.currentTimeMillis() - receivedMs <= STALE_AFTER_MS;
    }

    public static boolean isRunning() { return isLive() && state == 1; }

    public static long elapsedMs()   { return isLive() ? elapsedMs   : 0L; }
    public static long totalXp()     { return isLive() ? totalXp     : 0L; }
    public static long totalEnergy() { return isLive() ? totalEnergy : 0L; }
    public static long totalMoney()  { return isLive() ? totalMoney  : 0L; }
    /** Prestige-weighted blocks mined this session (0 when stale). */
    public static long totalBlocks() { return isLive() ? totalBlocks : 0L; }

    /** Session-average per-hour rate for {@code total} over the elapsed clock. */
    public static long perHour(long total) {
        long ms = elapsedMs();
        if (ms <= 0L) return 0L;
        return Math.round(total * (3_600_000.0 / ms));
    }
}
