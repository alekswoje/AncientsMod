package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.JewelSockets;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the jewel sockets to the survival inventory screen: three vanilla-look
 * cells down the right edge of the panel that accept a jewel off the cursor.
 *
 * <p>Targets {@link HandledScreen} (which owns {@code render}, {@code
 * mouseClicked} and the {@code x}/{@code y} layout fields) and gates on
 * {@link InventoryScreen}, so the recipe-book shift is picked up automatically
 * — the live {@code x} already accounts for it.
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenJewelSocketsMixin {

    @Shadow protected int x;
    @Shadow protected int y;
    @Shadow protected int backgroundWidth;

    private boolean ancientsmod$isInventory() {
        return (Object) this instanceof InventoryScreen;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void ancientsmod$renderJewelSockets(DrawContext ctx, int mouseX, int mouseY,
                                                float delta, CallbackInfo ci) {
        if (!ancientsmod$isInventory()) return;
        JewelSockets.render(ctx, x, y, backgroundWidth, mouseX, mouseY);
        // After the cells so it layers above them, and after the screen's own
        // render so it isn't painted over by the item tooltip pass.
        JewelSockets.renderTooltip(ctx, x, y, backgroundWidth, mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void ancientsmod$clickJewelSockets(Click click, boolean doubled,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (!ancientsmod$isInventory()) return;
        int button = click.button();
        if (button != 0 && button != 1) return; // left/right only
        if (JewelSockets.onClick(x, y, backgroundWidth, click.x(), click.y())) {
            cir.setReturnValue(true); // consumed — don't let it drop the cursor stack
        }
    }
}
