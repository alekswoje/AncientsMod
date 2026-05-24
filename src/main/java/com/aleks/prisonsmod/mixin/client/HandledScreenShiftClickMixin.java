package com.aleks.prisonsmod.mixin.client;

import com.aleks.prisonsmod.client.ServerAllowlist;
import com.aleks.prisonsmod.client.pv.PvClient;
import com.aleks.prisonsmod.net.NetworkHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Eliminates client-prediction flicker when shift-clicking items from the
 * player inventory into a Personal Vault menu.
 *
 * <p><b>Why</b>. Without this mixin, shift-clicking in a PV menu fires the
 * vanilla {@code ServerboundContainerClickPacket} which carries the client's
 * <i>predicted</i> post-click state (item now in the open container). The
 * server-side {@code PvAffinityRouter} then cancels the event and reroutes
 * the item to a different PV (or clusters within the open one). The client
 * has already painted the predicted state, so it has to revert when the
 * server's correction arrives — visible flicker.
 *
 * <p><b>What</b>. We intercept the click at HEAD, send a custom
 * {@code PKT_PV_SHIFT_CLICK_REQ} with the player-inventory slot index, and
 * cancel the vanilla call. The server runs the same routing logic and sends
 * authoritative inventory updates through normal channels — the client never
 * predicts, so there's nothing to correct.
 *
 * <p><b>Scope</b>. Only intercepts:
 * <ul>
 *   <li>{@link SlotActionType#QUICK_MOVE} (shift+click)</li>
 *   <li>Screen title starts with "Personal Vault " (per-PV inventory)</li>
 *   <li>Slot is in player inventory (main + hotbar, 0..35), not armor/offhand</li>
 *   <li>The cached PvBundle reports at least one affinity is set —
 *       otherwise vanilla shift-click is exactly what we want anyway</li>
 * </ul>
 *
 * <p>Other directions (PV → player inv, within player inv, hotbar swaps,
 * non-PV screens) pass through to vanilla unchanged.
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenShiftClickMixin {

    @Inject(method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V",
            at = @At("HEAD"), cancellable = true)
    private void prisonsmod$interceptPvShiftClick(Slot slot, int slotId, int button,
                                                  SlotActionType actionType, CallbackInfo ci) {
        if (actionType != SlotActionType.QUICK_MOVE) return;
        if (slot == null) return;
        if (!ServerAllowlist.isAllowed()) return;

        // Only intercept on PV per-vault inventories. The /pv overview chest
        // (title "Personal Vaults" plural) and the affinity picker have their
        // own titles and we ignore them — they're handled by mod-side screens
        // anyway, not vanilla shift-clicks.
        @SuppressWarnings("unchecked")
        HandledScreen<?> self = (HandledScreen<?>)(Object) this;
        Text titleText = self.getTitle();
        if (titleText == null) return;
        String title = titleText.getString();
        if (title == null || !title.startsWith("Personal Vault ")) return;
        // Plural "Personal Vaults" overview is a chest — must not match. The
        // "starts with Personal Vault " (note trailing space before the
        // number) check above excludes it because the overview's title is
        // exactly "Personal Vaults" (no trailing space, no number).

        // Only intercept shift-clicks ORIGINATING in the player inventory.
        // PV → player-inv shift-clicks (the other direction) should stay
        // vanilla — they don't trigger our server-side affinity router.
        if (!(slot.inventory instanceof PlayerInventory)) return;
        int playerInvSlot = slot.getIndex();
        if (playerInvSlot < 0 || playerInvSlot > 35) return; // skip armor (36-39) / offhand (40)

        // Cheap skip: no affinity set anywhere → vanilla shift-click is the
        // correct behavior; don't burn a packet round-trip for it.
        if (!PvClient.hasAnyAffinity()) return;

        NetworkHandler.sendPvShiftClick(playerInvSlot);
        ci.cancel();
    }
}
