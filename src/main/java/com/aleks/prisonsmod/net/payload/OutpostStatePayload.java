package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record OutpostStatePayload(List<Entry> entries) {

    public static OutpostStatePayload decode(PacketByteBuf buf) {
        int count = Math.min(buf.readByte() & 0xFF, Protocol.MAX_OUTPOST_ENTRIES);
        List<Entry> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = buf.readString(Protocol.OUTPOST_MAX_ID_CHARS);
            String gangName = buf.readString(Protocol.OUTPOST_MAX_GANG_CHARS);
            int percent = buf.readByte() & 0xFF;
            if (percent > 100) percent = 100;
            int flags = buf.readByte() & 0xFF;
            boolean hasReached100     = (flags & 1) != 0;
            boolean percentDecreasing = (flags & 2) != 0;
            boolean isOwnGang         = (flags & Protocol.OUTPOST_FLAG_OWN_GANG) != 0;
            if (id.isEmpty()) continue;
            out.add(new Entry(id, gangName, percent, hasReached100, percentDecreasing, isOwnGang));
        }
        return new OutpostStatePayload(Collections.unmodifiableList(out));
    }

    public record Entry(
            String id,
            String gangName,
            int percent,
            boolean hasReached100,
            boolean percentDecreasing,
            boolean isOwnGang
    ) {}
}
