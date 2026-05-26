package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.HashSet;
import java.util.Set;

/**
 * Decoded form of {@link Protocol#PKT_FULLBRIGHT_BLACKLIST}.
 *
 * <p>Wire format (after type byte): {@code varint count; for each: varint+UTF8
 * worldName}. Bukkit world names map to dimension registry paths
 * (e.g. {@code "world"}, {@code "tartarus_rift"}, {@code "forgotten_polis"}).
 * Empty set means fullbright is allowed everywhere.
 */
public record FullbrightBlacklistPayload(Set<String> worlds) {

    public static FullbrightBlacklistPayload decode(PacketByteBuf buf) {
        int rawCount = buf.readVarInt();
        if (rawCount < 0 || rawCount > Protocol.MAX_FULLBRIGHT_WORLDS) {
            throw new IllegalArgumentException("blacklist count out of range: " + rawCount);
        }
        Set<String> worlds = new HashSet<>(rawCount);
        for (int i = 0; i < rawCount; i++) {
            String name = buf.readString(Protocol.MAX_FULLBRIGHT_WORLD_NAME_CHARS);
            if (name.isEmpty()) continue;
            worlds.add(name);
        }
        return new FullbrightBlacklistPayload(worlds);
    }
}
