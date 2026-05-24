package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/** Decoded {@code PKT_SUGGEST_FILED}: token confirming the submission. */
public final class SuggestFiledPayload {

    public final String token;

    private SuggestFiledPayload(String token) {
        this.token = token;
    }

    public static SuggestFiledPayload decode(PacketByteBuf buf) {
        String token = clamp(buf.readString(Protocol.SUGGEST_MAX_TOKEN_CHARS),
                Protocol.SUGGEST_MAX_TOKEN_CHARS);
        return new SuggestFiledPayload(token);
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }
}
