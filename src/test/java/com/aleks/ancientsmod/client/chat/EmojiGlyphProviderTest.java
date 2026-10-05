package com.aleks.ancientsmod.client.chat;

import net.minecraft.client.font.*;
import net.minecraft.text.Style;
import net.minecraft.util.math.random.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmojiGlyphProviderTest {
    @Test void preservesArtworkAndAlphaWithoutChangingNormalTextMetrics() {
        StubGlyph normal = new StubGlyph(6);
        StubGlyph skull = new StubGlyph(10);
        GlyphProvider result = EmojiGlyphProvider.wrap(provider(normal), provider(skull));
        assertSame(normal, result.get('a'));
        BakedGlyph rendered = result.get(0x1F480);
        assertEquals(10, rendered.getMetrics().getAdvance(true));
        assertSame(rendered, result.get(0x1F480));
        rendered.create(1, 2, 0x7F12AB34, 0xFF010203,
                Style.EMPTY.withBold(true).withItalic(true), 1, 1);
        assertEquals(0x7FFFFFFF, skull.color);
        assertEquals(0, skull.shadow);
        assertFalse(skull.style.isBold());
        assertFalse(skull.style.isItalic());
    }

    private static GlyphProvider provider(BakedGlyph glyph) {
        return new GlyphProvider() {
            public BakedGlyph get(int point) { return glyph; }
            public BakedGlyph getObfuscated(Random random, int width) { return glyph; }
        };
    }

    private static class StubGlyph implements BakedGlyph {
        private final float advance;
        int color, shadow;
        Style style;
        StubGlyph(float advance) { this.advance = advance; }
        public GlyphMetrics getMetrics() { return () -> advance; }
        public TextDrawable.DrawnGlyphRect create(float x, float y, int color, int shadow,
                Style style, float bold, float offset) {
            this.color = color; this.shadow = shadow; this.style = style;
            return null;
        }
    }
}
