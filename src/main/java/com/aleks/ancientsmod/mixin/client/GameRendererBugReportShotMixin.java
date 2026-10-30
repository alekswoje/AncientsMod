package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.bugreport.BugReportShot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The /bugreport screenshot is taken here: the world (and post effects) are in the
 * framebuffer, but the HUD, chat box and any open screen are not drawn yet.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererBugReportShotMixin {
    @Shadow @Final private MinecraftClient client;

    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/fog/FogRenderer;rotate()V"))
    private void ancientsmod$bugReportShot(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        BugReportShot.onWorldRendered(client);
    }
}
