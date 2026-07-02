package com.aleks.ancientsmod.client.gangping;

import com.aleks.ancientsmod.net.Protocol;

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

    /**
     * True for meteor pings that carry a server-provided impact time. When set,
     * the ping shows a countdown ("Lands in M:SS") that flips to a count-up
     * ("Landed +Ns") at impact, and its lifetime is governed by {@link #landingAtMs}
     * plus {@link Protocol#METEOR_COUNTUP_WINDOW_MS} rather than {@link #lifetimeMs}.
     */
    public final boolean hasCountdown;
    /** Wall-clock time (client clock) the meteor is predicted to land. Only meaningful when {@link #hasCountdown}. */
    public final long landingAtMs;

    public GangPing(String senderName, int colorRgb,
                    double x, double y, double z,
                    String worldName, long spawnedAtMs) {
        this(senderName, colorRgb, x, y, z, worldName, spawnedAtMs,
                Protocol.GANG_PING_LIFETIME_MS);
    }

    public GangPing(String senderName, int colorRgb,
                    double x, double y, double z,
                    String worldName, long spawnedAtMs, long lifetimeMs) {
        this(senderName, colorRgb, x, y, z, worldName, spawnedAtMs, lifetimeMs, false, 0L);
    }

    public GangPing(String senderName, int colorRgb,
                    double x, double y, double z,
                    String worldName, long spawnedAtMs, long lifetimeMs,
                    boolean hasCountdown, long landingAtMs) {
        this.senderName = senderName;
        this.colorRgb = colorRgb;
        this.x = x;
        this.y = y;
        this.z = z;
        this.worldName = worldName == null ? "" : worldName;
        this.spawnedAtMs = spawnedAtMs;
        this.lifetimeMs = Math.max(1L, lifetimeMs);
        this.hasCountdown = hasCountdown;
        this.landingAtMs = landingAtMs;
    }

    public long ageMs(long now) { return Math.max(0L, now - spawnedAtMs); }

    public boolean expired(long now) {
        // Countdown pings outlive their plain lifetime: they persist through the
        // whole fall, then for a fixed count-up window after impact.
        if (hasCountdown) return now >= landingAtMs + Protocol.METEOR_COUNTUP_WINDOW_MS;
        return ageMs(now) >= lifetimeMs;
    }

    /** Linear fade over the final 25% of the ping's lifetime. 1.0 → full opacity, 0.0 → gone. */
    public float alpha(long now) {
        if (hasCountdown) {
            // Solid for the entire countdown; fade only over the last quarter of
            // the post-landing count-up window so the beam doesn't flicker on the
            // server's 60s re-announce cadence.
            if (now < landingAtMs) return 1.0f;
            long elapsed = now - landingAtMs;
            long window = Protocol.METEOR_COUNTUP_WINDOW_MS;
            long fadeStart = (window * 3L) / 4L;
            if (elapsed <= fadeStart) return 1.0f;
            long fadeSpan = window - fadeStart;
            if (fadeSpan <= 0) return 0.0f;
            float t = (elapsed - fadeStart) / (float) fadeSpan;
            return Math.max(0.0f, Math.min(1.0f, 1.0f - t));
        }
        long age = ageMs(now);
        long fadeStart = (lifetimeMs * 3L) / 4L;
        if (age <= fadeStart) return 1.0f;
        long fadeSpan = lifetimeMs - fadeStart;
        if (fadeSpan <= 0) return 0.0f;
        float t = (age - fadeStart) / (float) fadeSpan;
        return Math.max(0.0f, Math.min(1.0f, 1.0f - t));
    }
}
