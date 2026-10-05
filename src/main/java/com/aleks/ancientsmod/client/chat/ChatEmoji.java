package com.aleks.ancientsmod.client.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Shortcode handling kept independent of rendering and server connections. */
public final class ChatEmoji {
    /** Only supplementary-plane code points: the vanilla font can't draw them, so nothing else is overridden. */
    public record Emoji(String alias, int codePoint) {
        public String glyph() { return Character.toString(codePoint); }
    }

    // Order is the Tab-completion order. Artwork lives at textures/emoji/<name>.png.
    public static final List<Emoji> ALL = List.of(
            new Emoji(":skull:", 0x1F480),
            new Emoji(":bunny:", 0x1F430),
            new Emoji(":cry:", 0x1F62D),
            new Emoji(":joy:", 0x1F602),
            new Emoji(":fire:", 0x1F525),
            new Emoji(":eyes:", 0x1F440),
            new Emoji(":thumbsup:", 0x1F44D),
            new Emoji(":pray:", 0x1F64F),
            new Emoji(":crown:", 0x1F451),
            new Emoji(":100:", 0x1F4AF),
            new Emoji(":clown:", 0x1F921),
            new Emoji(":moyai:", 0x1F5FF),
            new Emoji(":rage:", 0x1F621),
            new Emoji(":thinking:", 0x1F914),
            new Emoji(":sunglasses:", 0x1F60E),
            new Emoji(":wave:", 0x1F44B),
            new Emoji(":sweat_smile:", 0x1F605),
            new Emoji(":pleading:", 0x1F97A));

    private static final Map<Integer, Emoji> BY_CODE_POINT =
            ALL.stream().collect(Collectors.toUnmodifiableMap(Emoji::codePoint, Function.identity()));

    private ChatEmoji() {}

    public static boolean isEmoji(int codePoint) {
        return BY_CODE_POINT.containsKey(codePoint);
    }

    /** The emoji whose alias starts at {@code index}, or null. */
    public static Emoji aliasAt(String text, int index) {
        if (index >= text.length() || text.charAt(index) != ':') return null;
        for (Emoji emoji : ALL) {
            if (text.startsWith(emoji.alias(), index)) return emoji;
        }
        return null;
    }

    public static boolean containsAlias(String text) {
        for (int i = text.indexOf(':'); i >= 0; i = text.indexOf(':', i + 1)) {
            if (aliasAt(text, i) != null) return true;
        }
        return false;
    }

    public static String expand(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            Emoji emoji = aliasAt(text, i);
            if (emoji != null) {
                out.append(emoji.glyph());
                i += emoji.alias().length();
            } else {
                out.append(text.charAt(i++));
            }
        }
        return out.toString();
    }

    /** Remap a UTF-16 caret/selection endpoint, snapping inside a token to its end. */
    public static int expandedIndex(String text, int index) {
        int result = Math.clamp(index, 0, text.length());
        for (int i = 0; i < text.length() && i < index;) {
            Emoji emoji = aliasAt(text, i);
            if (emoji == null) { i++; continue; }
            int length = emoji.alias().length();
            result -= Math.min(index - i, length) - emoji.glyph().length();
            i += length;
        }
        return result;
    }

    /** Complete only a colon-prefixed token, never commands, URLs or namespaces. */
    public static int completionStart(String text, int cursor) {
        if (text.startsWith("/") || cursor < 0 || cursor > text.length()) return -1;
        int start = text.lastIndexOf(':', cursor - 1);
        if (start < 0 || (start > 0 && !Character.isWhitespace(text.charAt(start - 1)))) return -1;
        return completions(text.substring(start, cursor)).isEmpty() ? -1 : start;
    }

    /** Aliases that extend {@code prefix}, excluding one already typed in full. */
    public static List<String> completions(String prefix) {
        List<String> matches = new ArrayList<>();
        for (Emoji emoji : ALL) {
            if (prefix.length() < emoji.alias().length() && emoji.alias().startsWith(prefix)) matches.add(emoji.alias());
        }
        return matches;
    }

    /** Keep server chat and unmodded clients readable; the recipient mod renders it. */
    public static String toWire(String text) {
        if (text.startsWith("/")) return text;
        StringBuilder readable = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            Emoji emoji = BY_CODE_POINT.get(cp);
            if (emoji != null) readable.append(emoji.alias());
            else readable.appendCodePoint(cp);
        });
        // Alias expansion must not make vanilla's 256-character limit eat the tail.
        return readable.length() <= 256 ? readable.toString() : text;
    }
}
