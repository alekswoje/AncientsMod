package com.aleks.prisonsmod.mixin.client;

import com.aleks.prisonsmod.client.BoosterItemOverlay;
import com.aleks.prisonsmod.client.CurrencyAmountOverlay;
import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.GearStatsOverlay;
import com.aleks.prisonsmod.client.ServerAllowlist;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the compact currency amount (e.g. "1m") on EVERY item-stack overlay —
 * hotbar, inventory, containers, creative, the held cursor stack — by hooking
 * the one method they all funnel through ({@code DrawContext.drawStackOverlay}).
 * TAIL so it paints over the icon and the (usually absent) count.
 *
 * <p>This supersedes the screen-only approach: the hotbar is drawn by
 * {@code InGameHud}, not {@code HandledScreen}, so a slot-overlay hook alone
 * missed it.
 */
@Mixin(DrawContext.class)
public abstract class DrawContextStackOverlayMixin {

    @Inject(
            method = "drawStackOverlay(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V",
            at = @At("TAIL"))
    private void prisonsmod$itemOverlays(TextRenderer textRenderer, ItemStack stack, int x, int y,
                                         String countOverride, CallbackInfo ci) {
        if (!ServerAllowlist.isAllowed()) return;
        DrawContext ctx = (DrawContext) (Object) this;
        if (FeatureToggles.isCurrencyAmountOverlayEnabled()) {
            CurrencyAmountOverlay.render(ctx, x, y, stack);
        }
        if (FeatureToggles.isGearStatsOverlayEnabled()) {
            GearStatsOverlay.render(ctx, x, y, stack);
        }
        if (FeatureToggles.isBoosterInfoOverlayEnabled()) {
            BoosterItemOverlay.render(ctx, x, y, stack);
        }
    }
}
