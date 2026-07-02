package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.ItemLockUi;
import com.aleks.ancientsmod.client.ItemLocks;
import com.aleks.ancientsmod.client.ServerAllowlist;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks the in-world Q (drop one) and Ctrl+Q (drop stack) presses when the
 * currently-selected hotbar slot is locked. Mirrors
 * {@link HandledScreenItemLockMixin} for the no-screen-open path: the slot
 * mixin handles drops/clicks that originate inside an inventory GUI; this one
 * catches the raw drop-key press.
 *
 * <p>Mixed into {@link ClientPlayerEntity} where {@code dropSelectedItem}
 * lives in 1.21.11 (the method was moved off {@code PlayerEntity}). There is
 * only ever one local {@link ClientPlayerEntity}, so no instance gate needed.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerItemLockMixin {

    @Inject(method = "dropSelectedItem", at = @At("HEAD"), cancellable = true)
    private void ancientsmod$blockLockedDrop(boolean entireStack,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!FeatureToggles.isItemLockEnabled()) return;

        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        PlayerInventory inv = self.getInventory();
        int selected = inv.getSelectedSlot();
        if (selected < 0 || selected > 8) return; // safety — selected is always hotbar
        if (!ItemLocks.isLocked(selected)) return;

        ItemLockUi.notifyBlocked(entireStack ? "Ctrl+Q (stack) on locked hotbar " + (selected + 1)
                                             : "Q drop on locked hotbar " + (selected + 1));
        cir.setReturnValue(false);
    }
}
