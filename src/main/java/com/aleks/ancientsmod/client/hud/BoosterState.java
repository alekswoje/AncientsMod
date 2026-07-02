package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.net.payload.BoosterUpdatePayload;

import java.util.List;

/**
 * Holds the latest booster snapshot received from the server. The booster
 * widget reads from here every frame; the network handler writes whenever a
 * fresh {@link BoosterUpdatePayload} arrives.
 *
 * <p>Smoothness trick: the server sends snapshots at 1 Hz with
 * {@code secondsRemaining} as a whole number. The widget extrapolates by
 * subtracting wall-clock elapsed since {@link #receivedMs} from each entry's
 * stored seconds so the on-screen countdown ticks every frame instead of
 * stepping once per heartbeat. When the next heartbeat arrives the
 * extrapolation snaps back to the authoritative value.
 */
public final class BoosterState {

    /** Stale-after window. If we haven't heard from the server in 6 s, treat the snapshot as cleared (no widget). */
    private static final long STALE_AFTER_MS = 6_000L;

    private static volatile List<BoosterUpdatePayload.Entry> entries = List.of();
    private static volatile long receivedMs = 0L;

    private BoosterState() {}

    public static void update(BoosterUpdatePayload payload) {
        entries = List.copyOf(payload.entries());
        receivedMs = payload.receivedMs();
    }

    /** Empty list when no boosters are active or the data has gone stale. */
    public static List<BoosterUpdatePayload.Entry> entries() {
        if (System.currentTimeMillis() - receivedMs > STALE_AFTER_MS) return List.of();
        return entries;
    }

    /**
     * Extrapolated seconds-remaining for an entry. Subtracts wall-clock since
     * the snapshot arrived so the on-screen countdown is smooth. Paused
     * boosters don't extrapolate (their server-stored seconds stays put).
     */
    public static int liveSecondsRemaining(BoosterUpdatePayload.Entry e) {
        if (e.paused()) return e.secondsRemaining();
        long elapsedMs = System.currentTimeMillis() - receivedMs;
        return Math.max(0, e.secondsRemaining() - (int) (elapsedMs / 1000));
    }
}
