package com.aleks.ancientsmod.client.chat;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class ChatEmojiTest {
    private static final String SKULL = Character.toString(0x1F480);
    private static final String BUNNY = Character.toString(0x1F430);
    private static final String CRY = Character.toString(0x1F62D);

    @Test void convertsMultipleTokensAndRemapsCaretAndSelection() {
        String input = "a :skull::skull: z";
        assertEquals("a " + SKULL + SKULL + " z", ChatEmoji.expand(input));
        assertEquals(2, ChatEmoji.expandedIndex(input, 2));
        assertEquals(4, ChatEmoji.expandedIndex(input, 5));
        assertEquals(4, ChatEmoji.expandedIndex(input, 9));
        assertEquals(6, ChatEmoji.expandedIndex(input, 16));
        assertEquals(8, ChatEmoji.expandedIndex(input, input.length()));
    }

    @Test void onlySuggestsEmojiPrefixesAndPreservesSuffix() {
        for (String prefix : new String[]{":", ":s", ":sk", ":sku", ":skul", ":skull"}) {
            assertEquals(3, ChatEmoji.completionStart("hi " + prefix + " later", 3 + prefix.length()));
        }
        for (String text : new String[]{"hello", ":skull:", ":unknown", "/say :sku", "https:", "minecraft:s"}) {
            assertEquals(-1, ChatEmoji.completionStart(text, text.length()), text);
        }
    }

    @Test void keepsWireTextReadableWithoutChangingCommands() {
        assertEquals("hi :skull:", ChatEmoji.toWire("hi " + SKULL));
        assertEquals("/say " + SKULL, ChatEmoji.toWire("/say " + SKULL));
        assertEquals("hi :skull:", ChatEmoji.toWire("hi :skull:"));
        String full = "x".repeat(254) + SKULL;
        assertEquals(full, ChatEmoji.toWire(full));
    }

    @Test void preservesStylesAndClickEventsAcrossSplitTokens() {
        Style click = Style.EMPTY.withColor(0x12AB34).withClickEvent(new ClickEvent.CopyToClipboard("original"));
        Text source = Text.empty().append(Text.literal("hi :sku").setStyle(click))
                .append(Text.literal("ll:").styled(s -> s.withBold(true)))
                .append(Text.literal(" tail").styled(s -> s.withItalic(true)));
        Text result = EmojiChatText.expand(source);
        assertEquals("hi " + SKULL + " tail", result.getString());
        assertEquals("hi :skull: tail", source.getString());
        var styles = new ArrayList<Style>();
        result.visit((style, value) -> { for (int i = 0; i < value.length(); i++) styles.add(style); return Optional.empty(); }, Style.EMPTY);
        assertEquals(click, styles.get(3));
        assertTrue(styles.get(styles.size() - 1).isItalic());
        Text unchanged = Text.literal("ordinary chat");
        assertSame(unchanged, EmojiChatText.expand(unchanged));
    }

    @Test void handlesEveryAliasLengthTogether() {
        String input = ":bunny: :cry::skull: :nope: :100:";
        assertEquals(BUNNY + " " + CRY + SKULL + " :nope: " + Character.toString(0x1F4AF), ChatEmoji.expand(input));
        assertEquals(input, ChatEmoji.toWire(ChatEmoji.expand(input)));
        assertEquals(3, ChatEmoji.expandedIndex(input, 8));
        assertEquals(5, ChatEmoji.expandedIndex(input, 10));
        assertEquals(ChatEmoji.expand(input).length(), ChatEmoji.expandedIndex(input, input.length()));
    }

    @Test void suggestsEveryMatchingAlias() {
        assertEquals(java.util.List.of(":cry:", ":crown:"), ChatEmoji.completions(":cr"));
        assertEquals(ChatEmoji.ALL.size(), ChatEmoji.completions(":").size());
        assertTrue(ChatEmoji.completions(":bunny:").isEmpty());
    }

    @Test void artworkAndFontCoverEveryEmoji() throws Exception {
        String font = new String(ChatEmojiTest.class.getResourceAsStream("/assets/ancientsmod/font/emoji.json").readAllBytes());
        for (ChatEmoji.Emoji emoji : ChatEmoji.ALL) {
            String name = emoji.alias().substring(1, emoji.alias().length() - 1);
            assertNotNull(ChatEmojiTest.class.getResource("/assets/ancientsmod/textures/emoji/" + name + ".png"), name);
            assertTrue(font.contains("ancientsmod:emoji/" + name + ".png"), name);
            assertTrue(emoji.codePoint() > 0xFFFF, name);
        }
    }
}
