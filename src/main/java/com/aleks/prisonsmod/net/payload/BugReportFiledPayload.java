package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

/**
 * Decoded {@code PKT_BUGREPORT_FILED}: confirmation the server persisted the
 * report. Carries the {@code BR-XXXXXX} id so the chat-thread header can show
 * it (and the player can copy/paste it into Discord if they want to).
 */
public final class BugReportFiledPayload {

    public final String token;
    public final String reportId;

    private BugReportFiledPayload(String token, String reportId) {
        this.token = token;
        this.reportId = reportId;
    }

    public static BugReportFiledPayload decode(PacketByteBuf buf) {
        String token = clamp(buf.readString(Protocol.BUGREPORT_MAX_TOKEN_CHARS),
                Protocol.BUGREPORT_MAX_TOKEN_CHARS);
        String reportId = clamp(buf.readString(Protocol.BUGREPORT_MAX_REPORT_ID_CHARS),
                Protocol.BUGREPORT_MAX_REPORT_ID_CHARS);
        return new BugReportFiledPayload(token, reportId);
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }
}
