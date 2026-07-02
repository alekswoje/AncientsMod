package com.aleks.ancientsmod.net.payload;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;

/**
 * Server says: "drop the in-flight mining prediction at (x, y, z)." Sent on
 * {@code BlockDamageAbortEvent} so a single tap doesn't briefly show the
 * predicted full-break animation.
 *
 * <p>Wire format: {@code int x, int y, int z}.
 */
public record MineCancelPayload(BlockPos pos) {

    public static MineCancelPayload decode(PacketByteBuf buf) {
        int x = buf.readInt();
        int y = buf.readInt();
        int z = buf.readInt();
        if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) {
            throw new IllegalArgumentException("xz out of world bounds");
        }
        if (y < -2048 || y > 2048) {
            throw new IllegalArgumentException("y out of world bounds: " + y);
        }
        return new MineCancelPayload(new BlockPos(x, y, z));
    }
}
