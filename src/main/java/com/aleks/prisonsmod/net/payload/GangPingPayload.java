package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded form of {@link Protocol#PKT_GANG_PING}.
 *
 * <p>All fields are bounds-checked at decode time. The server computed this
 * packet's recipient list, so sender identity here is cosmetic (displayed
 * above the ping marker) — it never authorizes anything client-side.
 */
public record GangPingPayload(String senderName, int colorRgb,
                              double x, double y, double z,
                              String worldName) {

    public static GangPingPayload decode(PacketByteBuf buf) {
        String rawName = buf.readString(Protocol.GANG_PING_MAX_NAME_CHARS * 4);
        String name = rawName.length() > Protocol.GANG_PING_MAX_NAME_CHARS
                ? rawName.substring(0, Protocol.GANG_PING_MAX_NAME_CHARS)
                : rawName;
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
        // World name is optional on the wire — old servers don't send it;
        // decode defensively so we don't trip on a truncated/missing field.
        String world = "";
        if (buf.readableBytes() > 0) {
            try {
                String rawWorld = buf.readString(Protocol.GANG_PING_MAX_WORLD_CHARS * 4);
                world = rawWorld.length() > Protocol.GANG_PING_MAX_WORLD_CHARS
                        ? rawWorld.substring(0, Protocol.GANG_PING_MAX_WORLD_CHARS)
                        : rawWorld;
            } catch (Exception ignored) {
                // treat as missing
            }
        }
        return new GangPingPayload(name, rgb, x, y, z, world);
    }
}
