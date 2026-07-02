package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Periodic rift-HUD snapshot: current points, rank, roster size, time left.
 *
 * <p>Wire format: {@code int points, int rank, int totalPlayers, int timeRemainingMs}.
 */
public record HudUpdatePayload(int points, int rank, int totalPlayers, int timeRemainingMs) {

    public static HudUpdatePayload decode(PacketByteBuf buf) {
        int points = buf.readInt();
        int rank = buf.readInt();
        int totalPlayers = buf.readInt();
        int timeRemainingMs = buf.readInt();

        if (points < 0 || points > Protocol.MAX_POINTS_PER_EVENT) {
            throw new IllegalArgumentException("points out of range: " + points);
        }
        if (rank < 1 || rank > Protocol.MAX_RANK) {
            throw new IllegalArgumentException("rank out of range: " + rank);
        }
        if (totalPlayers < 1 || totalPlayers > Protocol.MAX_RANK) {
            throw new IllegalArgumentException("totalPlayers out of range: " + totalPlayers);
        }
        if (rank > totalPlayers) {
            throw new IllegalArgumentException("rank > totalPlayers");
        }
        if (timeRemainingMs < 0 || timeRemainingMs > Protocol.MAX_TIME_REMAINING_MS) {
            throw new IllegalArgumentException("timeRemainingMs out of range: " + timeRemainingMs);
        }
        return new HudUpdatePayload(points, rank, totalPlayers, timeRemainingMs);
    }
}
