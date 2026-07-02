package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.net.payload.RiftBudgetPayload;

/**
 * Holds the latest per-player Tartarus Rift daily-time snapshot from the server.
 * The Events HUD reads this each frame for the rift row. Daily time-left is
 * extrapolated down client-side only while {@code consuming} (in the rift,
 * mining); the reset countdown always ticks.
 */
public final class RiftBudgetState {

    private static final long STALE_AFTER_MS = 6_000L;

    private static volatile RiftBudgetPayload latest = null;
    private static volatile long receivedMs = 0L;

    private RiftBudgetState() {}

    public static void update(RiftBudgetPayload payload) {
        latest = payload;
        receivedMs = System.currentTimeMillis();
    }

    /** True if a fresh snapshot has arrived recently. */
    public static boolean isFresh() {
        return latest != null && System.currentTimeMillis() - receivedMs <= STALE_AFTER_MS;
    }

    /** True when the rift feature is enabled/open for this player. */
    public static boolean isAvailable() {
        RiftBudgetPayload p = latest;
        return p != null && p.available();
    }

    /** Remaining daily rift seconds; only counts down while actively mining. */
    public static int liveRemaining() {
        RiftBudgetPayload p = latest;
        if (p == null) return 0;
        if (!p.consuming()) return p.remainingSeconds();
        long elapsed = (System.currentTimeMillis() - receivedMs) / 1000;
        return Math.max(0, p.remainingSeconds() - (int) elapsed);
    }

    /** Seconds until the daily reset; always counts down. */
    public static int liveUntilReset() {
        RiftBudgetPayload p = latest;
        if (p == null) return 0;
        long elapsed = (System.currentTimeMillis() - receivedMs) / 1000;
        return Math.max(0, p.secondsUntilReset() - (int) elapsed);
    }
}
