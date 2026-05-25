package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decoded form of {@link Protocol#PKT_PVE_STATS}.
 *
 * <p>Wire format: {@code varint+string worldName, byte killCount,
 * (varint+string kind, varint count) * killCount, byte dropCount,
 * (varint+string kind, varint count) * dropCount}.
 */
public record PveStatsPayload(String worldName, Map<String, Integer> kills, Map<String, Integer> drops, long receivedMs) {

    public static PveStatsPayload decode(PacketByteBuf buf) {
        String world = buf.readString(64);
        if (world.length() > 64) world = world.substring(0, 64);

        Map<String, Integer> kills = decodeMap(buf);
        Map<String, Integer> drops = decodeMap(buf);
        return new PveStatsPayload(world, kills, drops, System.currentTimeMillis());
    }

    private static Map<String, Integer> decodeMap(PacketByteBuf buf) {
        int rawCount = buf.readByte() & 0xFF;
        // LinkedHashMap preserves wire order — the server sends kinds in the
        // order they were first seen, which gives a stable display order.
        Map<String, Integer> out = new LinkedHashMap<>(Math.min(rawCount, Protocol.MAX_PVE_STAT_ROWS));
        // Loop the full rawCount so any entries past our cap are still drained
        // from the buffer — otherwise the next field (drop count) gets read
        // out of position. Lets the server cap grow past the client's without
        // corrupting the rest of the packet.
        for (int i = 0; i < rawCount; i++) {
            String kind = buf.readString(48);
            int value = buf.readVarInt();
            if (out.size() >= Protocol.MAX_PVE_STAT_ROWS) continue;
            if (value < 0 || value > 1_000_000) continue;
            out.put(kind, value);
        }
        return out;
    }
}
