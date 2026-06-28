package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded form of {@link Protocol#PKT_METEOR_PING}.
 *
 * <p>Wire format mirrors gang ping but carries an extra {@code lifetimeMs}
 * so the server can size each beam to the meteor's actual fall window, plus a
 * trailing {@code msUntilLanding} (time-to-impact at send time) that drives the
 * client-side countdown. All fields are bounds-checked at decode time.
 *
 * <p>{@code msUntilLanding} is optional on the wire — a plugin that predates the
 * countdown field simply doesn't append it, and decode falls back to
 * {@link Protocol#METEOR_PING_NO_COUNTDOWN} (no countdown shown).
 */
public record MeteorPingPayload(String label, int colorRgb,
                                double x, double y, double z,
                                String worldName, int lifetimeMs,
                                int msUntilLanding) {

    public static MeteorPingPayload decode(PacketByteBuf buf) {
        String rawLabel = buf.readString(Protocol.GANG_PING_MAX_NAME_CHARS * 4);
        String label = rawLabel.length() > Protocol.GANG_PING_MAX_NAME_CHARS
                ? rawLabel.substring(0, Protocol.GANG_PING_MAX_NAME_CHARS)
                : rawLabel;
        int r = buf.readByte() & 0xFF;
        int g = buf.readByte() & 0xFF;
        int b = buf.readByte() & 0xFF;
        int rgb = (r << 16) | (g << 8) | b;
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("non-finite coordinates");
        }
        String rawWorld = buf.readString(Protocol.GANG_PING_MAX_WORLD_CHARS * 4);
        String world = rawWorld.length() > Protocol.GANG_PING_MAX_WORLD_CHARS
                ? rawWorld.substring(0, Protocol.GANG_PING_MAX_WORLD_CHARS)
                : rawWorld;
        int rawLifetime = buf.readInt();
        int lifetime = Math.max(Protocol.METEOR_PING_MIN_LIFETIME_MS,
                Math.min(Protocol.METEOR_PING_MAX_LIFETIME_MS, rawLifetime));
        // Optional trailing field. Absent on older plugins → no countdown.
        // A negative value from the server is also treated as "unknown".
        int msUntilLanding = Protocol.METEOR_PING_NO_COUNTDOWN;
        if (buf.isReadable(4)) {
            int raw = buf.readInt();
            msUntilLanding = raw < 0 ? Protocol.METEOR_PING_NO_COUNTDOWN
                    : Math.min(raw, Protocol.METEOR_PING_MAX_LIFETIME_MS);
        }
        return new MeteorPingPayload(label, rgb, x, y, z, world, lifetime, msUntilLanding);
    }
}
