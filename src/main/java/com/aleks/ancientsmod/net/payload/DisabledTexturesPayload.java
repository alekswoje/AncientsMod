package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.HashSet;
import java.util.Set;

/**
 * Decoded form of {@link Protocol#PKT_DISABLED_TEXTURES}: the set of custom
 * textures the player has turned off.
 *
 * <p>Wire format: {@code varint count; for each: string key ("<item-id>#<cmd>", ≤96)}.
 * The plugin already builds the {@code item#cmd} key, so each entry is one string.
 * Bounded by {@link Protocol#MAX_DISABLED_TEXTURES} on decode.
 */
public record DisabledTexturesPayload(Set<String> keys) {

    public static DisabledTexturesPayload decode(PacketByteBuf buf) {
        int rawCount = buf.readVarInt();
        int count = Math.min(Math.max(0, rawCount), Protocol.MAX_DISABLED_TEXTURES);
        Set<String> set = new HashSet<>(Math.max(4, count));
        for (int i = 0; i < count; i++) {
            String key = buf.readString(96);
            if (key != null && !key.isEmpty()) set.add(key);
        }
        return new DisabledTexturesPayload(set);
    }
}
