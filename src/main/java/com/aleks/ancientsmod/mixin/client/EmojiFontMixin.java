package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.ServerAllowlist;
import com.aleks.ancientsmod.client.chat.EmojiGlyphProvider;
import net.minecraft.client.font.GlyphProvider;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Route both measurement and drawing through the same glyph, only on Ancients. */
@Mixin(TextRenderer.class)
public abstract class EmojiFontMixin {
    @Shadow @Final private TextRenderer.GlyphsProvider fonts;
    @Unique private GlyphProvider ancientsmod$lastFont;
    @Unique private GlyphProvider ancientsmod$emojiFont;
    @Unique private static final StyleSpriteSource EMOJI_FONT =
            new StyleSpriteSource.Font(Identifier.of("ancientsmod", "emoji"));

    @Inject(method = "getGlyphs", at = @At("RETURN"), cancellable = true)
    private void ancientsmod$emojiFont(StyleSpriteSource source, CallbackInfoReturnable<GlyphProvider> cir) {
        if (!ServerAllowlist.isAllowed() || !StyleSpriteSource.DEFAULT.equals(source)) return;
        GlyphProvider original = cir.getReturnValue();
        if (original != ancientsmod$lastFont) {
            ancientsmod$lastFont = original;
            GlyphProvider emoji = fonts.getGlyphs(EMOJI_FONT);
            ancientsmod$emojiFont = EmojiGlyphProvider.wrap(original, emoji);
        }
        cir.setReturnValue(ancientsmod$emojiFont);
    }

}
