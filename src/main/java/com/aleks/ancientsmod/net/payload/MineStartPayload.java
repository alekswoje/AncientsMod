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
 * <p>Wire format: {@code int x, int y, int z, int durationMs[, int graceTicks]}.
 *
 * <p>{@code graceTicks} is an optional trailing field (servers from 2026-09-24 on):
 * the look-away completion grace the server will apply to this block, i.e. if the
 * player stops holding it with at most this many ticks left, the server finishes it
 * instead of pausing. {@code 0} means the server will not grace-finish this block.
 * Absent (older server) or negative decodes as {@link #GRACE_UNKNOWN}, and the mod
 * falls back to computing the grace itself. Every mod version before this one reads
 * exactly the first four ints and ignores anything after them, so appending the
 * field is safe for old clients.
 */
public record MineStartPayload(BlockPos pos, int durationMs, int graceTicks) {

    /** The server did not say what grace it applies (field absent or negative). */
    public static final int GRACE_UNKNOWN = -1;

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
        // Optional trailing field. Anything after it is ignored, so a later server
        // can append more fields without breaking this decoder either.
        int graceTicks = GRACE_UNKNOWN;
        if (buf.isReadable(4)) {
            int raw = buf.readInt();
            graceTicks = raw < 0 ? GRACE_UNKNOWN : Math.min(raw, Protocol.MAX_MINE_DURATION_MS / 50);
        }
        return new MineStartPayload(new BlockPos(x, y, z), durationMs, graceTicks);
    }
}
