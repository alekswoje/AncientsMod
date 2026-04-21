package com.aleks.prisonsmod.client.gangping;

import com.aleks.prisonsmod.net.Protocol;

/**
 * One live gang ping being rendered in-world.
 *
 * <p>Identified by sender name (used as the dedupe key — a player's new ping
 * replaces their previous one, per the feature spec). Fade runs in the last
 * quarter of its lifetime, driven by {@link #alpha(long)}.
 */
public final class GangPing {

    public final String senderName;
    public final int colorRgb;
    public final double x;
    public final double y;
    public final double z;
    public final String worldName;
    public final long spawnedAtMs;

    public GangPing(String senderName, int colorRgb,
                    double x, double y, double z,
                    String worldName, long spawnedAtMs) {
        this.senderName = senderName;
        this.colorRgb = colorRgb;
        this.x = x;
        this.y = y;
        this.z = z;
        this.worldName = worldName == null ? "" : worldName;
        this.spawnedAtMs = spawnedAtMs;
    }

    public long ageMs(long now) { return Math.max(0L, now - spawnedAtMs); }

    public boolean expired(long now) { return ageMs(now) >= Protocol.GANG_PING_LIFETIME_MS; }

    /** Linear fade over the final 25% of the ping's lifetime. 1.0 → full opacity, 0.0 → gone. */
    public float alpha(long now) {
        long age = ageMs(now);
        long fadeStart = (Protocol.GANG_PING_LIFETIME_MS * 3L) / 4L;
        if (age <= fadeStart) return 1.0f;
        long fadeSpan = Protocol.GANG_PING_LIFETIME_MS - fadeStart;
        if (fadeSpan <= 0) return 0.0f;
        float t = (age - fadeStart) / (float) fadeSpan;
        return Math.max(0.0f, Math.min(1.0f, 1.0f - t));
    }
}
