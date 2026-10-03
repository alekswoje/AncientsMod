package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.ServerAllowlist;
import com.aleks.ancientsmod.client.chat.SkullEmoji;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatScreen.class)
public abstract class ChatScreenEmojiMixin {
    @Shadow protected TextFieldWidget chatField;

    // After keyboard/completion handling has finished, before the first painted frame.
    // Mutating from the changed-listener would corrupt vanilla completion's caret.
    @Inject(method = "render", at = @At("HEAD"))
    private void ancientsmod$expandEmoji(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!ServerAllowlist.isAllowed()) return;
        String text = chatField.getText();
        if (text.startsWith("/") || !text.contains(SkullEmoji.ALIAS)) return;
        int cursor = SkullEmoji.expandedIndex(text, chatField.getCursor());
        int selection = SkullEmoji.expandedIndex(text, ((TextFieldSelectionAccessor) chatField).ancientsmod$selectionEnd());
        chatField.setText(SkullEmoji.expand(text));
        chatField.setCursor(cursor, false);
        chatField.setSelectionEnd(selection);
    }

    @ModifyVariable(method = "sendMessage", at = @At("HEAD"), argsOnly = true)
    private String ancientsmod$readableWireText(String text) {
        return ServerAllowlist.isAllowed() ? SkullEmoji.toWire(text) : text;
    }
}
