package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.ItemLockUi;
import com.aleks.ancientsmod.client.ItemLocks;
import com.aleks.ancientsmod.client.KeyBinds;
import com.aleks.ancientsmod.client.ServerAllowlist;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts the {@link KeyBinds#TOGGLE_ITEM_LOCK} keybind <i>while an
 * inventory screen is open</i>, because Minecraft suspends normal key-binding
 * polling once a {@link net.minecraft.client.gui.screen.Screen} takes focus.
 *
 * <p>Action: if the lock key fires and the cursor is hovering a player-
 * inventory slot (slots 0..40), flip that slot's lock state and surface a
 * short action-bar confirmation. Other keys pass through to vanilla
 * unchanged.
 *
 * <p>1.21.11 refactored {@code Screen.keyPressed} from {@code (int keyCode,
 * int scanCode, int modifiers)} to {@code (KeyInput input)} — the older
 * signature was the source of the descriptor-mismatch crash.
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenLockKeyMixin {

    @Shadow @Nullable protected Slot focusedSlot;

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void ancientsmod$interceptLockKey(KeyInput input,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!FeatureToggles.isItemLockEnabled()) return;
        if (!KeyBinds.matchesItemLockKey(input)) return;
        Slot slot = this.focusedSlot;
        if (slot == null) return;
        if (!(slot.inventory instanceof PlayerInventory)) return;
        int playerInvSlot = slot.getIndex();
        if (playerInvSlot < 0 || playerInvSlot > 40) return;

        boolean nowLocked = ItemLocks.toggle(playerInvSlot);
        ItemLockUi.notifyToggled(playerInvSlot, nowLocked);
        cir.setReturnValue(true);
    }
}
