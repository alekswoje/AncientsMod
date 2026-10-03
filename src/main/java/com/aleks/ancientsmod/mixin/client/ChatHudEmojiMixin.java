package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.ServerAllowlist;
import com.aleks.ancientsmod.client.chat.EmojiChatText;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatHud.class)
public abstract class ChatHudEmojiMixin {
    @ModifyVariable(method = "addVisibleMessage", at = @At("HEAD"), argsOnly = true)
    private ChatHudLine ancientsmod$displayEmoji(ChatHudLine line) {
        if (!ServerAllowlist.isAllowed()) return line;
        return new ChatHudLine(line.creationTick(), EmojiChatText.expand(line.content()), line.signature(), line.indicator());
    }
}
