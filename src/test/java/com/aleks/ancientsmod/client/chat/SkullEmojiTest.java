package com.aleks.ancientsmod.client.chat;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class SkullEmojiTest {
    @Test void convertsMultipleTokensAndRemapsCaretAndSelection() {
        String input = "a :skull::skull: z";
        assertEquals("a " + SkullEmoji.GLYPH + SkullEmoji.GLYPH + " z", SkullEmoji.expand(input));
        assertEquals(2, SkullEmoji.expandedIndex(input, 2));
        assertEquals(4, SkullEmoji.expandedIndex(input, 5));
        assertEquals(4, SkullEmoji.expandedIndex(input, 9));
        assertEquals(6, SkullEmoji.expandedIndex(input, 16));
        assertEquals(8, SkullEmoji.expandedIndex(input, input.length()));
    }

    @Test void onlySuggestsEmojiPrefixesAndPreservesSuffix() {
        for (String prefix : new String[]{":", ":s", ":sk", ":sku", ":skul", ":skull"}) {
            assertEquals(3, SkullEmoji.completionStart("hi " + prefix + " later", 3 + prefix.length()));
        }
        for (String text : new String[]{"hello", ":skull:", ":unknown", "/say :sku", "https:", "minecraft:s"}) {
            assertEquals(-1, SkullEmoji.completionStart(text, text.length()), text);
        }
    }

    @Test void keepsWireTextReadableWithoutChangingCommands() {
        assertEquals("hi :skull:", SkullEmoji.toWire("hi " + SkullEmoji.GLYPH));
        assertEquals("/say " + SkullEmoji.GLYPH, SkullEmoji.toWire("/say " + SkullEmoji.GLYPH));
        assertEquals("hi :skull:", SkullEmoji.toWire("hi :skull:"));
        String full = "x".repeat(254) + SkullEmoji.GLYPH;
        assertEquals(full, SkullEmoji.toWire(full));
    }

    @Test void preservesStylesAndClickEventsAcrossSplitTokens() {
        Style click = Style.EMPTY.withColor(0x12AB34).withClickEvent(new ClickEvent.CopyToClipboard("original"));
        Text source = Text.empty().append(Text.literal("hi :sku").setStyle(click))
                .append(Text.literal("ll:").styled(s -> s.withBold(true)))
                .append(Text.literal(" tail").styled(s -> s.withItalic(true)));
        Text result = EmojiChatText.expand(source);
        assertEquals("hi " + SkullEmoji.GLYPH + " tail", result.getString());
        assertEquals("hi :skull: tail", source.getString());
        var styles = new ArrayList<Style>();
        result.visit((style, value) -> { for (int i = 0; i < value.length(); i++) styles.add(style); return Optional.empty(); }, Style.EMPTY);
        assertEquals(click, styles.get(3));
        assertTrue(styles.get(styles.size() - 1).isItalic());
        Text unchanged = Text.literal("ordinary chat");
        assertSame(unchanged, EmojiChatText.expand(unchanged));
    }
}
