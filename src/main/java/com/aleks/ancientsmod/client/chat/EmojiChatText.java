package com.aleks.ancientsmod.client.chat;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Transform the display copy only, retaining the source message and click/hover data. */
public final class EmojiChatText {
    private EmojiChatText() {}

    public static Text expand(Text source) {
        String plain = source.getString();
        if (!ChatEmoji.containsAlias(plain)) return source;
        List<Style> styles = new ArrayList<>(plain.length());
        source.visit((style, value) -> {
            for (int i = 0; i < value.length(); i++) styles.add(style);
            return Optional.empty();
        }, Style.EMPTY);
        MutableText result = Text.empty();
        for (int i = 0; i < plain.length();) {
            Style style = styles.get(i);
            ChatEmoji.Emoji emoji = ChatEmoji.aliasAt(plain, i);
            if (emoji != null) {
                result.append(Text.literal(emoji.glyph()).setStyle(style));
                i += emoji.alias().length();
            } else {
                int end = i + 1;
                while (end < plain.length() && styles.get(end).equals(style)
                        && ChatEmoji.aliasAt(plain, end) == null) end++;
                result.append(Text.literal(plain.substring(i, end)).setStyle(style));
                i = end;
            }
        }
        return result;
    }
}
