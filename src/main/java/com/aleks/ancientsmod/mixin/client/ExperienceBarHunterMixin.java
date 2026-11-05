package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.hud.XpBarStyle;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.bar.ExperienceBar;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Draws the filled part of the XP bar purple while it holds the Hunter level. */
@Mixin(ExperienceBar.class)
public abstract class ExperienceBarHunterMixin {

    @Redirect(
            method = "renderBar",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIIIIIII)V"))
    private void ancientsmod$hunterProgress(DrawContext context, RenderPipeline pipeline, Identifier sprite,
                                            int texW, int texH, int u, int v, int x, int y, int w, int h) {
        if (!XpBarStyle.isHunter()) {
            context.drawGuiTexture(pipeline, sprite, texW, texH, u, v, x, y, w, h);
            return;
        }
        context.drawTexture(RenderPipelines.GUI_TEXTURED, XpBarStyle.HUNTER_BAR, x, y, u, v, w, h, texW, texH);
    }
}
