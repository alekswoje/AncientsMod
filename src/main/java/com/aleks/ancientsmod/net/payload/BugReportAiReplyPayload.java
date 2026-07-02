package com.aleks.ancientsmod.net.payload;

import com.aleks.ancientsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded {@code PKT_BUGREPORT_AI_REPLY}: a single AI message in the bug-report
 * chat thread, plus a status byte that tells the UI whether the conversation is
 * still open ({@link Protocol#BR_STATUS_REPLIED}), wrapped up by the AI
 * ({@link Protocol#BR_STATUS_RESOLVED}), handed off to humans
 * ({@link Protocol#BR_STATUS_ESCALATED}), or errored ({@link Protocol#BR_STATUS_ERROR}).
 */
public final class BugReportAiReplyPayload {

    public final String token;
    public final String message;
    public final byte status;

    private BugReportAiReplyPayload(String token, String message, byte status) {
        this.token = token;
        this.message = message;
        this.status = status;
    }

    public static BugReportAiReplyPayload decode(PacketByteBuf buf) {
        String token = clamp(buf.readString(Protocol.BUGREPORT_MAX_TOKEN_CHARS),
                Protocol.BUGREPORT_MAX_TOKEN_CHARS);
        String message = clamp(buf.readString(Protocol.BUGREPORT_MAX_AI_MESSAGE_CHARS),
                Protocol.BUGREPORT_MAX_AI_MESSAGE_CHARS);
        byte status = buf.readByte();
        return new BugReportAiReplyPayload(token, message, status);
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }
}
