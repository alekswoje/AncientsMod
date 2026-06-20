package com.aleks.prisonsmod.mixin.client;

import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.ServerAllowlist;
import com.aleks.prisonsmod.client.wiki.InteractiveItemTooltip;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses vanilla's hovered-item tooltip while the in-mod wiki has a tooltip
 * pinned (or a wiki popup open). Without this, moving the cursor onto the pinned
 * tooltip would also hover the slots underneath it and spawn a second, competing
 * vanilla tooltip. While suppressed, {@link InteractiveItemTooltip} draws the
 * frozen tooltip itself.
 *
 * @see InteractiveItemTooltip
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenTooltipSuppressMixin {

    @Inject(method = "drawMouseoverTooltip", at = @At("HEAD"), cancellable = true)
    private void prisonsmod$suppressWhilePinned(DrawContext context, int x, int y, CallbackInfo ci) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!FeatureToggles.isItemWikiEnabled()) return;
        if (InteractiveItemTooltip.isSuppressingVanillaTooltip()) {
            ci.cancel();
        }
    }
}
