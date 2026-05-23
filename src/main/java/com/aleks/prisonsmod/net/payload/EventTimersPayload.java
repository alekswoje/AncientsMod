package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoded form of {@link Protocol#PKT_EVENT_TIMERS}.
 *
 * <p>Wire format: {@code byte count; for each entry: byte eventId, byte state,
 * (varint secondsUntilFire iff state == COUNTDOWN)}.
 */
public record EventTimersPayload(List<Entry> entries, long receivedMs) {

    public record Entry(byte eventId, byte state, int secondsUntilFire) {}

    public static EventTimersPayload decode(PacketByteBuf buf) {
        int rawCount = buf.readByte() & 0xFF;
        int count = Math.min(rawCount, Protocol.MAX_EVENT_ENTRIES);
        List<Entry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte eventId = buf.readByte();
            byte state = buf.readByte();
            int seconds = 0;
            if (state == Protocol.EVENT_STATE_COUNTDOWN) {
                seconds = buf.readVarInt();
                if (seconds < 0 || seconds > 7 * 24 * 3600) continue; // ≤ 7d defensive
            }
            list.add(new Entry(eventId, state, seconds));
        }
        return new EventTimersPayload(list, System.currentTimeMillis());
    }
}
