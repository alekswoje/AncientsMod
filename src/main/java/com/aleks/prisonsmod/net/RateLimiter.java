package com.aleks.prisonsmod.net;

import java.util.EnumMap;

/**
 * Per-packet-type sliding-second rate limiter. Cheap and allocation-free on the
 * hot path. Each packet type has its own independent budget so an attacker
 * cannot starve one effect type by flooding another.
 *
 * <p>Usage: {@code if (rateLimiter.tryAcquire(Kind.POINT_GAIN)) { ... decode ... }}
 *
 * <p>Not thread-safe. All packet receivers dispatch on the Minecraft render
 * thread, so single-threaded access is guaranteed in practice.
 */
public final class RateLimiter {

    public enum Kind {
        POINT_GAIN(Protocol.RATE_POINT_GAIN_PER_SEC),
        HUD_UPDATE(Protocol.RATE_HUD_UPDATE_PER_SEC),
        MINE_START(Protocol.RATE_MINE_START_PER_SEC),
        MINE_CANCEL(Protocol.RATE_MINE_CANCEL_PER_SEC),
        GANG_PING(Protocol.RATE_GANG_PING_PER_SEC),
        METEOR_PING(Protocol.RATE_METEOR_PING_PER_SEC),
        GANG_ROSTER(Protocol.RATE_GANG_ROSTER_PER_SEC),
        DUEL_STATE(Protocol.RATE_DUEL_STATE_PER_SEC),
        BOOSTER_UPDATE(Protocol.RATE_BOOSTER_UPDATE_PER_SEC),
        EVENT_TIMERS(Protocol.RATE_EVENT_TIMERS_PER_SEC),
        METEORITE_HUD(Protocol.RATE_METEORITE_HUD_PER_SEC),
        COOLDOWNS(Protocol.RATE_COOLDOWNS_PER_SEC),
        BUFF_SNAPSHOT(Protocol.RATE_BUFF_SNAPSHOT_PER_SEC),
        PVE_STATS(Protocol.RATE_PVE_STATS_PER_SEC),
        BUGREPORT(Protocol.RATE_BUGREPORT_PER_SEC);

        final int perSecond;
        Kind(int perSecond) { this.perSecond = perSecond; }
    }

    private static final class Bucket {
        long windowStartMs;
        int countInWindow;
    }

    private final EnumMap<Kind, Bucket> buckets = new EnumMap<>(Kind.class);

    public RateLimiter() {
        for (Kind k : Kind.values()) buckets.put(k, new Bucket());
    }

    /**
     * @return {@code true} if the caller is within budget and may process the packet;
     *         {@code false} if the packet should be dropped.
     */
    public boolean tryAcquire(Kind kind) {
        Bucket b = buckets.get(kind);
        long now = System.currentTimeMillis();
        if (now - b.windowStartMs >= 1000L) {
            b.windowStartMs = now;
            b.countInWindow = 0;
        }
        if (b.countInWindow >= kind.perSecond) return false;
        b.countInWindow++;
        return true;
    }
}
