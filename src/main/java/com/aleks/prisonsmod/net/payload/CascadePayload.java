package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Server-emitted Cascading Vein event. Triggers a "big" visual — particles,
 * screen shake scaled by blocks broken, combo-style banner.
 *
 * <p>Wire format (after type byte):
 * {@code int points, int blocksBroken, byte size, double x, y, z}.
 */
public record CascadePayload(int points, int blocksBroken, int size,
                              double x, double y, double z) {

    public static CascadePayload decode(PacketByteBuf buf) {
        int points = buf.readInt();
        int blocksBroken = buf.readInt();
        int size = buf.readByte() & 0xFF;
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();

        if (points < 0 || points > Protocol.MAX_POINTS_PER_EVENT) {
            throw new IllegalArgumentException("points out of range: " + points);
        }
        if (blocksBroken <= 0 || blocksBroken > Protocol.MAX_BLOCKS_PER_CASCADE) {
            throw new IllegalArgumentException("blocksBroken out of range: " + blocksBroken);
        }
        if (size < 3 || size > 15) {
            throw new IllegalArgumentException("cascade size out of range: " + size);
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("non-finite coordinate");
        }
        if (Math.abs(x) > 3.0e7 || Math.abs(y) > 3.0e7 || Math.abs(z) > 3.0e7) {
            throw new IllegalArgumentException("coordinate out of world bounds");
        }
        return new CascadePayload(points, blocksBroken, size, x, y, z);
    }
}
