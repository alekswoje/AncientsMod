package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The player's jewel sockets (server → mod), for the jewel-slot HUD.
 *
 * <p>Wire: {@code count}, then per slot {@code state, requiredPrestige,
 * rarityOrdinal, familyName, statCount, statLines[]}. Every field is written
 * for every slot regardless of state, so the decode never branches on the
 * wire — unused fields arrive as 0 / "".
 */
public record JewelSlotsPayload(List<Slot> slots) {

    public static JewelSlotsPayload decode(PacketByteBuf buf) {
        int count = Math.min(buf.readByte() & 0xFF, Protocol.MAX_JEWEL_SLOTS);
        List<Slot> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int state = buf.readByte() & 0xFF;
            int requiredPrestige = buf.readByte() & 0xFF;
            int rarityOrdinal = buf.readByte() & 0xFF;
            String familyName = buf.readString(Protocol.JEWEL_MAX_FAMILY_CHARS);
            int statCount = Math.min(buf.readByte() & 0xFF, Protocol.MAX_JEWEL_STATS);
            List<String> stats = new ArrayList<>(statCount);
            for (int s = 0; s < statCount; s++) {
                stats.add(buf.readString(Protocol.JEWEL_MAX_STAT_CHARS));
            }
            if (state > Protocol.JEWEL_STATE_FILLED) state = Protocol.JEWEL_STATE_EMPTY;
            if (rarityOrdinal > 7) rarityOrdinal = 7;
            out.add(new Slot(state, requiredPrestige, rarityOrdinal, familyName,
                    Collections.unmodifiableList(stats)));
        }
        return new JewelSlotsPayload(Collections.unmodifiableList(out));
    }

    public record Slot(int state, int requiredPrestige, int rarityOrdinal,
                       String familyName, List<String> statLines) {

        public boolean isLocked() { return state == Protocol.JEWEL_STATE_LOCKED; }
        public boolean isFilled() { return state == Protocol.JEWEL_STATE_FILLED; }
    }
}
