package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded form of {@link Protocol#PKT_MINING_RUSH_PING_CLEAR}.
 *
 * <p>Identifies a finished mining rush by the block its beam was anchored to
 * (world + coords) so the client can drop the matching
 * {@link MiningRushPingPayload}-spawned marker the instant the rush ends,
 * instead of waiting out the beam's full expiry-window lifetime. Coordinates
 * are bounds-checked at decode time.
 */
public record MiningRushPingClearPayload(String worldName, double x, double y, double z) {

    public static MiningRushPingClearPayload decode(PacketByteBuf buf) {
        String rawWorld = buf.readString(Protocol.GANG_PING_MAX_WORLD_CHARS * 4);
        String world = rawWorld.length() > Protocol.GANG_PING_MAX_WORLD_CHARS
                ? rawWorld.substring(0, Protocol.GANG_PING_MAX_WORLD_CHARS)
                : rawWorld;
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("non-finite coordinates");
        }
        return new MiningRushPingClearPayload(world, x, y, z);
    }
}
