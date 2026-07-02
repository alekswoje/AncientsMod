package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Server-emitted floating-number event: "+points" at a world location.
 *
 * <p>Wire format (after the leading type byte): {@code int points, double x, y, z}.
 *
 * <p>All numeric fields are validated on decode. A failed validation throws
 * {@link IllegalArgumentException} which the receiver catches and drops.
 */
public record PointGainPayload(int points, double x, double y, double z) {

    public static PointGainPayload decode(PacketByteBuf buf) {
        int points = buf.readInt();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();

        if (points <= 0 || points > Protocol.MAX_POINTS_PER_EVENT) {
            throw new IllegalArgumentException("points out of range: " + points);
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("non-finite coordinate");
        }
        // Reject coordinates that are obviously outside any reasonable world.
        if (Math.abs(x) > 3.0e7 || Math.abs(y) > 3.0e7 || Math.abs(z) > 3.0e7) {
            throw new IllegalArgumentException("coordinate out of world bounds");
        }
        return new PointGainPayload(points, x, y, z);
    }
}
