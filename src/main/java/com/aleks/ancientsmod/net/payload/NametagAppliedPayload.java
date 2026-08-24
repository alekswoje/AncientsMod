package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/** Decoded {@code PKT_NAMETAG_APPLIED}: token only — the rename landed, close the screen. */
public final class NametagAppliedPayload {

    public final String token;

    private NametagAppliedPayload(String token) {
        this.token = token;
    }

    public static NametagAppliedPayload decode(PacketByteBuf buf) {
        String token = buf.readString(Protocol.NAMETAG_MAX_TOKEN_CHARS);
        if (token == null) token = "";
        if (token.length() > Protocol.NAMETAG_MAX_TOKEN_CHARS) {
            token = token.substring(0, Protocol.NAMETAG_MAX_TOKEN_CHARS);
        }
        return new NametagAppliedPayload(token);
    }
}
