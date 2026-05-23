package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded form of {@link Protocol#PKT_METEORITE_HUD}.
 *
 * <p>Wire format: {@code varint+string worldName; int x, y, z;
 * varint+string tierName; byte R, G, B; byte refined; int remaining}.
 *
 * <p>{@code remaining == 0} is the "destroyed" signal — when the location
 * matches the tracked meteorite the client clears its HUD entry.
 */
public record MeteoriteHudPayload(String worldName,
                                  int x, int y, int z,
                                  String tierName, int colorRgb,
                                  boolean refined, int remaining) {

    public static MeteoriteHudPayload decode(PacketByteBuf buf) {
        String rawWorld = buf.readString(Protocol.METEORITE_HUD_MAX_WORLD_CHARS * 4);
        String world = rawWorld.length() > Protocol.METEORITE_HUD_MAX_WORLD_CHARS
                ? rawWorld.substring(0, Protocol.METEORITE_HUD_MAX_WORLD_CHARS)
                : rawWorld;
        int x = buf.readInt();
        int y = buf.readInt();
        int z = buf.readInt();
        String rawTier = buf.readString(Protocol.METEORITE_HUD_MAX_TIER_CHARS * 4);
        String tier = rawTier.length() > Protocol.METEORITE_HUD_MAX_TIER_CHARS
                ? rawTier.substring(0, Protocol.METEORITE_HUD_MAX_TIER_CHARS)
                : rawTier;
        int r = buf.readByte() & 0xFF;
        int g = buf.readByte() & 0xFF;
        int b = buf.readByte() & 0xFF;
        int rgb = (r << 16) | (g << 8) | b;
        boolean refined = buf.readByte() != 0;
        int rawRemaining = buf.readInt();
        if (rawRemaining < 0) rawRemaining = 0;
        if (rawRemaining > Protocol.METEORITE_HUD_MAX_REMAINING) {
            rawRemaining = Protocol.METEORITE_HUD_MAX_REMAINING;
        }
        return new MeteoriteHudPayload(world, x, y, z, tier, rgb, refined, rawRemaining);
    }
}
