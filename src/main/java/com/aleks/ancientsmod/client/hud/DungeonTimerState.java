package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.net.payload.DungeonTimerPayload;

/**
 * Latest dungeon run-clock snapshot, written by the network handler and read
 * by {@link DungeonTimerHud} each frame.
 *
 * <p>While the run is live the server heartbeats state 1 at 1 Hz; the HUD
 * extrapolates between heartbeats for a smooth per-frame clock. The end packet
 * (state 2 = complete, 3 = wiped) freezes the final time, which lingers on
 * screen briefly and then fades when the wire goes quiet.
 */
public final class DungeonTimerState {

    /** Heartbeats stop on run end/leave; hold the frozen end time a bit longer. */
    private static final long STALE_RUNNING_MS = 5_000L;
    private static final long STALE_ENDED_MS = 12_000L;

    private static volatile int state = 0; // 0 none, 1 running, 2 complete, 3 wiped
    private static volatile long elapsedMs = 0L;
    private static volatile int tier = 0;
    private static volatile long receivedMs = 0L;

    private DungeonTimerState() {}

    public static void update(DungeonTimerPayload p) {
        state      = p.state();
        elapsedMs  = p.elapsedMs();
        tier       = p.tier();
        receivedMs = p.receivedMs();
    }

    public static boolean isLive() {
        if (receivedMs <= 0L) return false;
        long staleAfter = state == DungeonTimerPayload.STATE_RUNNING
                ? STALE_RUNNING_MS : STALE_ENDED_MS;
        return System.currentTimeMillis() - receivedMs <= staleAfter;
    }

    public static boolean isRunning() {
        return isLive() && state == DungeonTimerPayload.STATE_RUNNING;
    }

    public static int state() { return isLive() ? state : 0; }
    public static int tier()  { return isLive() ? tier : 0; }

    /** Elapsed clock, extrapolated past the last heartbeat while running. */
    public static long liveElapsedMs() {
        if (!isLive()) return 0L;
        if (state != DungeonTimerPayload.STATE_RUNNING) return elapsedMs;
        return elapsedMs + Math.max(0L, System.currentTimeMillis() - receivedMs);
    }
}
