package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded form of {@link Protocol#PKT_HOT_ZONE_PING}.
 *
 * <p>Wire format is byte-for-byte identical to {@link MiningRushPingPayload}
 * (label, RGB, x/y/z, world name, lifetimeMs) — hot-zone pings reuse the same
 * beam renderer. It's a separate packet/payload so the client can gate it
 * behind its own toggle. All fields are bounds-checked at decode time.
 */
public record HotZonePingPayload(String label, int colorRgb,
                                 double x, double y, double z,
                                 String worldName, int lifetimeMs) {

    public static HotZonePingPayload decode(PacketByteBuf buf) {
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
        return new HotZonePingPayload(label, rgb, x, y, z, world, lifetime);
    }
}
