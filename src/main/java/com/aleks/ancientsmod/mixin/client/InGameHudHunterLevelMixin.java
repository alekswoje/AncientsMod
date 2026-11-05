package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.hud.XpBarStyle;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.hud.bar.Bar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Draws the number above the XP bar purple while it holds the Hunter level. */
@Mixin(InGameHud.class)
public abstract class InGameHudHunterLevelMixin {

    @Redirect(
            method = "renderMainHud",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/hud/bar/Bar;drawExperienceLevel(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;I)V"))
    private void ancientsmod$hunterLevel(DrawContext context, TextRenderer textRenderer, int level) {
        if (XpBarStyle.isHunter()) {
            XpBarStyle.drawHunterLevel(context, textRenderer, level);
        } else {
            Bar.drawExperienceLevel(context, textRenderer, level);
        }
    }
}
