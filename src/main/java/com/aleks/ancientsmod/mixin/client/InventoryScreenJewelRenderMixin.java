package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.JewelSockets;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the jewel sockets at the very end of the survival inventory's render.
 *
 * <p>Deliberately targets {@link InventoryScreen#render} and not
 * {@code HandledScreen#render}: {@code InventoryScreen.render} calls
 * {@code super.render(...)} first and then paints its own layer (status
 * effects, recipe-book chrome) over the top, so a TAIL inject on the
 * superclass draws the sockets and then has them covered up — which is exactly
 * what happened the first time round. Injecting on the most-derived override
 * puts them last.
 *
 * <p>{@code x}/{@code y}/{@code backgroundWidth} are the live layout fields
 * inherited from HandledScreen, so the recipe-book shift is already baked in.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenJewelRenderMixin {

    @Shadow protected int x;
    @Shadow protected int y;
    @Shadow protected int backgroundWidth;

    @Inject(method = "render", at = @At("TAIL"))
    private void ancientsmod$renderJewelSockets(DrawContext ctx, int mouseX, int mouseY,
                                                float delta, CallbackInfo ci) {
        JewelSockets.render(ctx, x, y, backgroundWidth, mouseX, mouseY);
        JewelSockets.renderTooltip(ctx, x, y, backgroundWidth, mouseX, mouseY);
    }
}
