package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.client.DisabledTextures;
import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.HashSet;
import java.util.Set;

/**
 * Decoded form of {@link Protocol#PKT_DISABLED_TEXTURES}: the set of custom
 * textures the player has turned off.
 *
 * <p>Wire format: {@code varint count; for each: string itemId (≤64), varint cmd}.
 * Bounded by {@link Protocol#MAX_DISABLED_TEXTURES} on decode.
 */
public record DisabledTexturesPayload(Set<String> keys) {

    public static DisabledTexturesPayload decode(PacketByteBuf buf) {
        int rawCount = buf.readVarInt();
        int count = Math.min(Math.max(0, rawCount), Protocol.MAX_DISABLED_TEXTURES);
        Set<String> set = new HashSet<>(Math.max(4, count));
        for (int i = 0; i < count; i++) {
            String itemId = buf.readString(64);
            int cmd = buf.readVarInt();
            if (itemId != null && !itemId.isEmpty() && cmd > 0) {
                set.add(DisabledTextures.key(itemId, cmd));
            }
        }
        return new DisabledTexturesPayload(set);
    }
}
