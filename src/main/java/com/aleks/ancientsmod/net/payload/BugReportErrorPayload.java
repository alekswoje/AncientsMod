package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded {@code PKT_BUGREPORT_ERROR}: a soft error in the bug-report flow
 * (rate-limited, token expired, AI offline). The mod shows the message as a
 * notice in the chat thread and leaves the UI open so the user can retry.
 */
public final class BugReportErrorPayload {

    public final String token;
    public final String message;

    private BugReportErrorPayload(String token, String message) {
        this.token = token;
        this.message = message;
    }

    public static BugReportErrorPayload decode(PacketByteBuf buf) {
        String token = clamp(buf.readString(Protocol.BUGREPORT_MAX_TOKEN_CHARS),
                Protocol.BUGREPORT_MAX_TOKEN_CHARS);
        String message = clamp(buf.readString(Protocol.BUGREPORT_MAX_AI_MESSAGE_CHARS),
                Protocol.BUGREPORT_MAX_AI_MESSAGE_CHARS);
        return new BugReportErrorPayload(token, message);
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }
}
