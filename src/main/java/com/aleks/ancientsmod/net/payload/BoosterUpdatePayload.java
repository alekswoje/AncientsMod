package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoded form of {@link Protocol#PKT_BOOSTER_UPDATE}.
 *
 * <p>Wire format: {@code byte count; for each entry: byte source, byte kind,
 * varint multX100, varint secondsRemaining, byte paused}.
 *
 * <p>Bounded by {@link Protocol#MAX_BOOSTER_ENTRIES} on decode — a misbehaving
 * server cannot make us allocate an unbounded list.
 */
public record BoosterUpdatePayload(List<Entry> entries, long receivedMs) {

    /** Single booster row: a (source, kind, multiplier, time-remaining, paused) tuple. */
    public record Entry(byte source, byte kind, double multiplier, int secondsRemaining, boolean paused) {}

    public static BoosterUpdatePayload decode(PacketByteBuf buf) {
        int rawCount = buf.readByte() & 0xFF;
        int count = Math.min(rawCount, Protocol.MAX_BOOSTER_ENTRIES);
        List<Entry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte source = buf.readByte();
            byte kind = buf.readByte();
            int multX100 = buf.readVarInt();
            int secondsRemaining = buf.readVarInt();
            byte paused = buf.readByte();
            // Defensive bounds — a malformed server cannot blow up our renderer with
            // absurd multipliers or negative seconds.
            if (multX100 < 0 || multX100 > 10_000) continue;        // ≤ 100x sane upper bound
            if (secondsRemaining < 0 || secondsRemaining > 7 * 24 * 3600) continue; // ≤ 7d
            list.add(new Entry(source, kind, multX100 / 100.0, secondsRemaining, paused != 0));
        }
        return new BoosterUpdatePayload(list, System.currentTimeMillis());
    }
}
