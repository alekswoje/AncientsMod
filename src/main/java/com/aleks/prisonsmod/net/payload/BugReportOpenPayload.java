package com.aleks.prisonsmod.net.payload;

import com.aleks.prisonsmod.net.Protocol;
import net.minecraft.network.PacketByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoded {@code PKT_BUGREPORT_OPEN}: the sanitized snapshot the server would
 * file if the player hits Submit. The mod renders it as a stack of collapsible
 * sections in the bug-report screen.
 *
 * <p>Every field is bounds-clamped during decode — a malicious server cannot
 * blow up renderer state by sending oversized strings or section counts.
 *
 * <p>See {@link Protocol#PKT_BUGREPORT_OPEN} for the wire format.
 */
public final class BugReportOpenPayload {

    public final String token;
    public final String prefill;
    public final List<Section> sections;

    private BugReportOpenPayload(String token, String prefill, List<Section> sections) {
        this.token = token;
        this.prefill = prefill;
        this.sections = sections;
    }

    public static BugReportOpenPayload decode(PacketByteBuf buf) {
        String token = clamp(buf.readString(Protocol.BUGREPORT_MAX_TOKEN_CHARS),
                Protocol.BUGREPORT_MAX_TOKEN_CHARS);
        if (token.isEmpty()) {
            throw new IllegalArgumentException("bugreport open: empty token");
        }
        String prefill = clamp(buf.readString(Protocol.BUGREPORT_MAX_PREFILL_CHARS),
                Protocol.BUGREPORT_MAX_PREFILL_CHARS);

        int sectionCount = buf.readUnsignedByte();
        if (sectionCount > Protocol.BUGREPORT_MAX_SECTIONS) {
            throw new IllegalArgumentException("bugreport open: too many sections (" + sectionCount + ")");
        }
        List<Section> sections = new ArrayList<>(sectionCount);
        for (int i = 0; i < sectionCount; i++) {
            byte sectionId = buf.readByte();
            String title = clamp(buf.readString(Protocol.BUGREPORT_MAX_TITLE_CHARS),
                    Protocol.BUGREPORT_MAX_TITLE_CHARS);
            int lineCount = buf.readUnsignedByte();
            if (lineCount > Protocol.BUGREPORT_MAX_LINES_PER_SECTION) {
                throw new IllegalArgumentException("bugreport open: too many lines in section " + i
                        + " (" + lineCount + ")");
            }
            List<String> lines = new ArrayList<>(lineCount);
            for (int j = 0; j < lineCount; j++) {
                lines.add(clamp(buf.readString(Protocol.BUGREPORT_MAX_LINE_CHARS),
                        Protocol.BUGREPORT_MAX_LINE_CHARS));
            }
            sections.add(new Section(sectionId, title, lines));
        }
        return new BugReportOpenPayload(token, prefill, sections);
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    public static final class Section {
        public final byte sectionId;
        public final String title;
        public final List<String> lines;

        public Section(byte sectionId, String title, List<String> lines) {
            this.sectionId = sectionId;
            this.title = title;
            this.lines = lines;
        }
    }
}
