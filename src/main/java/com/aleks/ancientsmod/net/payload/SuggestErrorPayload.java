package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded {@code PKT_SUGGEST_ERROR}: soft error from the server. An empty token
 * is the "fatal — close the screen" signal (no session was ever issued).
 */
public final class SuggestErrorPayload {

    public final String token;
    public final String message;

    private SuggestErrorPayload(String token, String message) {
        this.token = token;
        this.message = message;
    }

    public static SuggestErrorPayload decode(PacketByteBuf buf) {
        String token = clamp(buf.readString(Protocol.SUGGEST_MAX_TOKEN_CHARS),
                Protocol.SUGGEST_MAX_TOKEN_CHARS);
        String message = clamp(buf.readString(Protocol.SUGGEST_MAX_ERROR_CHARS),
                Protocol.SUGGEST_MAX_ERROR_CHARS);
        return new SuggestErrorPayload(token, message);
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }
}
