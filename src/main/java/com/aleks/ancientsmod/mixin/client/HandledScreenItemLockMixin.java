package com.aleks.ancientsmod.mixin.client;

import com.aleks.ancientsmod.client.FeatureToggles;
import com.aleks.ancientsmod.client.ItemLockUi;
import com.aleks.ancientsmod.client.ItemLocks;
import com.aleks.ancientsmod.client.ServerAllowlist;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.collection.DefaultedList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enforces item-lock state on slot clicks inside any {@link HandledScreen}.
 * Treats a locked player-inventory slot as <b>read-only</b>: any click action
 * that would mutate it — pick up, place, swap, shift-move, throw, or vacuum —
 * is silently cancelled and the player gets a brief action-bar nudge.
 *
 * <p>Specifically blocked when the targeted slot is locked:
 * <ul>
 *   <li>{@code PICKUP} (left/right click) — regardless of cursor state.
 *       Yes, that also prevents placing onto a locked slot; intentional, since
 *       the cleanest mental model is "locked = don't touch."</li>
 *   <li>{@code QUICK_MOVE} (shift+click) — would whisk the item into the
 *       adjacent container.</li>
 *   <li>{@code THROW} (Q hover / Ctrl+Q hover) — drops the slot's stack.</li>
 *   <li>{@code PICKUP_ALL} (double-left-click) — would vacuum the slot when
 *       the cursor matches.</li>
 * </ul>
 *
 * <p>And, separately, blocked when the lock is on a hotbar/offhand source of
 * a {@code SWAP}: the click might land on an unlocked container slot, but the
 * <i>source</i> (e.g. hotbar slot 4 mapped to keyboard button 4) would be
 * displaced. The {@code button} for {@code SWAP} is the player-inv index of
 * the source: 0..8 for the number-row hotbar swap, or 40 for the offhand F
 * swap.
 *
 * <p>Additionally guards {@code PICKUP_ALL} when targeting an <i>unlocked</i>
 * slot: if any locked slot holds a stack of the same item type as the cursor,
 * vanilla's vacuum would still drain it — so the whole double-click is
 * blocked.
 *
 * <p>Off-allowlist servers, the feature toggle being off, and non-player-
 * inventory slots all fall through to vanilla. Drop-key handling outside any
 * screen lives in {@link ClientPlayerItemLockMixin}.
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenItemLockMixin {

    @Inject(method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V",
            at = @At("HEAD"), cancellable = true)
    private void ancientsmod$enforceItemLock(Slot slot, int slotId, int button,
                                            SlotActionType actionType, CallbackInfo ci) {
        if (!ServerAllowlist.isAllowed()) return;
        if (!FeatureToggles.isItemLockEnabled()) return;

        // SWAP-from-locked-hotbar — the destination slot may be anywhere, but
        // the source is identified by `button`. Block before we even look at
        // the destination.
        if (actionType == SlotActionType.SWAP) {
            int sourceInv = button; // 0..8 hotbar, 40 offhand
            if (isLockedPlayerInvSlot(sourceInv)) {
                ItemLockUi.notifyBlocked("locked " + ItemLockUi.describeSlot(sourceInv));
                ci.cancel();
                return;
            }
            // Fall through — also check the destination slot below.
        }

        // PICKUP_ALL — vanilla scans the open ScreenHandler's slots for stacks
        // matching the cursor type and merges them into the cursor. If any of
        // those would-be donors live in a locked player-inv slot, block the
        // whole action. Cheap because we iterate only the open screen's slots.
        if (actionType == SlotActionType.PICKUP_ALL) {
            @SuppressWarnings("unchecked")
            HandledScreen<?> self = (HandledScreen<?>) (Object) this;
            ScreenHandler handler = self.getScreenHandler();
            ItemStack cursor = handler.getCursorStack();
            if (!cursor.isEmpty() && screenHasLockedDonor(handler, cursor)) {
                ItemLockUi.notifyBlocked("locked stack of the same item");
                ci.cancel();
                return;
            }
        }

        // All remaining action types are slot-targeted. If `slot` isn't a
        // player-inv slot, nothing here cares.
        if (slot == null) return;
        if (!(slot.inventory instanceof PlayerInventory)) return;
        int playerInvSlot = slot.getIndex();
        if (!isLockedPlayerInvSlot(playerInvSlot)) return;

        // From here on the targeted slot is locked. Block any mutation.
        switch (actionType) {
            case PICKUP, QUICK_MOVE, THROW, PICKUP_ALL, SWAP -> {
                ItemLockUi.notifyBlocked("locked " + ItemLockUi.describeSlot(playerInvSlot));
                ci.cancel();
            }
            default -> {
                // QUICK_CRAFT / CLONE / etc. — fall through. CLONE is creative-
                // only and we don't care about quick-craft drag interactions on
                // a single locked slot.
            }
        }
    }

    private static boolean isLockedPlayerInvSlot(int slot) {
        return slot >= 0 && slot <= 40 && ItemLocks.isLocked(slot);
    }

    /** Walk the open ScreenHandler's slots and look for a locked player-inv slot holding a stack with the same item type as {@code cursor}. */
    private static boolean screenHasLockedDonor(ScreenHandler handler, ItemStack cursor) {
        DefaultedList<Slot> slots = handler.slots;
        for (Slot s : slots) {
            if (!(s.inventory instanceof PlayerInventory)) continue;
            int idx = s.getIndex();
            if (!isLockedPlayerInvSlot(idx)) continue;
            ItemStack stack = s.getStack();
            if (stack.isEmpty()) continue;
            if (ItemStack.areItemsEqual(stack, cursor)) return true;
        }
        return false;
    }
}
