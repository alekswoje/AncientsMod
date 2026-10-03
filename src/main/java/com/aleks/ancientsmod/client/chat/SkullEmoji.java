package com.aleks.ancientsmod.client.chat;

/** Shortcode handling kept independent of rendering and server connections. */
public final class SkullEmoji {
    public static final String ALIAS = ":skull:";
    public static final String GLYPH = "\uD83D\uDC80";
    public static final int CODE_POINT = 0x1F480;

    private SkullEmoji() {}

    public static String expand(String text) {
        return text.replace(ALIAS, GLYPH);
    }

    /** Remap a UTF-16 caret/selection endpoint, snapping inside a token to its end. */
    public static int expandedIndex(String text, int index) {
        int result = Math.clamp(index, 0, text.length());
        for (int start = text.indexOf(ALIAS); start >= 0; start = text.indexOf(ALIAS, start + ALIAS.length())) {
            if (index <= start) break;
            result -= Math.min(index - start, ALIAS.length()) - GLYPH.length();
        }
        return result;
    }

    /** Complete only a colon-prefixed token, never commands, URLs or namespaces. */
    public static int completionStart(String text, int cursor) {
        if (text.startsWith("/") || cursor < 0 || cursor > text.length()) return -1;
        int start = text.lastIndexOf(':', cursor - 1);
        if (start < 0 || (start > 0 && !Character.isWhitespace(text.charAt(start - 1)))) return -1;
        String prefix = text.substring(start, cursor);
        return prefix.length() < ALIAS.length() && ALIAS.startsWith(prefix) ? start : -1;
    }

    /** Keep server chat and unmodded clients readable; the recipient mod renders it. */
    public static String toWire(String text) {
        if (text.startsWith("/")) return text;
        String readable = text.replace(GLYPH, ALIAS);
        // Alias expansion must not make vanilla's 256-character limit eat the tail.
        return readable.length() <= 256 ? readable : text;
    }
}
