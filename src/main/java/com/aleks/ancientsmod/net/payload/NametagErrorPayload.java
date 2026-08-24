package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded {@code PKT_NAMETAG_ERROR}. A non-empty token is a soft error against a
 * live session (the screen stays open, message shown); an empty token is fatal
 * (no session exists any more, so the screen closes).
 */
public final class NametagErrorPayload {

    public final String token;
    public final String message;

    private NametagErrorPayload(String token, String message) {
        this.token = token;
        this.message = message;
    }

    public static NametagErrorPayload decode(PacketByteBuf buf) {
        String token = clamp(buf.readString(Protocol.NAMETAG_MAX_TOKEN_CHARS),
                Protocol.NAMETAG_MAX_TOKEN_CHARS);
        String message = clamp(buf.readString(Protocol.NAMETAG_MAX_ERROR_CHARS),
                Protocol.NAMETAG_MAX_ERROR_CHARS);
        return new NametagErrorPayload(token, message);
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }
}
