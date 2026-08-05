package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-ore predicted break time + post-break replacement block table — the
 * payload of {@link Protocol#PKT_MINE_SPEEDS}. Drives swing-time mine
 * prediction: with this table the client predicts a block's whole break
 * timeline (and final replacement state) locally, with zero round trips.
 *
 * <p>Wire format after the type byte:
 * <pre>
 *   byte count                  (≤ {@link Protocol#MAX_MINE_SPEED_ROWS})
 *   for each:
 *     varint+string oreId         (Bukkit Material name, ≤ {@link Protocol#MINE_SPEED_MAX_ID_CHARS})
 *     varint        durationMs    (≤ {@link Protocol#MAX_MINE_DURATION_MS})
 *     varint+string replacementId (Bukkit Material name, ≤ {@link Protocol#MINE_SPEED_MAX_ID_CHARS})
 * </pre>
 */
public record MineSpeedsPayload(List<Row> rows) {

    public record Row(String oreId, int durationMs, String replacementId) {}

    public static MineSpeedsPayload decode(PacketByteBuf buf) {
        int count = buf.readUnsignedByte();
        if (count > Protocol.MAX_MINE_SPEED_ROWS) {
            throw new IllegalArgumentException("mine-speeds rows out of range: " + count);
        }
        List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String oreId = buf.readString(Protocol.MINE_SPEED_MAX_ID_CHARS);
            int durationMs = buf.readVarInt();
            String replacementId = buf.readString(Protocol.MINE_SPEED_MAX_ID_CHARS);
            if (durationMs < 0 || durationMs > Protocol.MAX_MINE_DURATION_MS) {
                throw new IllegalArgumentException("durationMs out of range: " + durationMs);
            }
            rows.add(new Row(oreId, durationMs, replacementId));
        }
        return new MineSpeedsPayload(rows);
    }
}
