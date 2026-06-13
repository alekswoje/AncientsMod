package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded form of {@link Protocol#PKT_MINING_STATS}.
 *
 * <p>Wire format: {@code varlong xpPerHour, varlong energyPerHour, varlong moneyPerHour,
 * varlong blocksPerHour}. All are non-negative. These were VarInts originally
 * (capped at {@code Integer.MAX_VALUE} ~2.1B/h); they're now VarLongs so high
 * prestige rates above 2.1B/h aren't clamped. VarLong reads an old server's
 * VarInt bytes identically, so this is backward-compatible.
 *
 * <p>{@code blocksPerHour} (prestige-weighted) was appended after the original
 * 3-rate format shipped; it's read only when the buffer still has bytes, so an
 * older server decodes it as 0 instead of throwing.
 */
public record MiningStatsPayload(long xpPerHour, long energyPerHour, long moneyPerHour,
                                 long blocksPerHour, long receivedMs) {

    public static MiningStatsPayload decode(PacketByteBuf buf) {
        long xp     = Math.max(0L, buf.readVarLong());
        long energy = Math.max(0L, buf.readVarLong());
        long money  = Math.max(0L, buf.readVarLong());
        long blocks = buf.isReadable() ? Math.max(0L, buf.readVarLong()) : 0L;
        return new MiningStatsPayload(xp, energy, money, blocks, System.currentTimeMillis());
    }
}
