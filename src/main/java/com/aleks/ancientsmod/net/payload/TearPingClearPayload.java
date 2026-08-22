package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded form of {@link Protocol#PKT_TEAR_PING_CLEAR}.
 *
 * <p>Identifies a closed Erebus Tear by the block its beam was anchored to
 * (world + coords) so the client can drop the matching
 * {@link TearPingPayload}-spawned marker the instant the breach closes, instead
 * of waiting out the beam's remaining lifetime. Same wire shape as
 * {@link MiningRushPingClearPayload}; coordinates are bounds-checked at decode
 * time.
 */
public record TearPingClearPayload(String worldName, double x, double y, double z) {

    public static TearPingClearPayload decode(PacketByteBuf buf) {
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
        return new TearPingClearPayload(world, x, y, z);
    }
}
