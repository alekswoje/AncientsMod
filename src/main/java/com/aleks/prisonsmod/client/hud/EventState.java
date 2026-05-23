package com.aleks.prisonsmod.client.hud;

import com.aleks.prisonsmod.net.payload.EventTimersPayload;

import java.util.List;

/**
 * Holds the latest event-timer snapshot from the server. Updated whenever a
 * {@link EventTimersPayload} arrives; widgets read from here every frame and
 * extrapolate countdowns smoothly between heartbeats.
 */
public final class EventState {

    private static final long STALE_AFTER_MS = 6_000L;

    private static volatile List<EventTimersPayload.Entry> entries = List.of();
    private static volatile long receivedMs = 0L;

    private EventState() {}

    public static void update(EventTimersPayload payload) {
        entries = List.copyOf(payload.entries());
        receivedMs = payload.receivedMs();
    }

    public static List<EventTimersPayload.Entry> entries() {
        if (System.currentTimeMillis() - receivedMs > STALE_AFTER_MS) return List.of();
        return entries;
    }

    /** Extrapolated seconds-until-fire for an entry — only meaningful if state == COUNTDOWN. */
    public static int liveSecondsUntilFire(EventTimersPayload.Entry e) {
        long elapsedMs = System.currentTimeMillis() - receivedMs;
        return Math.max(0, e.secondsUntilFire() - (int) (elapsedMs / 1000));
    }
}
