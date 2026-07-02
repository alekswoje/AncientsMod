package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;

/**
 * Server says: "you just started mining the block at (x, y, z); it is expected
 * to break in {@code durationMs} milliseconds." The mod uses this to begin a
 * predicted break-crack animation immediately, hiding the ~100ms round-trip
 * before the server's normal {@code BlockDestructionPacket} would arrive.
 *
 * <p>Wire format: {@code int x, int y, int z, int durationMs}.
 */
public record MineStartPayload(BlockPos pos, int durationMs) {

    public static MineStartPayload decode(PacketByteBuf buf) {
        int x = buf.readInt();
        int y = buf.readInt();
        int z = buf.readInt();
        int durationMs = buf.readInt();

        if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000) {
            throw new IllegalArgumentException("xz out of world bounds");
        }
        if (y < -2048 || y > 2048) {
            throw new IllegalArgumentException("y out of world bounds: " + y);
        }
        if (durationMs < 0 || durationMs > Protocol.MAX_MINE_DURATION_MS) {
            throw new IllegalArgumentException("durationMs out of range: " + durationMs);
        }
        return new MineStartPayload(new BlockPos(x, y, z), durationMs);
    }
}
