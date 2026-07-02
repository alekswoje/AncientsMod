package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoded form of {@link Protocol#PKT_COOLDOWNS}.
 *
 * <p>Wire format: {@code byte count; for each entry: byte category, byte id,
 * varint secondsRemaining}.
 */
public record CooldownsPayload(List<Entry> entries, long receivedMs) {

    public record Entry(byte category, byte id, int secondsRemaining) {}

    public static CooldownsPayload decode(PacketByteBuf buf) {
        int rawCount = buf.readByte() & 0xFF;
        int count = Math.min(rawCount, Protocol.MAX_COOLDOWN_ENTRIES);
        List<Entry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte cat = buf.readByte();
            byte id = buf.readByte();
            int seconds = buf.readVarInt();
            if (seconds < 0 || seconds > 7 * 24 * 3600) continue;
            list.add(new Entry(cat, id, seconds));
        }
        return new CooldownsPayload(list, System.currentTimeMillis());
    }
}
