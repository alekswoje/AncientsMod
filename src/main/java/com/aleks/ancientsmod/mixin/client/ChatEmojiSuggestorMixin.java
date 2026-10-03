package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.ServerAllowlist;
import com.aleks.ancientsmod.client.chat.SkullEmoji;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.client.gui.screen.ChatInputSuggestor;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.concurrent.CompletableFuture;

@Mixin(ChatInputSuggestor.class)
public abstract class ChatEmojiSuggestorMixin {
    @Shadow @Final private Screen owner;
    @Shadow @Final TextFieldWidget textField;
    @Shadow private CompletableFuture<Suggestions> pendingSuggestions;
    @Shadow boolean completingSuggestions;
    @Shadow public abstract void show(boolean narrateFirstSuggestion);

    @Inject(method = "refresh", at = @At("TAIL"))
    private void ancientsmod$suggestSkull(CallbackInfo ci) {
        if (!ServerAllowlist.isAllowed() || !(owner instanceof ChatScreen) || completingSuggestions) return;
        String text = textField.getText();
        int cursor = textField.getCursor();
        int start = SkullEmoji.completionStart(text, cursor);
        if (start < 0) return;
        pendingSuggestions = new SuggestionsBuilder(text.substring(0, cursor), start)
                .suggest(SkullEmoji.ALIAS).buildFuture();
        show(false);
    }
}
