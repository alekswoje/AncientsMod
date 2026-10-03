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
        if (!plain.contains(SkullEmoji.ALIAS)) return source;
        List<Style> styles = new ArrayList<>(plain.length());
        source.visit((style, value) -> {
            for (int i = 0; i < value.length(); i++) styles.add(style);
            return Optional.empty();
        }, Style.EMPTY);
        MutableText result = Text.empty();
        for (int i = 0; i < plain.length();) {
            Style style = styles.get(i);
            if (plain.startsWith(SkullEmoji.ALIAS, i)) {
                result.append(Text.literal(SkullEmoji.GLYPH).setStyle(style));
                i += SkullEmoji.ALIAS.length();
            } else {
                int end = i + 1;
                while (end < plain.length() && styles.get(end).equals(style)
                        && !plain.startsWith(SkullEmoji.ALIAS, end)) end++;
                result.append(Text.literal(plain.substring(i, end)).setStyle(style));
                i = end;
            }
        }
        return result;
    }
}
