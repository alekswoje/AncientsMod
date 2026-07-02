package com.aleks.ancientsmod.client.hud;

import com.aleks.ancientsmod.net.payload.CooldownsPayload;

import java.util.List;

/** Latest cooldowns snapshot, written by the network handler, read by {@link CooldownsHud} each frame. */
public final class CooldownState {

    private static final long STALE_AFTER_MS = 6_000L;

    private static volatile List<CooldownsPayload.Entry> entries = List.of();
    private static volatile long receivedMs = 0L;

    private CooldownState() {}

    public static void update(CooldownsPayload payload) {
        entries = List.copyOf(payload.entries());
        receivedMs = payload.receivedMs();
    }

    public static List<CooldownsPayload.Entry> entries() {
        if (System.currentTimeMillis() - receivedMs > STALE_AFTER_MS) return List.of();
        return entries;
    }

    public static int liveSecondsRemaining(CooldownsPayload.Entry e) {
        long elapsedMs = System.currentTimeMillis() - receivedMs;
        return Math.max(0, e.secondsRemaining() - (int) (elapsedMs / 1000));
    }
}
