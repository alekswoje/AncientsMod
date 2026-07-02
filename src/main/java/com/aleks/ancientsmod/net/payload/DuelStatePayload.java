package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded form of {@link Protocol#PKT_DUEL_STATE}.
 *
 * <p>Single byte: 0 = not in a duel fight, anything else = in a duel.
 */
public record DuelStatePayload(boolean inDuel) {

    public static DuelStatePayload decode(PacketByteBuf buf) {
        byte b = buf.readByte();
        return new DuelStatePayload(b != 0);
    }
}
