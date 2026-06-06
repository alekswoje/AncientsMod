package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded form of {@link Protocol#PKT_MINING_STATS}.
 *
 * <p>Wire format: {@code varint xpPerHour, varint energyPerHour, varint moneyPerHour,
 * varint blocksPerHour}. All are non-negative; the server clamps to
 * {@code Integer.MAX_VALUE} before sending so the varint stays bounded.
 *
 * <p>{@code blocksPerHour} (prestige-weighted) was appended after the original
 * 3-rate format shipped; it's read only when the buffer still has bytes, so an
 * older server decodes it as 0 instead of throwing.
 */
public record MiningStatsPayload(long xpPerHour, long energyPerHour, long moneyPerHour,
                                 long blocksPerHour, long receivedMs) {

    public static MiningStatsPayload decode(PacketByteBuf buf) {
        long xp     = Math.max(0L, buf.readVarInt());
        long energy = Math.max(0L, buf.readVarInt());
        long money  = Math.max(0L, buf.readVarInt());
        long blocks = buf.isReadable() ? Math.max(0L, buf.readVarInt()) : 0L;
        return new MiningStatsPayload(xp, energy, money, blocks, System.currentTimeMillis());
    }
}
