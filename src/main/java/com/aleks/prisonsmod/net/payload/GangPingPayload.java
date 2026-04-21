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
public record GangPingPayload(String senderName, int colorRgb, double x, double y, double z) {

    public static GangPingPayload decode(PacketByteBuf buf) {
        String raw = buf.readString(Protocol.GANG_PING_MAX_NAME_CHARS * 4);
        String name = raw.length() > Protocol.GANG_PING_MAX_NAME_CHARS
                ? raw.substring(0, Protocol.GANG_PING_MAX_NAME_CHARS)
                : raw;
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
        return new GangPingPayload(name, rgb, x, y, z);
    }
}
