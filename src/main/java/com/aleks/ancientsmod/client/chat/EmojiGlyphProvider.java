package com.aleks.ancientsmod.client.chat;

import net.minecraft.client.font.BakedGlyph;
import net.minecraft.client.font.GlyphMetrics;
import net.minecraft.client.font.GlyphProvider;
import net.minecraft.client.font.TextDrawable;
import net.minecraft.text.Style;
import net.minecraft.util.math.random.Random;

public final class EmojiGlyphProvider {
    private EmojiGlyphProvider() {}

    public static GlyphProvider wrap(GlyphProvider original, GlyphProvider emoji) {
        // Resolve lazily: fonts may still be preparing their atlases during a reload.
        return new GlyphProvider() {
            private BakedGlyph cachedOriginal;
            private BakedGlyph cachedSkull;
            @Override public BakedGlyph get(int codePoint) {
                if (codePoint != SkullEmoji.CODE_POINT) return original.get(codePoint);
                BakedGlyph skull = emoji.get(codePoint);
                if (skull != cachedOriginal) {
                    cachedOriginal = skull;
                    GlyphMetrics metrics = new GlyphMetrics() {
                        @Override public float getAdvance() { return skull.getMetrics().getAdvance(); }
                        @Override public float getBoldOffset() { return 0; }
                    };
                    cachedSkull = new BakedGlyph() {
                        @Override public GlyphMetrics getMetrics() { return metrics; }
                        @Override public TextDrawable.DrawnGlyphRect create(float x, float y, int color,
                                int shadowColor, Style style, float boldOffset, float shadowOffset) {
                            return skull.create(x, y, (color & 0xFF000000) | 0xFFFFFF, 0,
                                    style.withBold(false).withItalic(false), 0, 0);
                        }
                    };
                }
                return cachedSkull;
            }
            @Override public BakedGlyph getObfuscated(Random random, int width) {
                return original.getObfuscated(random, width);
            }
        };
    }
}
