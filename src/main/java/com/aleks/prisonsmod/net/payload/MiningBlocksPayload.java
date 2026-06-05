package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoded form of {@link Protocol#PKT_MINING_BLOCKS}.
 *
 * <p>Wire format: {@code byte count; for each: varint+string oreId,
 * varint lifetime, varint session}. {@code oreId} is the Bukkit Material name
 * (e.g. {@code "DIAMOND_ORE"}); the HUD prettifies it for display.
 */
public record MiningBlocksPayload(List<Row> rows, long receivedMs) {

    /** One ore's mined-block counts: lifetime on the held pickaxe + this-session. */
    public record Row(String oreId, long lifetime, long session) {}

    public static MiningBlocksPayload decode(PacketByteBuf buf) {
        int rawCount = buf.readByte() & 0xFF;
        List<Row> rows = new ArrayList<>(Math.min(rawCount, Protocol.MAX_MINING_BLOCK_ROWS));
        // Drain the full rawCount even past our cap so the buffer stays aligned
        // if the server cap ever grows beyond the client's.
        for (int i = 0; i < rawCount; i++) {
            String oreId = buf.readString(Protocol.MINING_BLOCK_MAX_ID_CHARS);
            long lifetime = Math.max(0L, buf.readVarInt());
            long session = Math.max(0L, buf.readVarInt());
            if (rows.size() >= Protocol.MAX_MINING_BLOCK_ROWS) continue;
            if (oreId == null || oreId.isEmpty()) continue;
            rows.add(new Row(oreId, lifetime, session));
        }
        return new MiningBlocksPayload(rows, System.currentTimeMillis());
    }
}
