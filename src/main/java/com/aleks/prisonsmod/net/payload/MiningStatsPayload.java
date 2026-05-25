package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded form of {@link Protocol#PKT_MINING_STATS}.
 *
 * <p>Wire format: {@code varint xpPerHour, varint energyPerHour, varint moneyPerHour}.
 * All three are non-negative; the server clamps to {@code Integer.MAX_VALUE}
 * before sending so the varint stays bounded.
 */
public record MiningStatsPayload(long xpPerHour, long energyPerHour, long moneyPerHour, long receivedMs) {

    public static MiningStatsPayload decode(PacketByteBuf buf) {
        long xp     = Math.max(0L, buf.readVarInt());
        long energy = Math.max(0L, buf.readVarInt());
        long money  = Math.max(0L, buf.readVarInt());
        return new MiningStatsPayload(xp, energy, money, System.currentTimeMillis());
    }
}
