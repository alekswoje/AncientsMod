package com.aleks.prisonsmod.client.gangping;

import com.aleks.prisonsmod.net.Protocol;

/**
 * One live ping being rendered in-world.
 *
 * <p>Identified by sender name (used as the dedupe key — a player's new ping
 * replaces their previous one, per the feature spec). Fade runs in the last
 * quarter of its lifetime, driven by {@link #alpha(long)}.
 *
 * <p>Lifetime is per-instance so meteor pings can outlast normal gang pings;
 * the gang-ping constructor falls back to {@link Protocol#GANG_PING_LIFETIME_MS}.
 */
public final class GangPing {

    public final String senderName;
    public final int colorRgb;
    public final double x;
    public final double y;
    public final double z;
    public final String worldName;
    public final long spawnedAtMs;
    public final long lifetimeMs;

    public GangPing(String senderName, int colorRgb,
                    double x, double y, double z,
                    String worldName, long spawnedAtMs) {
        this(senderName, colorRgb, x, y, z, worldName, spawnedAtMs,
                Protocol.GANG_PING_LIFETIME_MS);
    }

    public GangPing(String senderName, int colorRgb,
                    double x, double y, double z,
                    String worldName, long spawnedAtMs, long lifetimeMs) {
        this.senderName = senderName;
        this.colorRgb = colorRgb;
        this.x = x;
        this.y = y;
        this.z = z;
        this.worldName = worldName == null ? "" : worldName;
        this.spawnedAtMs = spawnedAtMs;
        this.lifetimeMs = Math.max(1L, lifetimeMs);
    }

    public long ageMs(long now) { return Math.max(0L, now - spawnedAtMs); }

    public boolean expired(long now) { return ageMs(now) >= lifetimeMs; }

    /** Linear fade over the final 25% of the ping's lifetime. 1.0 → full opacity, 0.0 → gone. */
    public float alpha(long now) {
        long age = ageMs(now);
        long fadeStart = (lifetimeMs * 3L) / 4L;
        if (age <= fadeStart) return 1.0f;
        long fadeSpan = lifetimeMs - fadeStart;
        if (fadeSpan <= 0) return 0.0f;
        float t = (age - fadeStart) / (float) fadeSpan;
        return Math.max(0.0f, Math.min(1.0f, 1.0f - t));
    }
}
