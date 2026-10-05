package com.aleks.ancientsmod.client.chat;

import net.minecraft.client.font.BakedGlyph;
import net.minecraft.client.font.GlyphMetrics;
import net.minecraft.client.font.GlyphProvider;
import net.minecraft.client.font.TextDrawable;
import net.minecraft.text.Style;
import net.minecraft.util.math.random.Random;
import java.util.HashMap;
import java.util.Map;

public final class EmojiGlyphProvider {
    private EmojiGlyphProvider() {}

    public static GlyphProvider wrap(GlyphProvider original, GlyphProvider emoji) {
        // Resolve lazily: fonts may still be preparing their atlases during a reload.
        return new GlyphProvider() {
            private final Map<Integer, BakedGlyph> sources = new HashMap<>();
            private final Map<Integer, BakedGlyph> wrapped = new HashMap<>();
            @Override public BakedGlyph get(int codePoint) {
                if (!ChatEmoji.isEmoji(codePoint)) return original.get(codePoint);
                BakedGlyph source = emoji.get(codePoint);
                if (source != sources.get(codePoint)) {
                    sources.put(codePoint, source);
                    wrapped.put(codePoint, flat(source));
                }
                return wrapped.get(codePoint);
            }
            @Override public BakedGlyph getObfuscated(Random random, int width) {
                return original.getObfuscated(random, width);
            }
        };
    }

    /** Full-colour artwork: ignore text colour, shadow, bold and italic, keep alpha. */
    private static BakedGlyph flat(BakedGlyph source) {
        GlyphMetrics metrics = new GlyphMetrics() {
            @Override public float getAdvance() { return source.getMetrics().getAdvance(); }
            @Override public float getBoldOffset() { return 0; }
        };
        return new BakedGlyph() {
            @Override public GlyphMetrics getMetrics() { return metrics; }
            @Override public TextDrawable.DrawnGlyphRect create(float x, float y, int color,
                    int shadowColor, Style style, float boldOffset, float shadowOffset) {
                return source.create(x, y, (color & 0xFF000000) | 0xFFFFFF, 0,
                        style.withBold(false).withItalic(false), 0, 0);
            }
        };
    }
}
