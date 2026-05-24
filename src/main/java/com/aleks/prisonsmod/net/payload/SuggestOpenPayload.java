package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/** Decoded {@code PKT_SUGGEST_OPEN}: token only — the screen renders blank inputs. */
public final class SuggestOpenPayload {

    public final String token;

    private SuggestOpenPayload(String token) {
        this.token = token;
    }

    public static SuggestOpenPayload decode(PacketByteBuf buf) {
        String token = clamp(buf.readString(Protocol.SUGGEST_MAX_TOKEN_CHARS),
                Protocol.SUGGEST_MAX_TOKEN_CHARS);
        if (token.isEmpty()) {
            throw new IllegalArgumentException("suggest open: empty token");
        }
        return new SuggestOpenPayload(token);
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }
}
