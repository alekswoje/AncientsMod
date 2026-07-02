package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded form of {@link Protocol#PKT_MINING_SESSION} — the player's manual
 * {@code /miningtrack} session snapshot.
 *
 * <p>Wire format: {@code byte state (1=running, 2=stopped); varlong elapsedMs;
 * varlong totalXp; varlong totalEnergy; varlong totalMoney; varlong totalBlocks}.
 * All longs are non-negative. {@code totalBlocks} is prestige-weighted, matching
 * the live {@link MiningStatsPayload#blocksPerHour()} convention.
 */
public record MiningSessionPayload(int state, long elapsedMs, long totalXp, long totalEnergy,
                                   long totalMoney, long totalBlocks, long receivedMs) {

    public static MiningSessionPayload decode(PacketByteBuf buf) {
        int state    = buf.readByte() & 0xFF;
        long elapsed = Math.max(0L, buf.readVarLong());
        long xp      = Math.max(0L, buf.readVarLong());
        long energy  = Math.max(0L, buf.readVarLong());
        long money   = Math.max(0L, buf.readVarLong());
        long blocks  = Math.max(0L, buf.readVarLong());
        return new MiningSessionPayload(state, elapsed, xp, energy, money, blocks,
                System.currentTimeMillis());
    }

    public boolean running() { return state == Protocol.MINING_SESSION_STATE_RUNNING; }
}
