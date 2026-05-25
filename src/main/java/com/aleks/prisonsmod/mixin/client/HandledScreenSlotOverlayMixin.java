package com.aleks.prisonsmod.mixin.client;

import com.aleks.prisonsmod.client.FeatureToggles;
import com.aleks.prisonsmod.client.ItemLocks;
import com.aleks.prisonsmod.client.ServerAllowlist;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws a small yellow padlock badge on locked player-inventory slots so the
 * player can see at a glance which slots are protected. Renders at TAIL of
 * {@code drawSlot} so it overlays the item icon + count.
 *
 * <p>Icon is drawn programmatically from primitive {@link DrawContext#fill}
 * calls — no bundled texture, no extra asset loading. Lives at the top-right
 * corner of the slot in the slot's native 16×16 coordinate space (drawSlot
 * runs after the GUI's translate, so {@code slot.x}/{@code slot.y} are in
 * screen-space).
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenSlotOverlayMixin {

    @Inject(method = "drawSlot", at = @At("TAIL"))
    private void prisonsmod$drawLockBadge(DrawContext context, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!FeatureToggles.isItemLockEnabled()) return;
        if (slot == null) return;
        if (!(slot.inventory instanceof PlayerInventory)) return;
        int playerInvSlot = slot.getIndex();
        if (playerInvSlot < 0 || playerInvSlot > 40) return;
        if (!ItemLocks.isLocked(playerInvSlot)) return;

        drawLock(context, slot.x + 10, slot.y - 1);
    }

    /**
     * Draw a 6×8 padlock at (x, y):
     * <pre>
     *   .##.    shackle top
     *   #..#    shackle sides
     *   ####    lock body top
     *   #YY#    body + keyhole
     *   #YY#    body + keyhole
     *   ####    lock body bottom
     * </pre>
     * Where {@code #} is dark outline, {@code .} is transparent, and the body
     * fill is yellow.
     */
    private static void drawLock(DrawContext ctx, int x, int y) {
        final int outline = 0xFF1A1A1A;
        final int body    = 0xFFFFD93C;
        final int keyhole = 0xFF1A1A1A;

        // Shackle (rows 0-1)
        ctx.fill(x + 1, y,     x + 5, y + 1, outline); // top arc
        ctx.fill(x,     y + 1, x + 1, y + 3, outline); // left leg
        ctx.fill(x + 5, y + 1, x + 6, y + 3, outline); // right leg

        // Body outline + fill (rows 2-5)
        ctx.fill(x,     y + 2, x + 6, y + 6, outline); // full body box
        ctx.fill(x + 1, y + 3, x + 5, y + 5, body);    // yellow interior

        // Keyhole (single dark pixel in the middle)
        ctx.fill(x + 2, y + 4, x + 4, y + 5, keyhole);
    }
}
